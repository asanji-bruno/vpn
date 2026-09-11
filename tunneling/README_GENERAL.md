# BDNET TUNNEL — General Architecture & Project Overview

> **Purpose**: Personal engineering test project. Full-stack tunneling system built from scratch
> using Python (Flask) as the web/app layer, with a modular multi-protocol proxy engine on the
> server side. Goal: understand and implement the same mechanisms used by commercial tunneling
> apps (HTTP Injector, SlowDNS, Psiphon, etc.) at a technical, educational level.

---

## 1. High-Level System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        CLIENT SIDE (App)                             │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │        Flask Web App  (runs locally in Firebase Studio)     │    │
│  │                                                             │    │
│  │  ┌─────────────┐  ┌───────────────┐  ┌───────────────┐    │    │
│  │  │ Config Panel │  │ Terminal Feed │  │ Method Picker │    │    │
│  │  │ (server URL, │  │ (live logs    │  │ (DNS/WS/SSH/  │    │    │
│  │  │  UUID, port, │  │  via SocketIO)│  │  SNI/VLESS)   │    │    │
│  │  │  SNI host..) │  └───────────────┘  └───────────────┘    │    │
│  │  └─────────────┘                                            │    │
│  │            │                                                │    │
│  │            ▼                                                │    │
│  │  ┌─────────────────────────────────────────────────────┐   │    │
│  │  │          Core Proxy Engine  (Python subprocess)     │   │    │
│  │  │                                                     │   │    │
│  │  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │   │    │
│  │  │  │DNS Tunnel│  │ WS/SSH   │  │  VLESS / Trojan  │  │   │    │
│  │  │  │ Module   │  │ Module   │  │     Module       │  │   │    │
│  │  │  └──────────┘  └──────────┘  └──────────────────┘  │   │    │
│  │  └─────────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│               │                                                      │
│        Tunnel Connection (varies by method)                          │
└──────────────────────────────────────────────────────────────────────┘
               │
               │  ◄── MTN Cameroon Restrictive Network ──►
               │
┌──────────────────────────────────────────────────────────────────────┐
│                      SERVER SIDE (VPS / Domain)                      │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                   bdnet.yourdomain.com                      │    │
│  │                                                             │    │
│  │  ┌────────────┐  ┌────────────┐  ┌──────────────────────┐  │    │
│  │  │Nginx/Caddy │  │ DNS Server │  │  SSH Server          │  │    │
│  │  │(TLS, WSS,  │  │(dnslib,    │  │  (Paramiko/OpenSSH   │  │    │
│  │  │ CDN front) │  │ port 53)   │  │   port 22 / 443)     │  │    │
│  │  └────────────┘  └────────────┘  └──────────────────────┘  │    │
│  │         │                                                   │    │
│  │         ▼                                                   │    │
│  │  ┌─────────────────────────────────────────────────────┐   │    │
│  │  │         Flask Management API (Python)               │   │    │
│  │  │  - Config endpoints (read/write server_config.json) │   │    │
│  │  │  - Proxy engine process manager                     │   │    │
│  │  │  - Health check + status push via WebSocket         │   │    │
│  │  └─────────────────────────────────────────────────────┘   │    │
│  │         │                                                   │    │
│  │         ▼                                                   │    │
│  │  ┌─────────────────────────────────────────────────────┐   │    │
│  │  │            NAT / IP Forwarding Layer                │   │    │
│  │  │       (iptables / nftables  →  Internet)            │   │    │
│  │  └─────────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
               │
               ▼
          Open Internet
```

---

## 2. Tunneling Methods — Overview Table

| # | Method | Port | Protocol Layer | MTN Use Case | Speed |
|---|---|---|---|---|---|
| 1 | **DNS Tunnel** | UDP 53 | DNS → TCP/IP | Zero balance / captive portal | Very slow |
| 2 | **WebSocket TLS** | TCP 443 | WS over HTTPS | DPI bypass, looks like browser | Fast |
| 3 | **SSH over WebSocket** | TCP 80/443 | SSH inside WS | Port 22 blocked | Medium |
| 4 | **SNI Injection** | TCP 443 | TLS SNI spoof | Zero-rating bug host exploit | Fast |
| 5 | **VLESS + WS** | TCP 443 | VLESS protocol | Full VPN replacement | Very fast |
| 6 | **HTTP CONNECT** | TCP 80/3128 | Raw TCP through HTTP | Basic proxy chain test | Medium |

---

## 3. Overall Data Flow — DNS Tunnel Example

```
[App Panel] — User sets DNS NS domain and server IP
      │
      ▼
[Flask App] — starts DNS Tunnel Module as background process
      │
      ▼
[DNS Tunnel Module]
  1. Takes outbound TCP data (HTTP GET, etc.)
  2. Base64-encodes it into chunks of ≤200 chars
  3. Formats: [b64chunk].t.yourdomain.com
  4. Sends UDP DNS query to 8.8.8.8 (or MTN DNS, port 53)
      │
      ▼ (passes through MTN network — DNS always allowed)
      │
[Your VPS — Authoritative NS for tunnel.yourdomain.com]
  1. Receives DNS query
  2. Strips subdomain prefix, decodes Base64
  3. Reconstructs original TCP packet
  4. Forwards to real destination (google.com, etc.)
  5. Encodes response into TXT DNS records
  6. Returns DNS answer
      │
      ▼
[DNS Tunnel Module] — reassembles response → delivers to local socket
      │
      ▼
[Browser / App] — receives internet data through tunnel
```

---

## 4. Project Folder Structure

```
tunneling/
│
├── README_GENERAL.md           ← This file
├── README_APP.md               ← Client app detailed plan
├── README_SERVER.md            ← Server detailed plan
│
├── app/                        ← Flask Web App (Firebase Studio)
│   ├── app.py                  ← Main Flask entry + SocketIO
│   ├── config.json             ← ALL user-editable settings
│   ├── requirements.txt
│   │
│   ├── core/                   ← Core proxy engine (Python modules)
│   │   ├── engine.py           ← Process manager (start/stop/switch methods)
│   │   ├── dns_tunnel.py       ← Method 1: DNS tunnel client
│   │   ├── ws_tunnel.py        ← Method 2: WebSocket TLS tunnel
│   │   ├── ssh_ws.py           ← Method 3: SSH-over-WebSocket
│   │   ├── sni_inject.py       ← Method 4: SNI injection client
│   │   ├── vless_client.py     ← Method 5: VLESS protocol client
│   │   └── http_connect.py     ← Method 6: HTTP CONNECT proxy
│   │
│   ├── templates/
│   │   ├── index.html          ← Main terminal + method panel
│   │   └── config.html         ← Live config editor
│   │
│   └── static/
│       ├── main.js             ← SocketIO client + terminal rendering
│       └── style.css           ← Dark terminal-style CSS
│
├── server/                     ← Server code (deploy to VPS/Render)
│   ├── server.py               ← Main Flask management API
│   ├── dns_server.py           ← Authoritative DNS tunnel server (dnslib)
│   ├── ws_relay.py             ← WebSocket relay (asyncio)
│   ├── ssh_ws_bridge.py        ← SSH-over-WebSocket bridge
│   ├── vless_server.py         ← VLESS inbound server
│   ├── http_proxy.py           ← HTTP CONNECT proxy
│   ├── requirements.txt
│   └── config/
│       ├── server_config.json  ← All server settings (editable)
│       └── nginx.conf.template ← Nginx config template
│
└── BDNET-Reverse/              ← Existing reverse-engineering work
    ├── decrypt.py
    └── JSONDEC2025.txt
```

---

## 5. Technology Stack

| Layer | Technology | Reason |
|---|---|---|
| App UI | HTML + Vanilla CSS + JS | Runs in any browser, Firebase Studio compatible |
| App Backend | Python 3.x Flask | Lightweight, easy subprocess/thread management |
| Real-time Terminal | Flask-SocketIO | Live log streaming to browser via WebSocket |
| Process Manager | Python `subprocess`, `threading` | Run/kill tunnel modules as controlled processes |
| DNS Tunnel | `dnslib`, `socket` (raw UDP) | Best Python lib for DNS packet construction |
| WebSocket Tunnel | `websockets` / `aiohttp` | Async WS client for method 2 & 3 |
| SSH Tunnel | `paramiko` | SSH handshake and port forwarding in Python |
| VLESS Protocol | Custom Python parser (UUID, AEAD) | Educational, built from spec |
| Server Proxy | Nginx + Let's Encrypt (Certbot) | TLS termination, CDN compatibility |
| Config Storage | JSON files — fully user-editable | No database, simple, transparent |
| Encryption | TLS 1.3 (Nginx), AES-256-GCM (app) | Industry standard |

---

## 6. Design Principles (Your Requirements)

1. **No hardcoded values** — Every parameter lives in `config.json`. The panel edits it live.
2. **Terminal-first UI** — Dark terminal feed is the main display. Buttons are plain, functional.
3. **Modular methods** — Each tunnel type is a separate Python class, hot-swappable.
4. **Flask in Firebase Studio** — Runs on `flask run` (port 5000), open in Studio browser. No mobile SDK.
5. **Server-agnostic** — There is a text input for server URL in the panel. Render, ngrok, custom domain — all work.
6. **Educational logging** — Every protocol step (DNS query sent, WS frame received, TLS handshake, etc.) is logged to the terminal so you can trace each packet.

---

## 7. MTN Cameroon Network — Research Notes

| Property | Detail |
|---|---|
| **UDP 53** | Always open — primary attack surface. DNS queries pass even with zero balance. |
| **TCP 80** | Open but often hijacked by captive portal (redirected to payment page). |
| **TCP 443** | Open. DPI inspects SNI field in TLS ClientHello. |
| **Port 22 (SSH)** | Blocked by MTN DPI when no active data bundle in most cases. |
| **ICMP** | Blocked when no balance. |
| **Zero-rated domains** | MTN CM historically zero-rates: `mtn.cm`, health.mtn.cm, WhatsApp IP ranges, Facebook Free Basics. |
| **Bug host approach** | Spoof TLS SNI to match a zero-rated domain. ISP firewall sees whitelisted SNI → passes traffic. Actual data tunnels inside. |
| **DPI level** | Moderate. Pattern matching on SNI. No deep TLS decryption (would break HTTPS for everyone). |
| **Primary DNS** | Routes through ISP resolver, then to 8.8.8.8. ISP resolver itself passes UDP 53. |

**Recommended attack sequence for MTN with zero balance:**
1. Try DNS tunnel (UDP 53 is free) — slow but works
2. If TCP 443 open with zero balance, try SNI injection with MTN bug host
3. If any TCP works, upgrade to WebSocket TLS (fastest)

---

## 8. Security Architecture

```
Client ←──── TLS 1.3 ────→ Server
              │
              ├── Certificate: Let's Encrypt (auto-renew via Certbot/Caddy)
              ├── Auth: UUID token per session (VLESS-style) or SSH keypair
              ├── DNS anti-replay: sequence number in every DNS payload chunk
              ├── No traffic logging on server (privacy by design)
              └── Config JSON encrypted at rest (optional AES-256 layer)
```

---

## 9. Deployment Matrix

| Platform | DNS Tunnel | WS/TLS | SSH | Custom Domain | Notes |
|---|---|---|---|---|---|
| **Custom VPS (Linux)** | ✅ Full | ✅ | ✅ | ✅ | Best option, full control |
| **Render.com (free)** | ❌ No UDP 53 | ✅ Port 443 | ❌ | ✅ Via render URL | Good for WS/VLESS methods only |
| **ngrok (local VPS)** | ❌ No UDP 53 | ✅ | ❌ | ✅ Temporary URL | Dev/test only |
| **Cloudflare Tunnel** | ❌ | ✅ Excellent | ❌ | ✅ | Best CDN fronting for SNI method |

> **For DNS tunneling, you must have a real Linux VPS with a public IP. You must point NS records
> of a subdomain to that VPS IP. Render and ngrok cannot do this.**

---

## 10. Open Questions to Resolve Before Build

- [ ] **Domain name**: Do you have one? Which registrar? (Needed for DNS tunnel NS records)
- [ ] **VPS**: Do you have one, or are we using Render/ngrok only for now?
- [ ] **MTN scenario**: Zero balance bypass? Captive portal skip? Speed throttle bypass?
- [ ] **Firebase Studio**: Is this an Android browser environment or desktop browser?
- [ ] **Existing decrypt.py**: Should this integrate with the tunnel app? What does it decrypt?
