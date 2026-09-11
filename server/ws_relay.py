"""
BDNET Server — WebSocket Relay (Method 2)
Accepts WSS connections from client. Multiplexes multiple TCP streams over one WS.

Frame format (binary):
  [4 bytes: stream_id] [1 byte: msg_type] [4 bytes: data_len] [N bytes: data]

msg_type:
  0x01 = OPEN   data = "host:port"
  0x02 = DATA   data = raw bytes
  0x03 = CLOSE  data = empty
  0x04 = ERROR  data = error string
  0x05 = AUTH   data = uuid token
"""

import asyncio
import json
import logging
import os
import socket
import struct
import time

import websockets

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config", "server_config.json")

def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)

log = logging.getLogger("ws_relay")
logging.basicConfig(level=logging.DEBUG,
    format="[%(asctime)s] [%(levelname)-5s] [ws_relay     ] %(message)s",
    datefmt="%H:%M:%S")

# ─── Frame Helpers ────────────────────────────────────────────────────────────

MSG_OPEN  = 0x01
MSG_DATA  = 0x02
MSG_CLOSE = 0x03
MSG_ERROR = 0x04
MSG_AUTH  = 0x05

def pack_frame(stream_id: int, msg_type: int, data: bytes) -> bytes:
    header = struct.pack(">IBI", stream_id, msg_type, len(data))
    return header + data

def unpack_frame(raw: bytes) -> tuple:
    """Returns (stream_id, msg_type, data)"""
    stream_id, msg_type, data_len = struct.unpack(">IBI", raw[:9])
    data = raw[9:9 + data_len]
    return stream_id, msg_type, data

# ─── Per-Client State ─────────────────────────────────────────────────────────

class ClientSession:
    def __init__(self, ws, allowed_uuids: list):
        self.ws = ws
        self.allowed_uuids = allowed_uuids
        self.authenticated = False
        self.streams: dict = {}       # stream_id → asyncio.StreamWriter
        self.lock = asyncio.Lock()

    async def send_frame(self, stream_id, msg_type, data=b""):
        frame = pack_frame(stream_id, msg_type, data)
        await self.ws.send(frame)

    async def handle(self):
        cfg = load_config()
        self.allowed_uuids = cfg["auth"]["allowed_uuids"]
        # If auth disabled, mark authenticated immediately
        if not cfg["auth"]["require_auth"]:
            self.authenticated = True

        try:
            async for message in self.ws:
                if isinstance(message, str):
                    continue  # ignore text frames
                stream_id, msg_type, data = unpack_frame(message)

                if msg_type == MSG_AUTH:
                    await self._handle_auth(stream_id, data)
                elif not self.authenticated:
                    await self.send_frame(stream_id, MSG_ERROR, b"not authenticated")
                    await self.ws.close()
                    return
                elif msg_type == MSG_OPEN:
                    asyncio.create_task(self._handle_open(stream_id, data))
                elif msg_type == MSG_DATA:
                    await self._handle_data(stream_id, data)
                elif msg_type == MSG_CLOSE:
                    await self._handle_close(stream_id)

        except websockets.exceptions.ConnectionClosed:
            log.info("Client disconnected")
        finally:
            # Close all open streams
            for writer in self.streams.values():
                try:
                    writer.close()
                except Exception:
                    pass

    async def _handle_auth(self, stream_id, data: bytes):
        token = data.decode("utf-8", errors="ignore").strip()
        if token in self.allowed_uuids:
            self.authenticated = True
            log.info(f"Client authenticated: uuid={token[:8]}...")
            await self.send_frame(stream_id, MSG_AUTH, b"ok")
        else:
            log.warning(f"Auth failed for token: {token[:8]}...")
            await self.send_frame(stream_id, MSG_ERROR, b"invalid uuid")
            await self.ws.close()

    async def _handle_open(self, stream_id: int, data: bytes):
        target = data.decode("utf-8", errors="ignore").strip()
        try:
            host, port_str = target.rsplit(":", 1)
            port = int(port_str)
        except ValueError:
            await self.send_frame(stream_id, MSG_ERROR, b"bad target format, expect host:port")
            return

        log.debug(f"[stream {stream_id}] OPEN → {host}:{port}")
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(host, port), timeout=10
            )
        except Exception as e:
            log.warning(f"[stream {stream_id}] Connect failed: {e}")
            await self.send_frame(stream_id, MSG_ERROR, str(e).encode())
            return

        async with self.lock:
            self.streams[stream_id] = writer

        # Start reading from target TCP → forward to WS client
        asyncio.create_task(self._forward_tcp_to_ws(stream_id, reader))

    async def _forward_tcp_to_ws(self, stream_id: int, reader: asyncio.StreamReader):
        try:
            while True:
                chunk = await reader.read(4096)
                if not chunk:
                    break
                await self.send_frame(stream_id, MSG_DATA, chunk)
        except Exception as e:
            log.debug(f"[stream {stream_id}] TCP read error: {e}")
        finally:
            await self.send_frame(stream_id, MSG_CLOSE)
            async with self.lock:
                self.streams.pop(stream_id, None)
            log.debug(f"[stream {stream_id}] CLOSED (tcp→ws)")

    async def _handle_data(self, stream_id: int, data: bytes):
        writer = self.streams.get(stream_id)
        if not writer:
            log.debug(f"[stream {stream_id}] DATA for unknown stream, ignored")
            return
        try:
            writer.write(data)
            await writer.drain()
        except Exception as e:
            log.warning(f"[stream {stream_id}] Write error: {e}")
            await self._handle_close(stream_id)

    async def _handle_close(self, stream_id: int):
        async with self.lock:
            writer = self.streams.pop(stream_id, None)
        if writer:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
        log.debug(f"[stream {stream_id}] CLOSED (client request)")

# ─── Server Entry ─────────────────────────────────────────────────────────────

async def handle_connection(websocket, path=None):
    cfg = load_config()
    allowed = cfg["auth"]["allowed_uuids"]
    log.info(f"New connection from {websocket.remote_address}")
    session = ClientSession(websocket, allowed)
    await session.handle()

async def main():
    cfg = load_config()
    ws_cfg = cfg["methods"]["ws_relay"]
    host = cfg["server"]["host"]
    port = ws_cfg["internal_port"]
    ping = ws_cfg["ping_interval"]

    log.info(f"WS Relay listening on ws://{host}:{port}")
    async with websockets.serve(
        handle_connection,
        host, port,
        ping_interval=ping,
        ping_timeout=30,
        max_size=64 * 1024 * 1024   # 64MB max frame
    ):
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    asyncio.run(main())
