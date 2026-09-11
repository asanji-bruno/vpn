"""
BDNET Server — DNS Tunnel Authoritative Server (Method 1)
Binds UDP port 53, acts as the authoritative NS for your tunnel subdomain.

HOW IT WORKS:
  Client encodes data as Base32 subdomains: [seq]-[sessionid]-[b32data].t.yourdomain.com
  This server decodes the subdomain, reconstructs the TCP stream, forwards to destination,
  encodes the response in TXT records, and returns them as DNS answers.

DNS SETUP REQUIRED (at your registrar):
  ns1.yourdomain.com   A      YOUR_VPS_IP
  t.yourdomain.com     NS     ns1.yourdomain.com

REQUIRES: root / admin for port 53 binding
REQUIRES: pip install dnslib
"""

import base64
import json
import logging
import os
import queue
import select
import socket
import struct
import threading
import time

import dnslib
from dnslib import DNSRecord, DNSHeader, RR, QTYPE, TXT, A, DNSQuestion

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config", "server_config.json")

def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)

log = logging.getLogger("dns_tunnel")
logging.basicConfig(level=logging.DEBUG,
    format="[%(asctime)s] [%(levelname)-5s] [dns_tunnel   ] %(message)s",
    datefmt="%H:%M:%S")

MAX_TXT_LEN    = 255     # DNS TXT record max chunk size
MAX_LABEL_LEN  = 63      # DNS label max length
SESSION_TIMEOUT = 120    # seconds before idle session is cleaned up

# ─── Session ──────────────────────────────────────────────────────────────────

class TunnelSession:
    """
    One logical TCP connection tunneled through DNS.
    Multiple DNS queries/responses carry the chunks of a single stream.
    """
    def __init__(self, session_id: str, dest_host: str, dest_port: int):
        self.session_id  = session_id
        self.dest_host   = dest_host
        self.dest_port   = dest_port
        self.last_seen   = time.time()
        self.connected   = False
        self.error       = None

        # Reassembly buffers
        self.rx_chunks: dict = {}     # seq_id → bytes (client→server)
        self.rx_expected = 0

        # Outbound queue (server→client, chunked for DNS responses)
        self.tx_queue: queue.Queue = queue.Queue()

        # TCP socket to actual destination
        self.sock: socket.socket = None
        self._connect_thread = threading.Thread(
            target=self._connect_and_forward, daemon=True
        )
        self._connect_thread.start()

    def _connect_and_forward(self):
        """Open TCP to destination and continuously read responses"""
        try:
            self.sock = socket.create_connection(
                (self.dest_host, self.dest_port), timeout=10
            )
            self.connected = True
            log.info(f"[{self.session_id}] Connected to {self.dest_host}:{self.dest_port}")
        except Exception as e:
            self.error = str(e)
            log.warning(f"[{self.session_id}] Connect failed: {e}")
            return

        # Read responses from destination and enqueue as chunks
        try:
            while True:
                data = self.sock.recv(4096)
                if not data:
                    break
                # Split into TXT-safe chunks
                for i in range(0, len(data), MAX_TXT_LEN):
                    chunk = data[i:i + MAX_TXT_LEN]
                    encoded = base64.b32encode(chunk).decode("ascii").rstrip("=")
                    self.tx_queue.put(encoded)
        except Exception as e:
            log.debug(f"[{self.session_id}] Socket read: {e}")
        finally:
            self.connected = False
            log.info(f"[{self.session_id}] TCP connection closed")

    def send_to_dest(self, data: bytes):
        """Forward client data to the real destination"""
        if self.sock and self.connected:
            try:
                self.sock.sendall(data)
                self.last_seen = time.time()
            except Exception as e:
                log.warning(f"[{self.session_id}] Send error: {e}")

    def get_response_chunks(self, max_chunks=3) -> list:
        """Pull pending response chunks for inclusion in a DNS TXT response"""
        chunks = []
        for _ in range(max_chunks):
            try:
                chunks.append(self.tx_queue.get_nowait())
            except queue.Empty:
                break
        return chunks

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

# ─── DNS Server ───────────────────────────────────────────────────────────────

class DNSTunnelServer:
    def __init__(self):
        self.cfg        = load_config()
        self.dns_cfg    = self.cfg["methods"]["dns_tunnel"]
        self.domain     = self.dns_cfg["tunnel_domain"].lower().rstrip(".")
        self.running    = False
        self.sessions: dict = {}     # session_id → TunnelSession
        self.session_lock = threading.Lock()

        # Rate limiting: ip → [timestamps]
        self.rate_map: dict = {}
        self.max_qps = self.cfg["rate_limit"]["max_dns_qps"]

    def _rate_ok(self, ip: str) -> bool:
        now = time.time()
        window = self.rate_map.setdefault(ip, [])
        # keep only last 1 second of entries
        self.rate_map[ip] = [t for t in window if now - t < 1.0]
        if len(self.rate_map[ip]) >= self.max_qps:
            return False
        self.rate_map[ip].append(now)
        return True

    def _cleanup_sessions(self):
        """Periodically remove timed-out sessions"""
        while self.running:
            time.sleep(30)
            now = time.time()
            with self.session_lock:
                dead = [sid for sid, s in self.sessions.items()
                        if now - s.last_seen > SESSION_TIMEOUT]
                for sid in dead:
                    self.sessions[sid].close()
                    del self.sessions[sid]
                    log.info(f"Session expired: {sid}")

    def handle_query(self, raw_data: bytes, client_addr: tuple) -> bytes:
        """
        Parse incoming DNS query, decode tunnel payload, return DNS answer.
        Query QNAME format: [seq]-[sid]-[b32data]-[desthost]-[destport].[tunnel_domain]
        e.g.: 001-a3f9-MFRA-google-443.t.example.com
        """
        try:
            request = DNSRecord.parse(raw_data)
        except Exception as e:
            log.debug(f"DNS parse error from {client_addr}: {e}")
            return b""

        qname = str(request.q.qname).lower().rstrip(".")
        qtype = request.q.qtype

        # Must be for our tunnel domain
        if not qname.endswith("." + self.domain) and qname != self.domain:
            # Not our domain — return NXDOMAIN
            reply = request.reply()
            reply.header.rcode = dnslib.RCODE.NXDOMAIN
            return reply.pack()

        # Strip the tunnel domain suffix
        prefix = qname[: -(len(self.domain) + 1)]   # everything before ".t.domain.com"
        labels = prefix.split(".")
        # labels[0] = seq_id, labels[1] = session_id, labels[2] = encoded_data
        # labels[3] = dest_host, labels[4] = dest_port  (only on first packet)

        reply = request.reply()

        try:
            if len(labels) < 3:
                raise ValueError("Too few labels")

            seq_id     = int(labels[0], 16)  # hex sequence
            session_id = labels[1]
            b32_data   = labels[2].upper()   # base32 is uppercase

            # Restore padding
            pad = (8 - len(b32_data) % 8) % 8
            b32_data += "=" * pad
            chunk_data = base64.b32decode(b32_data)

            # If session doesn't exist, labels[3] and labels[4] carry dest
            with self.session_lock:
                if session_id not in self.sessions:
                    if len(labels) < 5:
                        raise ValueError("No destination in first packet")
                    dest_host = labels[3].replace("-", ".")
                    dest_port = int(labels[4])
                    session = TunnelSession(session_id, dest_host, dest_port)
                    self.sessions[session_id] = session
                    log.info(f"New session {session_id} → {dest_host}:{dest_port}")
                else:
                    session = self.sessions[session_id]

            session.last_seen = time.time()

            # Accumulate chunk and send when we have enough
            # (simple: send every chunk immediately for low-latency)
            if chunk_data:
                session.send_to_dest(chunk_data)

            # Pull any waiting response data
            response_chunks = session.get_response_chunks(max_chunks=3)

            if response_chunks:
                for chunk_str in response_chunks:
                    reply.add_answer(
                        RR(request.q.qname, QTYPE.TXT, ttl=1,
                           rdata=TXT([chunk_str.encode("ascii")]))
                    )
            else:
                # No data yet — return empty TXT so client knows to poll
                reply.add_answer(
                    RR(request.q.qname, QTYPE.TXT, ttl=1,
                       rdata=TXT([b"WAIT"]))
                )

        except Exception as e:
            log.debug(f"Tunnel decode error: {e}")
            reply.header.rcode = dnslib.RCODE.SERVFAIL

        return reply.pack()

    def start(self):
        self.cfg = load_config()
        self.dns_cfg = self.cfg["methods"]["dns_tunnel"]
        port = self.dns_cfg["port"]
        host = self.cfg["server"]["host"]

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((host, port))
        self.running = True

        log.info(f"DNS Tunnel Server listening on UDP {host}:{port}")
        log.info(f"Tunnel domain: {self.domain}")

        # Cleanup thread
        threading.Thread(target=self._cleanup_sessions, daemon=True).start()

        while self.running:
            try:
                readable, _, _ = select.select([sock], [], [], 1.0)
                if not readable:
                    continue
                raw_data, client_addr = sock.recvfrom(4096)
                ip = client_addr[0]

                if not self._rate_ok(ip):
                    log.warning(f"Rate limit exceeded: {ip}")
                    continue

                response = self.handle_query(raw_data, client_addr)
                if response:
                    sock.sendto(response, client_addr)

            except Exception as e:
                if self.running:
                    log.error(f"Server loop error: {e}")

        sock.close()
        log.info("DNS server stopped")

    def stop(self):
        self.running = False

if __name__ == "__main__":
    server = DNSTunnelServer()
    try:
        server.start()
    except KeyboardInterrupt:
        server.stop()
