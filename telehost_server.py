#!/usr/bin/env python3
"""
TeleHost — Python Fallback Runtime & Termux / Android Server
Serves the exact same Google Material Design 3 UI and REST API.
"""

import os
import sys
import json
import time
import subprocess
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
import webbrowser

PORT = int(os.environ.get("PORT", 8080))
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
UI_DIR = os.path.join(BASE_DIR, "ui")
CONFIG_FILE = os.path.join(BASE_DIR, "telehost_bots.json")

BOTS = []
PROCESSES = {}
LOGS = {}
LOCK = threading.Lock()


def load_bots():
    global BOTS
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                BOTS = json.load(f)
                return
        except Exception:
            pass

    default_example = os.path.join(BASE_DIR, "examples", "telegram_business_bot")
    BOTS = [{
        "id": "crypto_business_bot",
        "name": "Crypto Business Bot (Example)",
        "working_dir": default_example,
        "command": "start_business_bot.bat" if os.name == 'nt' else "python main.py",
        "auto_restart": True,
        "status": "stopped",
        "pid": None,
        "started_at": None,
    }]
    save_bots()


def save_bots():
    with LOCK:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(BOTS, f, indent=2, ensure_ascii=False)


def start_bot(bot_id):
    with LOCK:
        bot = next((b for b in BOTS if b["id"] == bot_id), None)
        if not bot or bot["status"] == "running":
            return False

        cmd = bot["command"].strip()
        wdir = bot["working_dir"] or BASE_DIR

        if bot_id not in LOGS:
            LOGS[bot_id] = []

        now_str = time.strftime("%H:%M:%S")
        LOGS[bot_id].append(f"[{now_str}] 🚀 Запуск процесса: {cmd}")

        try:
            if os.name == 'nt' and (cmd.lower().endswith(".bat") or cmd.lower().endswith(".cmd")):
                full_cmd = f'cmd.exe /c "{cmd}"'
            elif cmd.lower().endswith(".py"):
                full_cmd = [sys.executable, "-u", cmd]
            else:
                full_cmd = cmd

            proc = subprocess.Popen(
                full_cmd,
                cwd=wdir,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
                shell=(isinstance(full_cmd, str) and os.name == 'nt')
            )

            bot["status"] = "running"
            bot["pid"] = proc.pid
            bot["started_at"] = int(time.time() * 1000)
            PROCESSES[bot_id] = proc

            def stream_reader(pipe, is_err):
                for line in iter(pipe.readline, ''):
                    if not line:
                        break
                    t = time.strftime("%H:%M:%S")
                    prefix = "[ERROR] " if is_err else ""
                    with LOCK:
                        LOGS[bot_id].append(f"[{t}] {prefix}{line.strip()}")
                        if len(LOGS[bot_id]) > 1500:
                            LOGS[bot_id].pop(0)
                pipe.close()

            threading.Thread(target=stream_reader, args=(proc.stdout, False), daemon=True).start()
            threading.Thread(target=stream_reader, args=(proc.stderr, True), daemon=True).start()

            def monitor():
                proc.wait()
                with LOCK:
                    if bot_id in PROCESSES:
                        del PROCESSES[bot_id]
                    bot["status"] = "stopped" if proc.returncode == 0 else "error"
                    bot["pid"] = None
                    t = time.strftime("%H:%M:%S")
                    LOGS[bot_id].append(f"[{t}] ⏹ Процесс завершился с кодом {proc.returncode}")
                    auto_r = bot.get("auto_restart", True)
                if auto_r and proc.returncode != 0:
                    time.sleep(3)
                    start_bot(bot_id)

            threading.Thread(target=monitor, daemon=True).start()
            save_bots()
            return True
        except Exception as e:
            bot["status"] = "error"
            LOGS[bot_id].append(f"[{now_str}] [ERROR] {e}")
            save_bots()
            return False


def stop_bot(bot_id):
    with LOCK:
        bot = next((b for b in BOTS if b["id"] == bot_id), None)
        if not bot:
            return False

        proc = PROCESSES.pop(bot_id, None)
        if proc:
            try:
                if os.name == 'nt':
                    subprocess.run(f"taskkill /F /T /PID {proc.pid}", shell=True, capture_output=True)
                else:
                    proc.terminate()
            except Exception:
                pass

        bot["status"] = "stopped"
        bot["pid"] = None
        bot["started_at"] = None
        t = time.strftime("%H:%M:%S")
        if bot_id in LOGS:
            LOGS[bot_id].append(f"[{t}] ⏹ Остановлен пользователем")
        save_bots()
        return True


class RequestHandler(BaseHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        # 1. Static Files
        if path in ("/", "/index.html"):
            self.serve_file(os.path.join(UI_DIR, "index.html"), "text/html; charset=utf-8")
        elif path == "/style.css":
            self.serve_file(os.path.join(UI_DIR, "style.css"), "text/css; charset=utf-8")
        elif path == "/app.js":
            self.serve_file(os.path.join(UI_DIR, "app.js"), "application/javascript; charset=utf-8")
        # 2. API Routes
        elif path == "/api/system":
            py_ver = sys.version.split()[0]
            self.respond_json({
                "os": os.name,
                "python_version": f"Python {py_ver}",
                "python_cmd": sys.executable,
                "arch": "x86_64"
            })
        elif path == "/api/bots":
            with LOCK:
                self.respond_json(BOTS)
        elif path == "/api/bots/logs":
            bot_id = query.get("id", [""])[0]
            since = int(query.get("since", [0])[0])
            with LOCK:
                lines = LOGS.get(bot_id, [])
                slice_lines = lines[since:] if since < len(lines) else []
                self.respond_json({
                    "next_index": len(lines),
                    "lines": slice_lines
                })
        elif path == "/api/pip/list":
            try:
                out = subprocess.check_output([sys.executable, "-m", "pip", "list", "--format=json"], text=True)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(out.encode("utf-8"))
            except Exception:
                self.respond_json([])
        elif path == "/api/files/read":
            bot_id = query.get("bot_id", [""])[0]
            fname = query.get("file", [".env"])[0]
            with LOCK:
                bot = next((b for b in BOTS if b["id"] == bot_id), None)
            if bot:
                fpath = os.path.join(bot["working_dir"], fname)
                if os.path.exists(fpath):
                    try:
                        with open(fpath, "r", encoding="utf-8") as f:
                            self.respond_json({"success": True, "file": fname, "content": f.read()})
                            return
                    except Exception as e:
                        self.respond_json({"success": False, "error": str(e)})
                        return
            self.respond_json({"success": False, "error": "File not found"})
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8")) if length > 0 else {}

        if path == "/api/bots/add":
            b_id = f"bot_{int(time.time() * 1000) % 100000}"
            new_bot = {
                "id": b_id,
                "name": body.get("name", "Bot"),
                "working_dir": body.get("working_dir", ""),
                "command": body.get("command", "main.py"),
                "auto_restart": body.get("auto_restart", True),
                "status": "stopped",
                "pid": None,
                "started_at": None,
            }
            with LOCK:
                BOTS.append(new_bot)
            save_bots()
            self.respond_json({"success": True, "id": b_id})
        elif path == "/api/bots/start":
            bot_id = body.get("id")
            ok = start_bot(bot_id)
            self.respond_json({"success": ok})
        elif path == "/api/bots/stop":
            bot_id = body.get("id")
            ok = stop_bot(bot_id)
            self.respond_json({"success": ok})
        elif path == "/api/bots/restart":
            bot_id = body.get("id")
            stop_bot(bot_id)
            time.sleep(0.5)
            ok = start_bot(bot_id)
            self.respond_json({"success": ok})
        elif path == "/api/bots/delete":
            bot_id = body.get("id")
            stop_bot(bot_id)
            with LOCK:
                BOTS[:] = [b for b in BOTS if b["id"] != bot_id]
            save_bots()
            self.respond_json({"success": True})
        elif path == "/api/bots/logs/clear":
            bot_id = body.get("id")
            with LOCK:
                if bot_id in LOGS:
                    LOGS[bot_id].clear()
            self.respond_json({"success": True})
        elif path == "/api/pip/install":
            pkg = body.get("package", "")
            try:
                res = subprocess.run([sys.executable, "-m", "pip", "install", pkg], capture_output=True, text=True)
                self.respond_json({"success": res.returncode == 0, "output": res.stdout + res.stderr})
            except Exception as e:
                self.respond_json({"success": False, "output": str(e)})
        elif path == "/api/pip/uninstall":
            pkg = body.get("package", "")
            try:
                res = subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", pkg], capture_output=True, text=True)
                self.respond_json({"success": res.returncode == 0, "output": res.stdout + res.stderr})
            except Exception as e:
                self.respond_json({"success": False, "output": str(e)})
        elif path == "/api/pip/install_requirements":
            wdir = body.get("working_dir", "")
            req_path = os.path.join(wdir, "requirements.txt")
            try:
                res = subprocess.run([sys.executable, "-m", "pip", "install", "-r", req_path], cwd=wdir, capture_output=True, text=True)
                self.respond_json({"success": res.returncode == 0, "output": res.stdout + res.stderr})
            except Exception as e:
                self.respond_json({"success": False, "output": str(e)})
        elif path == "/api/files/write":
            bot_id = body.get("bot_id", "")
            fname = body.get("file", "")
            content = body.get("content", "")
            with LOCK:
                bot = next((b for b in BOTS if b["id"] == bot_id), None)
            if bot:
                fpath = os.path.join(bot["working_dir"], fname)
                try:
                    with open(fpath, "w", encoding="utf-8") as f:
                        f.write(content)
                    self.respond_json({"success": True})
                    return
                except Exception as e:
                    self.respond_json({"success": False, "error": str(e)})
                    return
            self.respond_json({"success": False, "error": "Bot not found"})
        else:
            self.send_error(404, "API Not Found")

    def serve_file(self, filepath, ctype):
        if not os.path.exists(filepath):
            self.send_error(404, "File not found")
            return
        with open(filepath, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def respond_json(self, data):
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass  # Quiet HTTP logs


def main():
    load_bots()
    server = HTTPServer(("0.0.0.0", PORT), RequestHandler)
    url = f"http://localhost:{PORT}"
    print(f"🚀 TeleHost Python Server running at: {url}")
    try:
        webbrowser.open(url)
    except Exception:
        pass
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping TeleHost...")
        server.server_close()


if __name__ == "__main__":
    main()
