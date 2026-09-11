"""
BDNET Server — HTTP CONNECT Proxy (Method 6)
A standard HTTP/1.1 CONNECT tunnel proxy.
Client sends:  CONNECT host:port HTTP/1.1
Server replies: HTTP/1.1 200 Connection Established
Then relays raw bytes bidirectionally.
"""

import json
import logging
import os
import select
import socket
import threading

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config", "server_config.json")

def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)

log = logging.getLogger("http_connect")
logging.basicConfig(level=logging.DEBUG,
    format="[%(asctime)s] [%(levelname)-5s] [http_connect ] %(message)s",
    datefmt="%H:%M:%S")

CHUNK = 8192
TIMEOUT = 30

def relay(sock_a: socket.socket, sock_b: socket.socket):
    """Relay bytes between two sockets until one closes"""
    socks = [sock_a, sock_b]
    try:
        while True:
            readable, _, exceptional = select.select(socks, [], socks, TIMEOUT)
            if exceptional or not readable:
                break
            for s in readable:
                other = sock_b if s is sock_a else sock_a
                try:
                    data = s.recv(CHUNK)
                    if not data:
                        return
                    other.sendall(data)
                except Exception:
                    return
    except Exception as e:
        log.debug(f"Relay error: {e}")
    finally:
        for s in socks:
            try:
                s.close()
            except Exception:
                pass

def handle_client(client_sock: socket.socket, client_addr: tuple, cfg: dict):
    proxy_cfg = cfg["methods"]["http_connect"]
    allowed_ports = set(proxy_cfg["allowed_ports"])

    client_sock.settimeout(TIMEOUT)

    try:
        # Read request headers
        raw = b""
        while b"\r\n\r\n" not in raw:
            chunk = client_sock.recv(4096)
            if not chunk:
                return
            raw += chunk

        first_line = raw.split(b"\r\n")[0].decode("utf-8", errors="ignore")
        parts = first_line.split()

        if len(parts) < 3 or parts[0].upper() != "CONNECT":
            # Not a CONNECT request — send 405
            client_sock.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            return

        # Parse host:port
        host_port = parts[1]
        if ":" in host_port:
            host, port_str = host_port.rsplit(":", 1)
            port = int(port_str)
        else:
            host = host_port
            port = 443

        log.debug(f"{client_addr} CONNECT → {host}:{port}")

        # Port allowlist check
        if allowed_ports and port not in allowed_ports:
            log.warning(f"Port {port} not in allowlist — rejected")
            client_sock.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n")
            return

        # Connect to target
        target_sock = socket.create_connection((host, port), timeout=10)
        target_sock.settimeout(TIMEOUT)

        # Tell client tunnel is open
        client_sock.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        log.info(f"Tunnel open: {client_addr} ↔ {host}:{port}")

        # Relay
        relay(client_sock, target_sock)
        log.info(f"Tunnel closed: {client_addr} ↔ {host}:{port}")

    except Exception as e:
        log.debug(f"Client handler error: {e}")
        try:
            client_sock.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
        except Exception:
            pass
    finally:
        try:
            client_sock.close()
        except Exception:
            pass

class HTTPConnectProxy:
    def __init__(self):
        self.running = False
        self.server_sock = None

    def start(self):
        cfg = load_config()
        proxy_cfg = cfg["methods"]["http_connect"]
        host = cfg["server"]["host"]
        port = proxy_cfg["port"]

        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((host, port))
        self.server_sock.listen(50)
        self.running = True

        log.info(f"HTTP CONNECT Proxy listening on {host}:{port}")
        log.info(f"Allowed ports: {proxy_cfg['allowed_ports']}")

        while self.running:
            try:
                client_sock, client_addr = self.server_sock.accept()
                cfg = load_config()   # reload config each connection
                t = threading.Thread(
                    target=handle_client,
                    args=(client_sock, client_addr, cfg),
                    daemon=True
                )
                t.start()
            except Exception as e:
                if self.running:
                    log.error(f"Accept error: {e}")

    def stop(self):
        self.running = False
        if self.server_sock:
            self.server_sock.close()

if __name__ == "__main__":
    proxy = HTTPConnectProxy()
    proxy.start()
