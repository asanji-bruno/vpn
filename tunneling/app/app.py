"""
BDNET Tunnel App — Main Flask Entry Point
Run: python app.py  (port 5000)
Firebase Studio: just run this file, open the preview URL
"""

import json
import os
import threading
import time
from datetime import datetime

from flask import Flask, jsonify, render_template, request
from flask_socketio import SocketIO, emit

from core.engine import TunnelEngine

# ─── Config ───────────────────────────────────────────────────────────────────

BASE_DIR    = os.path.dirname(__file__)
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

def load_cfg():
    with open(CONFIG_PATH) as f:
        return json.load(f)

def save_cfg(data):
    with open(CONFIG_PATH, "w") as f:
        json.dump(data, f, indent=2)

def deep_merge(base, patch):
    result = base.copy()
    for k, v in patch.items():
        if k in result and isinstance(result[k], dict) and isinstance(v, dict):
            result[k] = deep_merge(result[k], v)
        else:
            result[k] = v
    return result

# ─── Flask + SocketIO ─────────────────────────────────────────────────────────

app    = Flask(__name__)
app.config["SECRET_KEY"] = "bdnet-tunnel-secret"
sio    = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

# ─── Engine ───────────────────────────────────────────────────────────────────

engine = TunnelEngine(config_path=CONFIG_PATH)

def on_log(line: str):
    """Called by engine whenever a log line is produced"""
    sio.emit("log", {"msg": line, "ts": datetime.now().strftime("%H:%M:%S.%f")[:-3]})

def on_status(state: str):
    """Called by engine on state change"""
    sio.emit("status", {"state": state, "method": engine.active_method,
                         "uptime": engine.uptime()})

engine.log_callback   = on_log
engine.status_callback = on_status

# ─── Routes ───────────────────────────────────────────────────────────────────

@app.route("/")
def index():
    cfg = load_cfg()
    methods = cfg["method"].get("available",
        ["dns_tunnel","ws_tls","ssh_ws","sni_inject","vless_ws","http_connect"])
    return render_template("index.html",
        methods=methods,
        active=cfg["method"]["active"],
        server_url=cfg["server"]["url"],
        ngrok_url=cfg["server"].get("ngrok_url",""),
        status=engine.state,
        version="0.1.0")

@app.route("/config")
def config_page():
    cfg = load_cfg()
    return render_template("config.html", cfg=cfg)

# ── API ──

@app.route("/api/config", methods=["GET"])
def api_cfg_get():
    return jsonify(load_cfg())

@app.route("/api/config", methods=["POST"])
def api_cfg_post():
    patch = request.get_json(force=True)
    merged = deep_merge(load_cfg(), patch)
    save_cfg(merged)
    engine.reload_config()
    return jsonify({"ok": True})

@app.route("/api/start", methods=["POST"])
def api_start():
    body   = request.get_json(force=True) or {}
    method = body.get("method") or load_cfg()["method"]["active"]
    # Save the chosen method
    cfg = load_cfg()
    cfg["method"]["active"] = method
    save_cfg(cfg)
    result = engine.start(method)
    return jsonify(result)

@app.route("/api/stop", methods=["POST"])
def api_stop():
    return jsonify(engine.stop())

@app.route("/api/restart", methods=["POST"])
def api_restart():
    engine.stop()
    time.sleep(0.5)
    return jsonify(engine.start(engine.active_method or load_cfg()["method"]["active"]))

@app.route("/api/status")
def api_status():
    return jsonify({
        "state":  engine.state,
        "method": engine.active_method,
        "uptime": engine.uptime(),
        "bytes_in":  engine.bytes_in,
        "bytes_out": engine.bytes_out,
    })

@app.route("/api/logs")
def api_logs():
    n = int(request.args.get("n", 200))
    return jsonify(engine.log_buffer[-n:])

@app.route("/api/logs/clear", methods=["POST"])
def api_logs_clear():
    engine.log_buffer.clear()
    return jsonify({"ok": True})

@app.route("/api/methods")
def api_methods():
    cfg = load_cfg()
    methods = cfg["method"].get("available",
        ["dns_tunnel","ws_tls","ssh_ws","sni_inject","vless_ws","http_connect"])
    return jsonify(methods)

# ── SocketIO events ──

@sio.on("connect")
def on_connect():
    # Send last 50 log lines to newly connected client
    for line in engine.log_buffer[-50:]:
        emit("log", {"msg": line, "ts": ""})
    emit("status", {"state": engine.state, "method": engine.active_method,
                     "uptime": engine.uptime()})

@sio.on("cmd_start")
def on_cmd_start(data):
    method = data.get("method", load_cfg()["method"]["active"])
    cfg = load_cfg(); cfg["method"]["active"] = method; save_cfg(cfg)
    engine.start(method)

@sio.on("cmd_stop")
def on_cmd_stop(data):
    engine.stop()

@sio.on("cmd_clear")
def on_cmd_clear(data):
    engine.log_buffer.clear()
    emit("cleared", {})

# ─── Startup ──────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    cfg = load_cfg()
    print(f"\n{'='*55}")
    print(f"  BDNET TUNNEL APP  v0.1.0")
    print(f"  Server  : {cfg['server']['url']}")
    print(f"  Ngrok   : {cfg['server'].get('ngrok_url','')}")
    print(f"  Open    : http://localhost:5000")
    print(f"{'='*55}\n")
    sio.run(app, host="0.0.0.0", port=5000, debug=False, allow_unsafe_werkzeug=True)
