"""
BDNET Server — VLESS Inbound Server (Method 5)
Implements the VLESS protocol over WebSocket transport.

VLESS Request Header (client → server, first frame):
  [0]      Version     = 0x00
  [1-16]   UUID        16 bytes
  [17]     Addon len   (usually 0x00)
  [18]     Command     0x01=TCP 0x02=UDP
  [19-20]  Port        uint16 big-endian
  [21]     Addr type   0x01=IPv4 0x02=Domain 0x03=IPv6
  [22+]    Addr        (if domain: 1 byte length prefix + domain bytes)
  [rest]   Initial data to forward

VLESS Response Header (server → client, first frame):
  [0]      Version     = 0x00
  [1]      Addon len   = 0x00
"""

import asyncio
import json
import logging
import os
import struct
import uuid as uuid_mod

import websockets

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config", "server_config.json")

def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)

log = logging.getLogger("vless_server")
logging.basicConfig(level=logging.DEBUG,
    format="[%(asctime)s] [%(levelname)-5s] [vless_server ] %(message)s",
    datefmt="%H:%M:%S")

ADDR_IPV4   = 0x01
ADDR_DOMAIN = 0x02
ADDR_IPV6   = 0x03

CMD_TCP = 0x01
CMD_UDP = 0x02

def parse_vless_header(data: bytes) -> dict:
    """
    Parse VLESS header bytes. Returns dict with keys:
      version, uuid, command, port, addr_type, address, data_offset
    Raises ValueError on malformed header.
    """
    if len(data) < 22:
        raise ValueError("Header too short")

    version = data[0]
    raw_uuid = data[1:17]
    try:
        client_uuid = str(uuid_mod.UUID(bytes=raw_uuid))
    except Exception:
        raise ValueError("Invalid UUID bytes")

    addon_len = data[17]
    # skip addon bytes
    offset = 18 + addon_len

    command  = data[offset];     offset += 1
    port     = struct.unpack(">H", data[offset:offset+2])[0]; offset += 2
    addr_type = data[offset];    offset += 1

    if addr_type == ADDR_IPV4:
        if len(data) < offset + 4:
            raise ValueError("Short IPv4")
        address = ".".join(str(b) for b in data[offset:offset+4])
        offset += 4
    elif addr_type == ADDR_DOMAIN:
        domain_len = data[offset]; offset += 1
        address = data[offset:offset+domain_len].decode("utf-8")
        offset += domain_len
    elif addr_type == ADDR_IPV6:
        if len(data) < offset + 16:
            raise ValueError("Short IPv6")
        import socket
        address = socket.inet_ntop(socket.AF_INET6, data[offset:offset+16])
        offset += 16
    else:
        raise ValueError(f"Unknown addr type: {addr_type:#x}")

    return {
        "version":     version,
        "uuid":        client_uuid,
        "command":     command,
        "port":        port,
        "addr_type":   addr_type,
        "address":     address,
        "data_offset": offset    # where the actual proxied data begins
    }

CHUNK = 8192

async def handle_vless(websocket, path=None):
    cfg = load_config()
    vcfg = cfg["methods"]["vless_server"]
    allowed_uuids = set(cfg["auth"]["allowed_uuids"])
    client_addr = websocket.remote_address

    # Read first WebSocket frame — must be the VLESS header
    try:
        first_frame = await asyncio.wait_for(websocket.recv(), timeout=10)
    except asyncio.TimeoutError:
        log.warning(f"{client_addr} — timed out waiting for VLESS header")
        await websocket.close(1002, "timeout")
        return

    data = first_frame if isinstance(first_frame, bytes) else first_frame.encode()

    # Parse VLESS header
    try:
        hdr = parse_vless_header(data)
    except ValueError as e:
        log.warning(f"{client_addr} — VLESS parse error: {e}")
        await websocket.close(1002, str(e))
        return

    log.debug(f"{client_addr} — UUID={hdr['uuid'][:8]}... target={hdr['address']}:{hdr['port']}")

    # Validate UUID
    if cfg["auth"]["require_auth"] and hdr["uuid"] not in allowed_uuids:
        log.warning(f"{client_addr} — UUID rejected: {hdr['uuid']}")
        await websocket.close(1008, "forbidden")
        return

    # Only TCP supported in this build
    if hdr["command"] != CMD_TCP:
        log.warning(f"{client_addr} — Unsupported command: {hdr['command']:#x}")
        await websocket.close(1003, "udp not supported")
        return

    # Connect to target
    host, port = hdr["address"], hdr["port"]
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(host, port), timeout=10
        )
    except Exception as e:
        log.error(f"Cannot reach {host}:{port} — {e}")
        await websocket.close(1011, str(e))
        return

    log.info(f"{client_addr} → {host}:{port} [VLESS TCP]")

    # Send VLESS response header: version=0x00, addon_len=0x00
    response_header = bytes([0x00, 0x00])
    await websocket.send(response_header)

    # Forward any initial data that came after the header
    initial_data = data[hdr["data_offset"]:]
    if initial_data:
        writer.write(initial_data)
        await writer.drain()

    # Relay bidirectionally
    async def ws_to_tcp():
        try:
            async for msg in websocket:
                chunk = msg if isinstance(msg, bytes) else msg.encode()
                writer.write(chunk)
                await writer.drain()
        except Exception:
            pass
        finally:
            writer.close()

    async def tcp_to_ws():
        try:
            while True:
                chunk = await reader.read(CHUNK)
                if not chunk:
                    break
                await websocket.send(chunk)
        except Exception:
            pass
        finally:
            await websocket.close()

    done, pending = await asyncio.wait(
        [asyncio.create_task(ws_to_tcp()), asyncio.create_task(tcp_to_ws())],
        return_when=asyncio.FIRST_COMPLETED
    )
    for t in pending:
        t.cancel()

    log.info(f"VLESS session closed: {client_addr}")

async def main():
    cfg = load_config()
    vcfg = cfg["methods"]["vless_server"]
    host = cfg["server"]["host"]
    port = vcfg["internal_port"]

    log.info(f"VLESS Server listening on ws://{host}:{port}")
    async with websockets.serve(handle_vless, host, port):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
