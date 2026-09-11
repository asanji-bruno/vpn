# BDNET TUNNEL — Server Architecture Plan (README_SERVER)

> **Server Role**: Acts as the exit node for all tunnel traffic. Receives encoded/encrypted
> data from the client app, decodes it, forwards to the real internet, and returns responses.
> Must support ALL 6 tunneling methods. Strong, modular, fully configurable via JSON.

---

## 1. Server Responsibilities

- Receive and decode DNS tunnel queries (method 1)
- Accept WebSocket TLS connections as proxy relay (method 2)
- Bridge WebSocket connections to SSH port (method 3)
- Handle SNI-injected TLS connections and route to real destinations (method 4)
- Accept VLESS protocol connections (method 5)
- Run an HTTP CONNECT proxy (method 6)
- Expose a management API for the client app to query status, update config
- Forward all proxied traffic to the open internet via NAT/iptables
- Log all activity to rotating log files

---

## 2. Server Architecture Diagram

```
                        INTERNET
                           │
                           │
    ┌──────────────────────────────────────────────────────────────┐
    │                   YOUR VPS / SERVER                          │
    │                                                              │
    │   ┌──────────────────────────────────────────────────────┐  │
    │   │               Nginx (Reverse Proxy)                  │  │
    │   │                                                      │  │
    │   │  Port 443 (HTTPS/WSS):                               │  │
    │   │    /ws         → ws_relay.py  (WebSocket proxy)      │  │
    │   │    /vless      → vless_server.py                     │  │
    │   │    /ssh-relay  → ssh_ws_bridge.py                    │  │
    │   │    /api/*      → server.py  (Flask management)       │  │
    │   │    /health     → server.py  (health check)           │  │
    │   │    /           → server.py  (camouflage HTML page)   │  │
    │   │                                                      │  │
    │   │  Port 80 (HTTP):                                      │  │
    │   │    /           → redirect to HTTPS                   │  │
    │   │    /connect    → http_proxy.py (CONNECT method)      │  │
    │   │                                                      │  │
    │   └──────────────────────────────────────────────────────┘  │
    │                                                              │
    │   ┌──────────────────────────────────────────────────────┐  │
    │   │         Python Server Processes                       │  │
    │   │                                                       │  │
    │   │   server.py        → Flask API (port 5001)           │  │
    │   │   dns_server.py    → UDP port 53 (DNS authoritative) │  │
    │   │   ws_relay.py      → asyncio WebSocket relay         │  │
    │   │   ssh_ws_bridge.py → WebSocket ↔ SSH bridge          │  │
    │   │   vless_server.py  → VLESS inbound (port 10000)      │  │
    │   │   http_proxy.py    → HTTP CONNECT proxy (port 3128)  │  │
    │   └──────────────────────────────────────────────────────┘  │
    │                                                              │
    │   ┌──────────────────────────────────────────────────────┐  │
    │   │         iptables / IP Forwarding (NAT)                │  │
    │   │   net.ipv4.ip_forward = 1                             │  │
    │   │   iptables -t nat -A POSTROUTING -j MASQUERADE        │  │
    │   └──────────────────────────────────────────────────────┘  │
    └──────────────────────────────────────────────────────────────┘
    │
    ├── UDP 53  ← DNS Tunnel (external, no Nginx)
    ├── TCP 80  ← HTTP (Nginx → redirect/HTTP proxy)
    └── TCP 443 ← HTTPS/WSS (Nginx → routes to services)
```

---

## 3. Server Config File — server_config.json (ALL Editable)

```json
{
  "server": {
    "host": "0.0.0.0",
    "api_port": 5001,
    "domain": "yourdomain.com",
    "tunnel_subdomain": "t.yourdomain.com",
    "tls_cert": "/etc/letsencrypt/live/yourdomain.com/fullchain.pem",
    "tls_key": "/etc/letsencrypt/live/yourdomain.com/privkey.pem"
  },
  "auth": {
    "master_token": "CHANGE_THIS_SECRET_TOKEN",
    "allowed_uuids": ["UUID-1-HERE", "UUID-2-HERE"],
    "require_auth": true
  },
  "methods": {
    "dns_tunnel": {
      "enabled": true,
      "port": 53,
      "protocol": "udp",
      "tunnel_domain": "t.yourdomain.com",
      "encode": "base32",
      "record_types": ["TXT", "NULL"],
      "max_clients": 10,
      "session_timeout": 120
    },
    "ws_relay": {
      "enabled": true,
      "internal_port": 8001,
      "path": "/ws",
      "max_frame_size": 65536,
      "ping_interval": 20,
      "max_connections": 50
    },
    "ssh_ws_bridge": {
      "enabled": true,
      "internal_port": 8002,
      "path": "/ssh-relay",
      "target_host": "127.0.0.1",
      "target_port": 22
    },
    "vless_server": {
      "enabled": true,
      "internal_port": 10000,
      "path": "/vless",
      "allowed_uuids": ["UUID-1-HERE"],
      "network": "ws",
      "tls": true
    },
    "http_connect": {
      "enabled": true,
      "port": 3128,
      "allow_all_targets": false,
      "allowed_ports": [80, 443, 22, 8080]
    }
  },
  "nat": {
    "enabled": true,
    "interface": "eth0",
    "ip_forward": true
  },
  "logging": {
    "level": "DEBUG",
    "log_dir": "/var/log/bdnet/",
    "rotate_mb": 10,
    "keep_files": 5,
    "log_connections": true,
    "log_bytes": false
  },
  "rate_limit": {
    "enabled": true,
    "max_dns_qps": 50,
    "max_ws_connections_per_ip": 5,
    "ban_threshold": 200,
    "ban_duration_seconds": 300
  }
}
```

---

## 4. Service 1 — dns_server.py (DNS Tunnel Server)

### Protocol Detail

The DNS tunnel server acts as an **Authoritative Name Server** for the tunnel subdomain.
For this to work, DNS NS records must point to your VPS.

**DNS Setup (registrar/DNS panel):**
```
yourdomain.com.    A      YOUR_VPS_IP
ns1.tunnel.com.    A      YOUR_VPS_IP
t.yourdomain.com.  NS     ns1.yourdomain.com.
```

### Packet Flow

```
Client sends DNS query:
  QNAME: 001-R2LUGY3UNVPWYZLOO.t.yourdomain.com
  TYPE:  TXT
  
Server receives UDP packet on port 53:
  1. Parse DNS packet using dnslib
  2. Extract QNAME subdomain: "001-R2LUGY3UNVPWYZLOO"
  3. Decode Base32: "001" = seq_id, rest = encoded data chunk
  4. Append chunk to session buffer
  5. When full packet assembled: forward to real destination
  6. Receive response, encode as Base32 TXT records
  7. Return DNS response with TXT = encoded response data
```

### Session Management

```python
# Each client identified by random session_id embedded in queries
# Sessions stored in dict: {session_id: Session}

class Session:
    client_addr: tuple       # (ip, port)
    rx_buffer: dict          # seq_id → data chunk
    tx_queue: queue.Queue    # response chunks waiting to be sent
    target_socket: socket    # connection to real destination
    last_seen: float         # for timeout cleanup
    total_rx_bytes: int
    total_tx_bytes: int
```

### Python Implementation Plan (dns_server.py)

```python
# Key classes:

class DNSTunnelServer:
    def __init__(self, config): ...
    def start(self): ...           # Bind UDP port 53, start listener loop
    def stop(self): ...
    def handle_query(self, data, addr): ...  # Main query handler
    def decode_chunk(self, qname): ...       # Base32 decode from QNAME
    def encode_response(self, data): ...     # Base32 encode to TXT records
    def forward_to_target(self, session, data): ...  # Forward to internet
    def get_or_create_session(self, session_id, addr): ...

class DNSTunnelSession:
    def __init__(self, session_id, addr): ...
    def add_chunk(self, seq_id, data): ...
    def get_pending_response(self): ...
    def connect_to_target(self, host, port): ...
    def close(self): ...
```

---

## 5. Service 2 — ws_relay.py (WebSocket Proxy Relay)

### Purpose
Accept WebSocket connections from clients and relay TCP streams to internet destinations.
Handles stream multiplexing (multiple concurrent connections per WebSocket).

### Multiplexing Protocol (Custom Framing)

```
Each frame over WebSocket:
┌────────────────┬────────────────┬──────────────────────────────────┐
│ stream_id      │ msg_type       │ data                             │
│ (4 bytes)      │ (1 byte)       │ (variable)                       │
└────────────────┴────────────────┴──────────────────────────────────┘

msg_type values:
  0x01 = OPEN    (data = "host:port")
  0x02 = DATA    (data = raw bytes)
  0x03 = CLOSE   (data = empty)
  0x04 = ERROR   (data = error string)
  0x05 = AUTH    (data = uuid token)
```

### Implementation Plan (ws_relay.py)

```python
# Built with asyncio + websockets library

class WebSocketRelay:
    def __init__(self, config): ...
    async def start(self): ...
    async def handle_client(self, websocket, path): ...
    async def authenticate(self, websocket): ...    # Check UUID token
    async def handle_frame(self, websocket, frame): ...
    async def open_stream(self, stream_id, host, port): ...
    async def relay_stream(self, stream_id, ws, target_sock): ...
    async def close_stream(self, stream_id): ...
```

### Connection Flow

```
Client WS connects to wss://server.com/ws
  │
  ├── Server sends AUTH challenge
  ├── Client sends AUTH frame with UUID
  ├── Server validates UUID
  │
  ├── Client sends OPEN frame: {stream_id: 1, host: "google.com", port: 443}
  ├── Server opens TCP socket to google.com:443
  │
  ├── Client sends DATA frames (TLS ClientHello, etc.)
  ├── Server relays to google.com TCP socket
  │
  ├── google.com responds
  ├── Server sends DATA frames back to client
  │
  └── Client sends CLOSE frame → server closes TCP socket
```

---

## 6. Service 3 — ssh_ws_bridge.py (SSH over WebSocket Bridge)

### Purpose
Accept WebSocket connections and bridge them to the SSH port (22) on localhost.
This lets clients SSH through a WebSocket, bypassing port 22 blocks.

### Implementation

```python
# Very simple bridge: WS ↔ raw TCP socket to localhost:22

class SSHWebSocketBridge:
    async def handle(self, websocket, path):
        # 1. Open TCP socket to self.target_host:self.target_port
        # 2. Start two async tasks:
        #    - ws_to_tcp: read from WebSocket, write to TCP
        #    - tcp_to_ws: read from TCP, write to WebSocket
        # 3. Run until either side closes
```

### How Client Uses It
```
Client connects: ws://server.com/ssh-relay
Server bridges: WS ↔ localhost:22 (SSH daemon)
Client wraps paramiko over WS socket object
SSH handshake completes inside WebSocket
SSH dynamic forwarding (-D) → SOCKS5 proxy on client
```

---

## 7. Service 4 — vless_server.py (VLESS Inbound)

### VLESS Server Protocol

VLESS is a stateless protocol. Each connection starts with a VLESS header:

```
Byte 0:     Version (0x00)
Bytes 1-16: Client UUID (128-bit)
Byte 17:    Addon length (usually 0x00)
Byte 18:    Command (0x01 = TCP connect)
Bytes 19-20: Destination port (big-endian uint16)
Byte 21:    Address type (0x01=IPv4, 0x02=domain, 0x03=IPv6)
Bytes 22+:  Destination address (length prefix if domain)
Remaining:  Proxied data
```

### Implementation

```python
class VLESSServer:
    def __init__(self, config):
        self.allowed_uuids = set(config["allowed_uuids"])
        self.ws_path = config["path"]

    async def handle_connection(self, websocket, path):
        # 1. Read first frame (VLESS header)
        # 2. Parse UUID — validate against allowed_uuids
        # 3. Parse destination host:port
        # 4. Open TCP connection to destination
        # 5. Relay data bidirectionally
        # 6. Send VLESS response header (version + addon)

    def parse_vless_header(self, data: bytes) -> dict:
        # Returns: {uuid, command, port, addr_type, address}

    def validate_uuid(self, uuid_bytes: bytes) -> bool:
        import uuid
        client_uuid = str(uuid.UUID(bytes=uuid_bytes))
        return client_uuid in self.allowed_uuids
```

---

## 8. Service 5 — http_proxy.py (HTTP CONNECT Proxy)

### Purpose
A standard HTTP/HTTPS proxy accepting CONNECT tunnel requests.

### Implementation

```python
class HTTPConnectProxy:
    def handle_client(self, client_sock, addr):
        # 1. Read HTTP request
        # 2. Check if CONNECT method
        # 3. Parse "CONNECT host:port HTTP/1.1"
        # 4. Open TCP to host:port
        # 5. Send "HTTP/1.1 200 Connection Established\r\n\r\n"
        # 6. Relay bytes bidirectionally
        # 7. Close when either side disconnects
```

---

## 9. Flask Management API — server.py

The Flask API provides remote management endpoints callable by the client app.

### API Endpoints

| Route | Method | Auth | Description |
|---|---|---|---|
| `/health` | GET | No | Health check (Render keep-alive ping) |
| `/` | GET | No | Camouflage webpage (looks like a normal site) |
| `/api/status` | GET | Token | Server status, active connections, uptime |
| `/api/config` | GET | Token | Current server_config.json |
| `/api/config` | POST | Token | Update and reload server config |
| `/api/services` | GET | Token | Status of each service (DNS, WS, SSH, etc.) |
| `/api/services/{name}/start` | POST | Token | Start a specific service |
| `/api/services/{name}/stop` | POST | Token | Stop a specific service |
| `/api/logs` | GET | Token | Recent log lines (last 200) |
| `/api/sessions` | GET | Token | Active tunnel sessions |
| `/api/sessions/{id}` | DELETE | Token | Kill a specific session |

### Authentication
All `/api/*` routes require header: `Authorization: Bearer MASTER_TOKEN`
The master token is set in `server_config.json`.

### Camouflage Page
The root `/` serves a plain HTML page that looks like a generic website
(e.g., "Tech Blog" or "Personal Portfolio") so the server doesn't look like a proxy
server to an observer scanning the domain.

---

## 10. Nginx Configuration (nginx.conf.template)

```nginx
server {
    listen 80;
    server_name ${DOMAIN};
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name ${DOMAIN};

    ssl_certificate     ${TLS_CERT};
    ssl_certificate_key ${TLS_KEY};
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # Health check (no auth — for Render/uptime monitors)
    location /health {
        proxy_pass http://127.0.0.1:${API_PORT}/health;
    }

    # WebSocket Relay (Method 2)
    location /ws {
        proxy_pass http://127.0.0.1:8001;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # SSH over WebSocket Bridge (Method 3)
    location /ssh-relay {
        proxy_pass http://127.0.0.1:8002;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }

    # VLESS (Method 5)
    location /vless {
        proxy_pass http://127.0.0.1:10000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }

    # Flask Management API
    location /api/ {
        proxy_pass http://127.0.0.1:${API_PORT}/api/;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # Camouflage root page
    location / {
        proxy_pass http://127.0.0.1:${API_PORT}/;
    }
}
```

---

## 11. Server Python Dependencies (requirements.txt)

```
flask==3.0.3
flask-socketio==5.3.6
eventlet==0.36.1
gunicorn==22.0.0
websockets==12.0
dnslib==0.9.24
paramiko==3.4.0
cryptography==42.0.8
requests==2.32.3
python-dotenv==1.0.1
```

---

## 12. Deployment Guide

### Option A — Render.com (Free Tier, Methods 2/5 Only)

```
1. Push server/ folder to GitHub
2. Connect repo to Render.com (New Web Service)
3. Build command: pip install -r requirements.txt
4. Start command: gunicorn server:app --worker-class eventlet -w 1 -b 0.0.0.0:$PORT
5. Add env vars in Render dashboard:
   - MASTER_TOKEN=your_secret_token
   - DOMAIN=your.onrender.com
6. Note Render URL → enter it in app client config.json
7. DNS tunnel NOT available (Render can't bind UDP 53 or custom ports)
```

### Option B — ngrok (Local Dev, All Methods Except DNS)

```
1. Install ngrok + authenticate
2. Run server locally:
   python server.py          (port 5001)
   python ws_relay.py        (port 8001)
   python ssh_ws_bridge.py   (port 8002)
3. ngrok http 5001           → management API
4. ngrok http 8001           → WebSocket relay (upgrade WS supported)
5. Enter ngrok URL in app client config.json
```

### Option C — Custom VPS (All Methods, Recommended for DNS Tunnel)

```
1. Linux VPS (Ubuntu 22.04 / Debian 12), min 512MB RAM
2. Domain with NS record control
3. Setup:
   a. apt install nginx python3 python3-pip certbot
   b. certbot --nginx -d yourdomain.com
   c. Copy server/ files
   d. pip install -r requirements.txt
   e. Copy nginx.conf.template → /etc/nginx/sites-available/bdnet
   f. Set up systemd services for each server process
   g. iptables -t nat -A POSTROUTING -j MASQUERADE
   h. echo 1 > /proc/sys/net/ipv4/ip_forward
   i. For DNS tunnel: point NS records to VPS IP
4. Start all services:
   systemctl start bdnet-api bdnet-dns bdnet-ws bdnet-ssh bdnet-vless
```

### Systemd Service Template

```ini
# /etc/systemd/system/bdnet-dns.service
[Unit]
Description=BDNET DNS Tunnel Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/bdnet/server
ExecStart=/usr/bin/python3 /opt/bdnet/server/dns_server.py
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

---

## 13. Security Hardening

| Item | Implementation |
|---|---|
| **API Authentication** | Bearer token in Authorization header. Token in server_config.json. |
| **UUID Validation** | VLESS and WS relay check UUID against whitelist. |
| **Rate Limiting** | Max DNS queries per second per IP. Max WS connections per IP. |
| **IP Banning** | Auto-ban IPs exceeding rate limits for configurable duration. |
| **No Traffic Logging** | Proxied data bytes not logged. Only connection events. |
| **Camouflage Page** | Root URL serves normal-looking HTML to avoid detection. |
| **TLS 1.3 Only** | Nginx configured to reject TLS 1.0/1.1. |
| **Fail-Safe Shutdown** | All services catch SIGTERM and close connections cleanly. |
| **Config Reload Without Restart** | Flask API reloads server_config.json without restarting Nginx. |

---

## 14. Monitoring and Health

### Health Check Endpoint
```
GET /health
Response: {"status": "ok", "uptime": 3600, "version": "0.1"}
```
Used by:
- Render.com (to keep service alive and detect failures)
- Client app (to verify server is reachable before connecting tunnel)
- Client app scheduled ping every 30s to prevent Render cold starts

### Status Endpoint
```
GET /api/status   (Authorization: Bearer TOKEN)
Response:
{
  "uptime": 3600,
  "services": {
    "dns_tunnel": {"running": true, "sessions": 2, "bytes_rx": 1024000},
    "ws_relay":   {"running": true, "connections": 5, "bytes_tx": 5120000},
    "ssh_bridge": {"running": true, "connections": 1},
    "vless":      {"running": false},
    "http_proxy": {"running": true, "connections": 3}
  },
  "system": {
    "cpu_percent": 12.5,
    "mem_mb": 48,
    "net_rx_mb": 100,
    "net_tx_mb": 200
  }
}
```

---

## 15. MTN Cameroon — Server-Side Strategy

### DNS Tunnel NS Setup
```
Required DNS records at your registrar:
  ns1.yourdomain.com   A      YOUR_VPS_IP
  t.yourdomain.com     NS     ns1.yourdomain.com

Then your server's dns_server.py listens on UDP 53.
When MTN client sends DNS query for *.t.yourdomain.com,
the query routes through MTN resolver → internet → your VPS UDP 53.
Your server responds with encoded data in TXT records.
```

### SNI Bug Host — Server Requirements
For SNI injection to work with a CDN (Cloudflare):
```
1. Add yourdomain.com to Cloudflare
2. Enable "Proxied" mode (orange cloud)
3. The Cloudflare IP will be the visible connection endpoint
4. MTN sees Cloudflare IP + bug_host SNI → allows (if bug_host is zero-rated)
5. Cloudflare routes to your origin server
6. This is called "CDN fronting" (domain fronting via CDN)

Note: Some CDNs block this. Cloudflare's free plan generally allows it.
      The bug_host must be a domain that Cloudflare also serves (any .cloudflare.com
      or any site proxied by CF) — or simply any zero-rated host that resolves to CF IP.
```

---

## 16. Server File-by-File Build Order

Build in this order (incrementally testable):

1. `server_config.json` — define all config fields
2. `server.py` — Flask shell + health endpoint + auth middleware
3. `http_proxy.py` — simplest service (test end-to-end first)
4. `ws_relay.py` — WebSocket relay with multiplexing
5. `ssh_ws_bridge.py` — SSH bridge (test with ws_relay working)
6. `vless_server.py` — VLESS inbound parser
7. `dns_server.py` — DNS authoritative server (most complex)
8. `nginx.conf.template` — Nginx config (after TLS cert obtained)
9. Systemd service files — after all services working
