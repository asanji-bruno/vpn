# BDNET TUNNEL — Client App Architecture Plan (README_APP)

> **Platform**: Python Flask app, rendered in browser (Firebase Studio / any browser).
> No Android native layer. Pure web UI + Python backend running locally.
> Terminal-style interface. All settings editable. No hardcoded values.

---

## 1. App Responsibilities

The client app is responsible for:
- **UI Panel**: Let user pick tunneling method, edit all parameters, start/stop connections.
- **Terminal Feed**: Stream all protocol-level logs in real-time to browser.
- **Core Engine**: Manage and run tunnel modules as Python subprocesses/threads.
- **Config Management**: Read/write `config.json` live. No hardcoded values.
- **Local SOCKS5 Proxy**: Optionally expose a local SOCKS5 proxy port so the device can route traffic through the active tunnel.

---

## 2. App Architecture Layers

```
Browser (Firebase Studio or any browser)
    │
    │   HTTP GET / POST  +  WebSocket (SocketIO)
    │
    ▼
Flask App (app.py — port 5000)
    │
    ├── Route: GET  /              → renders index.html (main panel)
    ├── Route: GET  /config        → renders config.html (settings editor)
    ├── Route: POST /api/start     → starts selected tunnel method
    ├── Route: POST /api/stop      → stops active tunnel
    ├── Route: POST /api/config    → saves config.json update
    ├── Route: GET  /api/config    → returns current config.json as JSON
    ├── Route: GET  /api/status    → returns engine status
    │
    ├── SocketIO Event: "log"      → emits terminal log lines to browser
    ├── SocketIO Event: "status"   → emits connection state changes
    │
    ▼
Core Engine (engine.py)
    │
    ├── Reads config.json
    ├── Spawns selected tunnel module as thread or subprocess
    ├── Captures stdout/stderr of tunnel process
    ├── Emits each log line via SocketIO to browser
    └── Handles start / stop / restart of modules
    │
    ├── dns_tunnel.py    → DNS Tunnel Module
    ├── ws_tunnel.py     → WebSocket TLS Tunnel Module
    ├── ssh_ws.py        → SSH-over-WebSocket Module
    ├── sni_inject.py    → SNI Injection Module
    ├── vless_client.py  → VLESS Client Module
    └── http_connect.py  → HTTP CONNECT Proxy Module
```

---

## 3. Config File — config.json (ALL Editable, No Hardcoding)

```json
{
  "server": {
    "url": "https://your-server.onrender.com",
    "ip": "",
    "port": 443,
    "domain": "tunnel.yourdomain.com",
    "ns_domain": "ns1.yourdomain.com"
  },
  "auth": {
    "uuid": "YOUR-UUID-HERE",
    "ssh_user": "root",
    "ssh_password": "",
    "ssh_key_path": "",
    "secret_token": ""
  },
  "method": {
    "active": "ws_tls",
    "available": ["dns_tunnel", "ws_tls", "ssh_ws", "sni_inject", "vless_ws", "http_connect"]
  },
  "dns": {
    "upstream_dns": "8.8.8.8",
    "upstream_port": 53,
    "tunnel_domain": "t.yourdomain.com",
    "encode": "base32",
    "record_type": "TXT",
    "poll_interval_ms": 200,
    "chunk_size": 180,
    "max_retries": 3
  },
  "websocket": {
    "path": "/ws",
    "host_header": "",
    "ping_interval": 20,
    "reconnect_delay": 5
  },
  "ssh": {
    "target_host": "127.0.0.1",
    "target_port": 22,
    "local_socks_port": 1080,
    "compression": true
  },
  "sni": {
    "bug_host": "mtn.cm",
    "real_server_ip": "",
    "cdn_mode": false,
    "inject_headers": "GET / HTTP/1.1\r\nHost: [bug_host]\r\nUpgrade: websocket\r\n\r\n"
  },
  "vless": {
    "uuid": "YOUR-UUID-HERE",
    "path": "/vless",
    "flow": "",
    "encryption": "none",
    "network": "ws"
  },
  "http_connect": {
    "proxy_host": "",
    "proxy_port": 3128,
    "target_host": "",
    "target_port": 443
  },
  "local_proxy": {
    "enabled": true,
    "socks5_port": 1080,
    "http_port": 8080
  },
  "logging": {
    "level": "DEBUG",
    "show_hex": false,
    "show_base64": false,
    "max_terminal_lines": 1000
  },
  "ui": {
    "theme": "dark_terminal",
    "auto_scroll": true,
    "timestamps": true
  }
}
```

---

## 4. UI — Terminal Panel (index.html)

Design: **Dark terminal aesthetic**. Monospace font. Green text on black. Functional layout.

```
╔══════════════════════════════════════════════════════════════════╗
║  BDNET TUNNEL v0.1 — Engineering Test Shell                      ║
╠══════════════════════════════════════════════════════════════════╣
║  METHOD: [dns_tunnel ▼]  SERVER: [https://your.server.com  ]    ║
║  [▶ CONNECT]  [■ STOP]  [↺ RESTART]  [⚙ CONFIG]               ║
╠══════════════════════════════════════════════════════════════════╣
║  STATUS: ● DISCONNECTED   LOCAL SOCKS5: 127.0.0.1:1080          ║
╠══════════════════════════════════════════════════════════════════╣
║  TERMINAL LOG                                          [CLEAR]   ║
║                                                                  ║
║  [2026-09-11 14:01:00] [DNS] Initializing tunnel module          ║
║  [2026-09-11 14:01:01] [DNS] Resolving ns1.yourdomain.com        ║
║  [2026-09-11 14:01:01] [DNS] Sending query: [chunk1].t.dom.com   ║
║  [2026-09-11 14:01:01] [DNS] Response: TXT "b64responsedata..."  ║
║  [2026-09-11 14:01:02] [DNS] Tunnel established. RTT: 340ms      ║
║  [2026-09-11 14:01:02] [PROXY] Local SOCKS5 listening :1080      ║
║                                                                  ║
║  > _                                                             ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
```

**Elements:**
- Method dropdown — switches active tunnel module
- Server URL field — live-editable, saves to config.json on change
- CONNECT / STOP / RESTART buttons — plain HTML, no fancy styling
- Status indicator — colored dot + text (DISCONNECTED / CONNECTING / CONNECTED / ERROR)
- Local proxy address display (SOCKS5 port)
- Terminal log — scrolling `<pre>` or `<div>`, receives lines via SocketIO
- CLEAR button — clears terminal display only (not logs)

---

## 5. Config Editor Page (config.html)

A full-page JSON editor for `config.json`. Sections:

```
╔══════════════════════════════════════════════════════════════════╗
║  CONFIG EDITOR — config.json                                     ║
╠══════════════════════════════════════════════════════════════════╣
║  ┌─ SERVER ──────────────────────────────────────────────────┐  ║
║  │ URL:         [https://your.server.com                    ]│  ║
║  │ IP:          [                                           ]│  ║
║  │ Port:        [443                                        ]│  ║
║  │ NS Domain:   [ns1.yourdomain.com                        ]│  ║
║  └──────────────────────────────────────────────────────────┘  ║
║  ┌─ DNS TUNNEL ──────────────────────────────────────────────┐  ║
║  │ Upstream DNS: [8.8.8.8]  Port: [53]                       │  ║
║  │ Tunnel Domain: [t.yourdomain.com                         ]│  ║
║  │ Encode: [base32 ▼]  Record: [TXT ▼]  Chunk: [180        ]│  ║
║  │ Poll ms: [200]  Retries: [3]                              │  ║
║  └──────────────────────────────────────────────────────────┘  ║
║  ┌─ SNI INJECTION ───────────────────────────────────────────┐  ║
║  │ Bug Host: [mtn.cm                                        ]│  ║
║  │ Real Server IP: [                                        ]│  ║
║  │ CDN Mode: [OFF ▼]                                         │  ║
║  │ Custom Headers: [multiline textarea                      ]│  ║
║  └──────────────────────────────────────────────────────────┘  ║
║  ... (one section per method) ...                               ║
║                                                                  ║
║  [SAVE CONFIG]  [RELOAD FROM FILE]  [RESET DEFAULTS]           ║
╚══════════════════════════════════════════════════════════════════╝
```

**Every config field is an editable input.** SAVE button hits `/api/config` POST and writes to `config.json`.

---

## 6. Core Engine — engine.py

The engine manages tunnel process lifecycle.

```python
# engine.py — pseudocode structure

class TunnelEngine:
    active_method: str = None
    process: subprocess.Popen = None
    socketio: SocketIO = None

    def start(self, method: str, config: dict):
        # 1. Load correct module
        # 2. Spawn as thread (or subprocess for isolation)
        # 3. Pipe stdout/stderr to log_callback
        # 4. Update status

    def stop(self):
        # 1. Send SIGTERM to subprocess / set thread stop event
        # 2. Wait for clean shutdown
        # 3. Update status

    def log_callback(self, line: str):
        # Emits line to browser via SocketIO
        socketio.emit("log", {"msg": line, "ts": timestamp()})
```

---

## 7. Tunnel Modules — Technical Spec

### Module 1: dns_tunnel.py — DNS Tunnel Client

```
PURPOSE: Encode/send data as DNS queries. Receive data from DNS responses.

INPUTS (from config.json):
  - upstream_dns: IP of DNS server to send queries to
  - tunnel_domain: base domain (e.g., t.yourdomain.com)
  - encode: "base32" (DNS-safe) or "base64url"
  - record_type: TXT, NULL, SRV
  - chunk_size: max bytes per DNS label (max 63 chars per label)
  - poll_interval_ms: how often to poll for response

FLOW:
  1. Accept raw TCP data from local SOCKS5 proxy
  2. Fragment into chunks of chunk_size bytes
  3. Encode each chunk as base32
  4. Send DNS query: [seq_id]-[chunk_b32].t.yourdomain.com
  5. Wait for DNS TXT response
  6. Decode TXT record → reconstruct original response
  7. Deliver to local SOCKS5 client

KEY PYTHON LIBS: dnslib, socket (UDP), threading
KEY CHALLENGE: DNS round-trip latency (300–800ms per query on MTN)
ANTI-DETECTION: Use realistic TTLs, random query IDs, jitter between queries
```

### Module 2: ws_tunnel.py — WebSocket TLS Tunnel

```
PURPOSE: Establish a WebSocket connection to server over TLS/HTTPS.
         Use it as a raw byte stream (like a TCP socket).

INPUTS:
  - server url (wss://your.server.com/ws)
  - path: /ws
  - host_header: optional override
  - ping_interval: keepalive

FLOW:
  1. Connect to wss://server/path using websockets lib
  2. TLS handshake → appears as normal browser WebSocket to ISP
  3. Start local SOCKS5 server on socks5_port
  4. For each SOCKS5 client connection:
     a. Read destination from SOCKS5 CONNECT request
     b. Send "OPEN:host:port" message to server via WS
     c. Relay bytes bidirectionally (local socket ↔ WS)

KEY PYTHON LIBS: websockets (asyncio), asyncio, python-socks
KEY CHALLENGE: Handling multiple concurrent TCP streams over one WS connection (multiplexing)
SOLUTION: Use simple framing: [4-byte stream_id][4-byte length][data]
```

### Module 3: ssh_ws.py — SSH over WebSocket

```
PURPOSE: Wrap SSH session inside WebSocket HTTP upgrade.
         Hides SSH inside standard HTTP traffic.

INPUTS:
  - server ws relay URL (ws://server/ssh-relay)
  - ssh_user, ssh_password or ssh_key_path
  - target_host, target_port (usually 127.0.0.1:22 on server)
  - local_socks_port

FLOW:
  1. Connect to server WebSocket relay (ws://server/ssh-relay)
  2. Server relay bridges WS connection to local SSH port 22
  3. Client-side: paramiko.Transport(ws_socket)
  4. SSH handshake over WebSocket socket
  5. Open SSH dynamic port forwarding channel (SOCKS5 proxy)
  6. Local SOCKS5 available on local_socks_port

KEY PYTHON LIBS: paramiko, websockets, threading
KEY CHALLENGE: Making paramiko's Transport work over a WebSocket socket object
SOLUTION: Wrap WS connection in a socket-like adapter class
```

### Module 4: sni_inject.py — SNI Host Injection

```
PURPOSE: Spoof TLS SNI field to use MTN zero-rated/bug host domain.
         Real traffic tunnels inside the "allowed" TLS connection.

INPUTS:
  - bug_host: zero-rated domain (e.g., "mtn.cm", "health.mtn.cm")
  - real_server_ip: actual VPS IP address
  - cdn_mode: if True, server is behind Cloudflare (SNI = Cloudflare IP)
  - inject_headers: custom HTTP headers for payload injection

FLOW:
  1. Open raw TCP socket to real_server_ip:443
  2. Initiate TLS handshake with SNI = bug_host (NOT real server domain)
  3. ISP firewall sees SNI = mtn.cm → thinks it's allowed traffic → passes
  4. Server receives TLS connection → decodes real client via pre-shared key
  5. Tunnel established; relay traffic normally

KEY PYTHON LIBS: ssl (with server_hostname override), socket
KEY CHALLENGE: Python ssl module uses SNI automatically. Must override:
    context.check_hostname = False
    ssl.wrap_socket(sock, server_hostname=bug_host, ...)
NOTE: Server must have a TLS cert that accepts connections for bug_host SNI
      (via Cloudflare CDN fronting: the CDN's cert covers all domains)
```

### Module 5: vless_client.py — VLESS Protocol Client

```
PURPOSE: Implement VLESS protocol client for connecting to Xray-style servers.

VLESS PROTOCOL (simplified):
  Version:  1 byte (0x00)
  UUID:     16 bytes (client identifier)
  Addon:    1 byte (additional data length)
  Command:  1 byte (0x01=TCP, 0x02=UDP, 0x03=MUX)
  Port:     2 bytes (big-endian)
  AddrType: 1 byte (0x01=IPv4, 0x02=Domain, 0x03=IPv6)
  Addr:     variable
  Data:     raw proxied data

INPUTS:
  - server url, port, uuid, ws_path, network (ws/tcp/grpc)

FLOW:
  1. Connect to server WebSocket (wss://server/path)
  2. Send VLESS header (UUID + target destination)
  3. Server authenticates UUID → opens connection to target
  4. Relay raw TCP data bidirectionally

KEY PYTHON LIBS: websockets, uuid, struct (for binary packing)
ENCRYPTION: None in VLESS itself; relies on TLS of WebSocket transport
```

### Module 6: http_connect.py — HTTP CONNECT Proxy

```
PURPOSE: Use HTTP CONNECT method to establish raw TCP tunnel through proxy.

FLOW:
  1. Connect TCP to proxy_host:proxy_port
  2. Send: CONNECT target_host:target_port HTTP/1.1\r\nHost: target_host\r\n\r\n
  3. Read: HTTP/1.1 200 Connection Established
  4. Proxy now acts as transparent TCP relay
  5. Wrap in local SOCKS5

NOTES: Simplest method. No encryption unless target uses TLS. 
       Useful for testing proxy chain before adding encryption layers.
```

---

## 8. Local SOCKS5 Proxy Layer

All methods expose a local SOCKS5 proxy (default port 1080) so the device can route
any application traffic through the active tunnel.

```
App running on device
    │
    │  (browser/app configured to use SOCKS5 127.0.0.1:1080)
    ▼
Local SOCKS5 Server (app/core/socks5_server.py)
    │
    │  CONNECT request for google.com:443
    ▼
Active Tunnel Module
    │
    │  Sends via active method (DNS / WS / SSH / VLESS)
    ▼
Remote Server → google.com:443
```

---

## 9. Flask Routes — Full API Spec

| Route | Method | Body | Response | Description |
|---|---|---|---|---|
| `/` | GET | — | HTML | Main panel |
| `/config` | GET | — | HTML | Config editor |
| `/api/config` | GET | — | JSON | Current config |
| `/api/config` | POST | JSON patch | `{ok: true}` | Update + save config |
| `/api/start` | POST | `{method: "dns_tunnel"}` | `{ok: true}` | Start tunnel method |
| `/api/stop` | POST | — | `{ok: true}` | Stop active tunnel |
| `/api/restart` | POST | — | `{ok: true}` | Stop + start |
| `/api/status` | GET | — | `{method, state, uptime, bytes_in, bytes_out}` | Current status |
| `/api/methods` | GET | — | JSON list | Available tunnel methods |
| `/api/logs` | GET | — | JSON array | Recent log lines |

**SocketIO Events (server → client):**

| Event | Payload | Description |
|---|---|---|
| `log` | `{ts, level, module, msg}` | Single log line to terminal |
| `status` | `{state, method, uptime}` | State change notification |
| `bytes` | `{in, out, total}` | Traffic counter update |
| `error` | `{code, msg}` | Error notification |

---

## 10. Python Dependencies (requirements.txt — App)

```
flask==3.0.3
flask-socketio==5.3.6
eventlet==0.36.1
websockets==12.0
paramiko==3.4.0
dnslib==0.9.24
python-socks==2.4.4
cryptography==42.0.8
requests==2.32.3
```

---

## 11. File-by-File Build Order

Build in this order to test incrementally:

1. `config.json` — define all config fields first
2. `app.py` — basic Flask + SocketIO shell
3. `core/engine.py` — process manager + log streaming
4. `templates/index.html` + `static/` — terminal UI
5. `templates/config.html` — config editor
6. `core/http_connect.py` — simplest method (test server connectivity)
7. `core/ws_tunnel.py` — WebSocket method (most important)
8. `core/ssh_ws.py` — SSH over WS
9. `core/dns_tunnel.py` — DNS method (most complex)
10. `core/sni_inject.py` — SNI method (needs MTN testing)
11. `core/vless_client.py` — VLESS (most advanced)

---

## 12. Encryption Details Per Module

| Module | Transport Encryption | Auth Method | Anti-Replay |
|---|---|---|---|
| dns_tunnel | None (DNS plaintext) — add SSH inside | Shared secret in subdomain prefix | Sequence number in payload |
| ws_tls | TLS 1.3 (WebSocket Secure) | UUID token in WS header | TLS record layer |
| ssh_ws | SSH-level encryption (AES/ChaCha20) | SSH keypair or password | SSH sequence numbers |
| sni_inject | TLS 1.3 (via CDN/Cloudflare) | Pre-shared key in HTTP header | TLS |
| vless_ws | TLS 1.3 (WebSocket) | UUID (128-bit random) | TLS |
| http_connect | None unless TLS to destination | None (open proxy) | None |

---

## 13. Logging Format (Terminal Output)

All log lines follow this format:

```
[TIMESTAMP] [LEVEL] [MODULE] message
```

Examples:
```
[14:01:00.123] [INFO ] [DNS   ] Initialized. Domain: t.tunnel.example.com
[14:01:00.456] [DEBUG] [DNS   ] TX Query: 001-R2LUGY3UNVPWYZLOOQXC.t.example.com
[14:01:00.789] [DEBUG] [DNS   ] RX TXT:   "b64:SGVsbG8gV29ybGQ="  RTT: 287ms
[14:01:01.000] [INFO ] [WS    ] Connecting to wss://server.com/ws
[14:01:01.100] [INFO ] [WS    ] TLS handshake OK. Cipher: TLS_AES_256_GCM_SHA384
[14:01:01.200] [INFO ] [SOCKS ] Local proxy listening on 127.0.0.1:1080
[14:01:02.000] [INFO ] [SOCKS ] New connection: google.com:443
[14:01:02.050] [DEBUG] [WS    ] TX Frame: OPEN:google.com:443 (12 bytes)
[14:01:02.200] [DEBUG] [WS    ] RX Frame: 1024 bytes from google.com:443
```

---

## 14. Limitations and Known Issues

| Issue | Details | Mitigation |
|---|---|---|
| DNS tunnel speed | 5–15 KB/s max on MTN | Use only for initial connection, upgrade to WS |
| DNS chunking | Max 63 chars per label, 253 total QNAME | chunk_size ≤ 180, split into multiple labels |
| WebSocket multiplexing | One WS connection per stream | Implement stream ID multiplexer |
| SNI bug host | Changes when MTN patches the zero-rating | Make bug_host fully user-configurable |
| Render.com cold start | 15s delay after inactivity | Add health ping route on server |
| Firebase Studio browser | May not support raw socket APIs | All tunneling done in Python subprocess, not browser |
