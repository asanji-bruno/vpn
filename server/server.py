"""
BDNET Server — Main Flask Management API
Handles: health check, config R/W, service management, session listing
All settings come from config/server_config.json — nothing hardcoded
"""

import os
import json
import time
import logging
import threading
import subprocess
from datetime import datetime
from logging.handlers import RotatingFileHandler
from functools import wraps

from flask import Flask, request, jsonify, g

# ─── Load Config ──────────────────────────────────────────────────────────────

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config", "server_config.json")

def load_config():
    with open(CONFIG_PATH, "r") as f:
        return json.load(f)

def save_config(data):
    with open(CONFIG_PATH, "w") as f:
        json.dump(data, f, indent=2)

cfg = load_config()

# ─── Logging Setup ────────────────────────────────────────────────────────────

log_dir = cfg["logging"]["log_dir"]
os.makedirs(log_dir, exist_ok=True)

log_level = getattr(logging, cfg["logging"]["level"], logging.DEBUG)
handler = RotatingFileHandler(
    os.path.join(log_dir, "server.log"),
    maxBytes=cfg["logging"]["rotate_mb"] * 1024 * 1024,
    backupCount=cfg["logging"]["keep_files"]
)
logging.basicConfig(
    level=log_level,
    format="[%(asctime)s] [%(levelname)-5s] [%(name)-12s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[handler, logging.StreamHandler()]
)
log = logging.getLogger("server")

# ─── App State ────────────────────────────────────────────────────────────────

START_TIME = time.time()

# Tracks running subprocesses for each service
# Key: service name, Value: subprocess.Popen or threading.Thread
services: dict = {}
service_status: dict = {
    "dns_tunnel":    {"running": False, "pid": None, "started": None, "bytes_rx": 0, "bytes_tx": 0, "sessions": 0},
    "ws_relay":      {"running": False, "pid": None, "started": None, "bytes_rx": 0, "bytes_tx": 0, "connections": 0},
    "ssh_ws_bridge": {"running": False, "pid": None, "started": None, "connections": 0},
    "vless_server":  {"running": False, "pid": None, "started": None, "connections": 0},
    "http_connect":  {"running": False, "pid": None, "started": None, "connections": 0},
}

# Shared recent log buffer (last 500 lines)
log_buffer: list = []
log_lock = threading.Lock()

def push_log(level: str, module: str, msg: str):
    ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    line = f"[{ts}] [{level:<5}] [{module:<12}] {msg}"
    with log_lock:
        log_buffer.append(line)
        if len(log_buffer) > 500:
            log_buffer.pop(0)
    log.info(line)

# ─── Flask App ────────────────────────────────────────────────────────────────

app = Flask(__name__)

# ─── Auth Middleware ──────────────────────────────────────────────────────────

def require_token(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        cfg_now = load_config()
        if not cfg_now["auth"]["require_auth"]:
            return f(*args, **kwargs)
        token = request.headers.get("Authorization", "")
        if token != f"Bearer {cfg_now['auth']['master_token']}":
            return jsonify({"error": "unauthorized"}), 401
        return f(*args, **kwargs)
    return decorated

# ─── Routes ───────────────────────────────────────────────────────────────────

@app.route("/health")
def health():
    """No auth — used by Render, ngrok, and client keep-alive pings"""
    c = load_config()
    return jsonify({
        "status": "ok",
        "uptime": int(time.time() - START_TIME),
        "version": "0.1.0",
        "public_url": c["server"].get("public_url", "local"),
        "ngrok_url":  c["server"].get("ngrok_url", "")
    })

@app.route("/ngrok")
def ngrok_info():
    """Returns the active ngrok/public URL — no auth needed so app can discover it"""
    c = load_config()
    url = c["server"].get("ngrok_url", "")
    return jsonify({
        "ngrok_url":  url,
        "public_url": c["server"].get("public_url", url),
        "ws_url":     url.replace("https://", "wss://").replace("http://", "ws://") + "/ws",
        "vless_url":  url.replace("https://", "wss://").replace("http://", "ws://") + "/vless",
        "ssh_url":    url.replace("https://", "wss://").replace("http://", "ws://") + "/ssh-relay",
        "health_url": url + "/health",
        "api_url":    url + "/api"
    })

@app.route("/")
def index():
    """Camouflage page — looks like a normal site to outside observers"""
    return """<!DOCTYPE html>
<html><head><title>Personal Tech Blog</title></head>
<body style="font-family:sans-serif;max-width:600px;margin:60px auto">
<h2>Welcome</h2>
<p>This is a personal project page. Nothing to see here.</p>
</body></html>"""

@app.route("/api/status")
@require_token
def api_status():
    import psutil
    proc = psutil.Process(os.getpid())
    return jsonify({
        "uptime": int(time.time() - START_TIME),
        "services": service_status,
        "system": {
            "cpu_percent": psutil.cpu_percent(interval=0.1),
            "mem_mb": round(proc.memory_info().rss / 1024 / 1024, 1),
        }
    })

@app.route("/api/config", methods=["GET"])
@require_token
def api_config_get():
    return jsonify(load_config())

@app.route("/api/config", methods=["POST"])
@require_token
def api_config_post():
    """Deep-merge posted JSON into existing config and save"""
    current = load_config()
    patch = request.get_json(force=True)
    merged = deep_merge(current, patch)
    save_config(merged)
    push_log("INFO", "config", "Config updated via API")
    return jsonify({"ok": True, "config": merged})

@app.route("/api/services")
@require_token
def api_services():
    return jsonify(service_status)

@app.route("/api/services/<name>/start", methods=["POST"])
@require_token
def api_service_start(name):
    if name not in service_status:
        return jsonify({"error": f"Unknown service: {name}"}), 404
    result = start_service(name)
    return jsonify(result)

@app.route("/api/services/<name>/stop", methods=["POST"])
@require_token
def api_service_stop(name):
    if name not in service_status:
        return jsonify({"error": f"Unknown service: {name}"}), 404
    result = stop_service(name)
    return jsonify(result)

@app.route("/api/services/<name>/restart", methods=["POST"])
@require_token
def api_service_restart(name):
    stop_service(name)
    time.sleep(1)
    result = start_service(name)
    return jsonify(result)

@app.route("/api/logs")
@require_token
def api_logs():
    n = int(request.args.get("n", 200))
    with log_lock:
        return jsonify(log_buffer[-n:])

@app.route("/api/logs/clear", methods=["POST"])
@require_token
def api_logs_clear():
    with log_lock:
        log_buffer.clear()
    return jsonify({"ok": True})

# ─── Service Manager ──────────────────────────────────────────────────────────

SERVICE_SCRIPTS = {
    "dns_tunnel":    "dns_server.py",
    "ws_relay":      "ws_relay.py",
    "ssh_ws_bridge": "ssh_ws_bridge.py",
    "vless_server":  "vless_server.py",
    "http_connect":  "http_proxy.py",
}

def start_service(name: str) -> dict:
    cfg_now = load_config()
    if not cfg_now["methods"].get(name, {}).get("enabled", False):
        return {"ok": False, "msg": f"{name} is disabled in config"}
    if service_status[name]["running"]:
        return {"ok": False, "msg": f"{name} already running"}

    script = os.path.join(os.path.dirname(__file__), SERVICE_SCRIPTS[name])
    if not os.path.exists(script):
        return {"ok": False, "msg": f"Script not found: {script}"}

    proc = subprocess.Popen(
        ["python", script],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )
    services[name] = proc
    service_status[name]["running"] = True
    service_status[name]["pid"] = proc.pid
    service_status[name]["started"] = datetime.now().isoformat()

    # Background thread to stream process output into log_buffer
    def stream_output():
        for line in proc.stdout:
            push_log("INFO", name[:12], line.rstrip())
        service_status[name]["running"] = False
        service_status[name]["pid"] = None
        push_log("WARN", name[:12], "Process exited")

    t = threading.Thread(target=stream_output, daemon=True)
    t.start()

    push_log("INFO", "manager", f"Started {name} (pid={proc.pid})")
    return {"ok": True, "pid": proc.pid}

def stop_service(name: str) -> dict:
    proc = services.get(name)
    if not proc:
        return {"ok": False, "msg": f"{name} not running"}
    proc.terminate()
    proc.wait(timeout=5)
    services.pop(name, None)
    service_status[name]["running"] = False
    service_status[name]["pid"] = None
    push_log("INFO", "manager", f"Stopped {name}")
    return {"ok": True}

# ─── Helpers ──────────────────────────────────────────────────────────────────

def deep_merge(base: dict, patch: dict) -> dict:
    """Recursively merge patch into base dict"""
    result = base.copy()
    for k, v in patch.items():
        if k in result and isinstance(result[k], dict) and isinstance(v, dict):
            result[k] = deep_merge(result[k], v)
        else:
            result[k] = v
    return result

# ─── Startup ──────────────────────────────────────────────────────────────────

def autostart_services():
    """Start all enabled services on boot"""
    cfg_now = load_config()
    for name, mcfg in cfg_now["methods"].items():
        if mcfg.get("enabled", False):
            push_log("INFO", "manager", f"Autostarting: {name}")
            start_service(name)

if __name__ == "__main__":
    push_log("INFO", "server", "BDNET Server starting...")

    # Print ngrok URL prominently on startup
    ngrok = cfg["server"].get("ngrok_url", "")
    if ngrok:
        push_log("INFO", "server", f"Public URL (ngrok): {ngrok}")
        push_log("INFO", "server", f"WS  relay endpoint: {ngrok.replace('https://','wss://')}/ws")
        push_log("INFO", "server", f"VLESS  endpoint   : {ngrok.replace('https://','wss://')}/vless")
        push_log("INFO", "server", f"SSH-WS endpoint   : {ngrok.replace('https://','wss://')}/ssh-relay")
        push_log("INFO", "server", f"Info endpoint     : {ngrok}/ngrok")

    autostart_thread = threading.Thread(target=autostart_services, daemon=True)
    autostart_thread.start()

    port = cfg["server"]["api_port"]
    host = cfg["server"]["host"]
    push_log("INFO", "server", f"Flask API listening on {host}:{port}")
    app.run(host=host, port=port, debug=False, threaded=True)
