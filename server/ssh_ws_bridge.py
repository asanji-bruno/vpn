"""
BDNET Server — SSH over WebSocket Bridge (Method 3)
Bridges any WebSocket connection to a raw TCP target (default: localhost:22).
Client connects via WebSocket, gets a raw SSH socket on the other side.
"""

import asyncio
import json
import logging
import os

import websockets

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config", "server_config.json")

def load_config():
    with open(CONFIG_PATH) as f:
        return json.load(f)

log = logging.getLogger("ssh_ws_bridge")
logging.basicConfig(level=logging.DEBUG,
    format="[%(asctime)s] [%(levelname)-5s] [ssh_ws_bridge] %(message)s",
    datefmt="%H:%M:%S")

CHUNK = 8192

async def bridge(websocket, path=None):
    cfg = load_config()
    scfg = cfg["methods"]["ssh_ws_bridge"]
    target_host = scfg["target_host"]
    target_port = scfg["target_port"]

    client_addr = websocket.remote_address
    log.info(f"Bridge request from {client_addr} → {target_host}:{target_port}")

    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(target_host, target_port), timeout=10
        )
    except Exception as e:
        log.error(f"Cannot connect to target {target_host}:{target_port} — {e}")
        await websocket.close(1011, f"Target unreachable: {e}")
        return

    log.info(f"Bridge established: {client_addr} ↔ {target_host}:{target_port}")

    async def ws_to_tcp():
        """Read bytes from WebSocket client, write to TCP target"""
        try:
            async for message in websocket:
                data = message if isinstance(message, bytes) else message.encode()
                writer.write(data)
                await writer.drain()
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            log.debug(f"ws→tcp error: {e}")
        finally:
            writer.close()

    async def tcp_to_ws():
        """Read bytes from TCP target, write to WebSocket client"""
        try:
            while True:
                chunk = await reader.read(CHUNK)
                if not chunk:
                    break
                await websocket.send(chunk)
        except Exception as e:
            log.debug(f"tcp→ws error: {e}")
        finally:
            await websocket.close()

    # Run both directions concurrently; stop when either finishes
    done, pending = await asyncio.wait(
        [asyncio.create_task(ws_to_tcp()), asyncio.create_task(tcp_to_ws())],
        return_when=asyncio.FIRST_COMPLETED
    )
    for task in pending:
        task.cancel()

    log.info(f"Bridge closed: {client_addr}")

async def main():
    cfg = load_config()
    scfg = cfg["methods"]["ssh_ws_bridge"]
    host = cfg["server"]["host"]
    port = scfg["internal_port"]

    log.info(f"SSH-WS Bridge listening on ws://{host}:{port}")
    log.info(f"  Forwarding to: {scfg['target_host']}:{scfg['target_port']}")

    async with websockets.serve(bridge, host, port):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
