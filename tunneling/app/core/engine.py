"""
BDNET — Core Tunnel Engine
Manages the lifecycle (start/stop/restart) of tunnel modules.
Streams stdout of each module into log_buffer and emits via SocketIO callbacks.
"""

import json
import subprocess
import sys
import threading
import time
import os
from datetime import datetime

BASE_DIR = os.path.dirname(__file__)

METHODS = {
    "dns_tunnel":   os.path.join(BASE_DIR, "dns_tunnel.py"),
    "ws_tls":       os.path.join(BASE_DIR, "ws_tunnel.py"),
    "ssh_ws":       os.path.join(BASE_DIR, "ssh_ws.py"),
    "sni_inject":   os.path.join(BASE_DIR, "sni_inject.py"),
    "vless_ws":     os.path.join(BASE_DIR, "vless_client.py"),
    "http_connect": os.path.join(BASE_DIR, "http_connect.py"),
}

class TunnelEngine:
    def __init__(self, config_path: str):
        self.config_path    = config_path
        self.active_method  = None
        self.state          = "DISCONNECTED"  # DISCONNECTED / CONNECTING / CONNECTED / ERROR
        self.process        = None
        self._start_time    = None
        self.bytes_in       = 0
        self.bytes_out      = 0
        self.log_buffer     = []          # last 500 lines
        self._lock          = threading.Lock()
        self._stop_event    = threading.Event()

        # Callbacks set by app.py
        self.log_callback    = None
        self.status_callback = None

    # ── Public API ────────────────────────────────────────────────────────────

    def start(self, method: str) -> dict:
        if self.state in ("CONNECTING", "CONNECTED"):
            self.stop()
            time.sleep(0.3)

        script = METHODS.get(method)
        if not script:
            self._log("ERROR", "engine", f"Unknown method: {method}")
            return {"ok": False, "msg": f"Unknown method: {method}"}

        if not os.path.exists(script):
            self._log("WARN", "engine", f"Module not built yet: {script}")
            return {"ok": False, "msg": f"Module not found: {os.path.basename(script)}"}

        self.active_method = method
        self._stop_event.clear()
        self._set_state("CONNECTING")
        self._log("INFO", "engine", f"Starting method: {method}")

        self.process = subprocess.Popen(
            [sys.executable, script, "--config", self.config_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )

        t = threading.Thread(target=self._stream_output, daemon=True)
        t.start()

        self._log("INFO", "engine", f"Process started (pid={self.process.pid})")
        self._start_time = time.time()
        return {"ok": True, "pid": self.process.pid, "method": method}

    def stop(self) -> dict:
        self._stop_event.set()
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=4)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass
            self.process = None
        self._set_state("DISCONNECTED")
        self._log("INFO", "engine", "Tunnel stopped")
        self._start_time = None
        return {"ok": True}

    def reload_config(self):
        self._log("INFO", "engine", "Config reloaded")

    def uptime(self) -> int:
        if self._start_time:
            return int(time.time() - self._start_time)
        return 0

    # ── Internal ──────────────────────────────────────────────────────────────

    def _stream_output(self):
        """Read subprocess stdout line by line → push to log buffer"""
        try:
            for line in self.process.stdout:
                line = line.rstrip()
                if not line:
                    continue
                # Detect state hints from module output
                if "[CONNECTED]" in line:
                    self._set_state("CONNECTED")
                elif "[ERROR]" in line or "Error" in line or "error" in line:
                    self._set_state("ERROR")
                self._log("INFO", self.active_method[:10] if self.active_method else "module", line)
        except Exception as e:
            self._log("WARN", "engine", f"Stream error: {e}")
        finally:
            if not self._stop_event.is_set():
                self._set_state("DISCONNECTED")
                self._log("WARN", "engine", "Process exited unexpectedly")

    def _set_state(self, state: str):
        self.state = state
        if self.status_callback:
            try:
                self.status_callback(state)
            except Exception:
                pass

    def _log(self, level: str, module: str, msg: str):
        ts   = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        line = f"[{ts}] [{level:<5}] [{module:<10}] {msg}"
        with self._lock:
            self.log_buffer.append(line)
            if len(self.log_buffer) > 500:
                self.log_buffer.pop(0)
        if self.log_callback:
            try:
                self.log_callback(line)
            except Exception:
                pass
