#!/usr/bin/env python3
"""
VLM Server Launcher — tkinter GUI for managing vlm_server.py.

Provides a desktop GUI to:
  - Start/stop the VLM server as a subprocess
  - Display the LAN URL (copy-to-clipboard) for Android app configuration
  - Test /api/health and /api/detect endpoints
  - Configure port, camera, FPS, model, and mock mode
  - Stream real-time server logs

No external dependencies — uses only Python stdlib (tkinter ships with Python).

Usage:
    python3 vlm_launcher.py
"""

import json
import os
import socket
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import scrolledtext
from urllib.request import urlopen, Request
from urllib.error import URLError


def get_lan_ip() -> str:
    """Detect the machine's LAN IP address."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class VlmLauncher:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("VLM Server Launcher")
        self.root.geometry("580x620")
        self.root.minsize(480, 500)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.process: subprocess.Popen | None = None
        self.log_thread: threading.Thread | None = None

        self._build_ui()
        self._update_status()

    def _build_ui(self):
        pad = {"padx": 10, "pady": 4}

        # --- URL Section ---
        url_frame = tk.LabelFrame(self.root, text="Configure in Android app", padx=8, pady=6)
        url_frame.pack(fill=tk.X, **pad)

        url_row = tk.Frame(url_frame)
        url_row.pack(fill=tk.X)

        self.url_var = tk.StringVar()
        self.url_entry = tk.Entry(url_row, textvariable=self.url_var, state="readonly",
                                  font=("Courier", 13), readonlybackground="#f0f0f0")
        self.url_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)

        self.copy_btn = tk.Button(url_row, text="Copy URL", width=10, command=self.copy_url)
        self.copy_btn.pack(side=tk.RIGHT, padx=(6, 0))

        # Status label
        self.status_label = tk.Label(url_frame, text="Status: \u25cf Stopped",
                                     fg="red", font=("", 11), anchor="w")
        self.status_label.pack(fill=tk.X, pady=(4, 0))

        # --- Buttons ---
        btn_frame = tk.Frame(self.root)
        btn_frame.pack(fill=tk.X, **pad)

        self.start_btn = tk.Button(btn_frame, text="\u25b6 Start Server", width=14,
                                   command=self.start_server, bg="#4CAF50", fg="white",
                                   font=("", 10, "bold"))
        self.start_btn.pack(side=tk.LEFT, padx=(0, 4))

        self.health_btn = tk.Button(btn_frame, text="Test Health", width=12,
                                    command=self.test_health)
        self.health_btn.pack(side=tk.LEFT, padx=4)

        self.query_btn = tk.Button(btn_frame, text="Test Query", width=12,
                                   command=self.test_query)
        self.query_btn.pack(side=tk.LEFT, padx=4)

        # --- Settings ---
        settings_frame = tk.LabelFrame(self.root, text="Settings", padx=8, pady=6)
        settings_frame.pack(fill=tk.X, **pad)

        row1 = tk.Frame(settings_frame)
        row1.pack(fill=tk.X, pady=2)

        tk.Label(row1, text="Port:").pack(side=tk.LEFT)
        self.port_var = tk.StringVar(value="8000")
        tk.Entry(row1, textvariable=self.port_var, width=6).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(row1, text="Camera:").pack(side=tk.LEFT)
        self.camera_var = tk.StringVar(value="0")
        tk.Entry(row1, textvariable=self.camera_var, width=4).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(row1, text="FPS:").pack(side=tk.LEFT)
        self.fps_var = tk.StringVar(value="1.0")
        tk.Entry(row1, textvariable=self.fps_var, width=5).pack(side=tk.LEFT, padx=(2, 0))

        row2 = tk.Frame(settings_frame)
        row2.pack(fill=tk.X, pady=2)

        tk.Label(row2, text="Model:").pack(side=tk.LEFT)
        self.model_var = tk.StringVar(value="Qwen/Qwen2.5-VL-7B-Instruct")
        tk.Entry(row2, textvariable=self.model_var, width=36).pack(side=tk.LEFT, padx=(2, 12))

        self.mock_var = tk.BooleanVar(value=True)
        tk.Checkbutton(row2, text="Mock mode", variable=self.mock_var).pack(side=tk.LEFT)

        # --- Log ---
        log_frame = tk.LabelFrame(self.root, text="Server Log", padx=4, pady=4)
        log_frame.pack(fill=tk.BOTH, expand=True, **pad)

        self.log_text = scrolledtext.ScrolledText(log_frame, height=12, state=tk.DISABLED,
                                                  font=("Courier", 10), wrap=tk.WORD,
                                                  bg="#1e1e1e", fg="#d4d4d4",
                                                  insertbackground="#d4d4d4")
        self.log_text.pack(fill=tk.BOTH, expand=True)

        self._refresh_url()

    def _refresh_url(self):
        """Update the displayed URL based on current port setting."""
        ip = get_lan_ip()
        port = self.port_var.get().strip() or "8000"
        url = f"http://{ip}:{port}"
        self.url_var.set(url)

    def copy_url(self):
        """Copy the server URL to clipboard."""
        self._refresh_url()
        self.root.clipboard_clear()
        self.root.clipboard_append(self.url_var.get())
        self._log("URL copied to clipboard")

    def _log(self, message: str):
        """Append a message to the log widget (thread-safe via root.after)."""
        def _append():
            self.log_text.config(state=tk.NORMAL)
            self.log_text.insert(tk.END, message + "\n")
            self.log_text.see(tk.END)
            self.log_text.config(state=tk.DISABLED)
        self.root.after(0, _append)

    def _update_status(self):
        """Update status label and button states based on process state."""
        running = self.process is not None and self.process.poll() is None
        if running:
            self.status_label.config(text="Status: \u25cf Running", fg="green")
            self.start_btn.config(text="\u25a0 Stop Server", bg="#f44336",
                                  command=self.stop_server)
            # Disable settings while running
            for w in [self.port_var, self.camera_var, self.fps_var, self.model_var]:
                pass  # Entry widgets are still accessible but changes won't apply
        else:
            self.status_label.config(text="Status: \u25cf Stopped", fg="red")
            self.start_btn.config(text="\u25b6 Start Server", bg="#4CAF50",
                                  command=self.start_server)

    def start_server(self):
        """Start vlm_server.py as a subprocess."""
        if self.process and self.process.poll() is None:
            self._log("Server is already running")
            return

        self._refresh_url()

        # Locate vlm_server.py relative to this script
        script_dir = os.path.dirname(os.path.abspath(__file__))
        server_script = os.path.join(script_dir, "vlm_server.py")

        if not os.path.exists(server_script):
            self._log(f"ERROR: {server_script} not found")
            return

        port = self.port_var.get().strip() or "8000"
        camera = self.camera_var.get().strip() or "0"
        fps = self.fps_var.get().strip() or "1.0"
        model = self.model_var.get().strip()

        cmd = [sys.executable, server_script,
               "--port", port,
               "--camera", camera,
               "--fps", fps,
               "--host", "0.0.0.0"]

        if self.mock_var.get():
            cmd.append("--mock")
        else:
            cmd.extend(["--model", model])

        self._log(f"Starting: {' '.join(cmd)}")

        try:
            self.process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1
            )
        except Exception as e:
            self._log(f"ERROR starting server: {e}")
            return

        self._update_status()

        # Start log reader daemon thread
        self.log_thread = threading.Thread(target=self._read_log, daemon=True)
        self.log_thread.start()

    def _read_log(self):
        """Read subprocess stdout line-by-line and push to log widget."""
        proc = self.process
        if not proc or not proc.stdout:
            return

        try:
            for line in proc.stdout:
                self._log(line.rstrip("\n"))
        except Exception:
            pass

        # Process ended
        exit_code = proc.poll()
        self._log(f"Server exited (code {exit_code})")
        self.root.after(0, self._update_status)

    def stop_server(self):
        """Stop the running server subprocess."""
        if not self.process:
            self._log("No server running")
            return

        self._log("Stopping server...")
        self.process.terminate()

        # Wait up to 3s for graceful shutdown
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self._log("Force killing server...")
            self.process.kill()
            self.process.wait()

        self.process = None
        self._update_status()
        self._log("Server stopped")

    def test_health(self):
        """Test /api/health endpoint in a background thread."""
        port = self.port_var.get().strip() or "8000"
        url = f"http://localhost:{port}/api/health"

        def _do():
            self._log(f"GET {url}")
            try:
                req = Request(url, method="GET")
                with urlopen(req, timeout=5) as resp:
                    body = resp.read().decode()
                data = json.loads(body)
                formatted = json.dumps(data, indent=2)
                self._log(f"Health OK:\n{formatted}")
            except URLError as e:
                self._log(f"Health FAILED: {e.reason}")
            except Exception as e:
                self._log(f"Health FAILED: {e}")

        threading.Thread(target=_do, daemon=True).start()

    def test_query(self):
        """Test /api/detect endpoint in a background thread."""
        port = self.port_var.get().strip() or "8000"
        url = f"http://localhost:{port}/api/detect"

        def _do():
            self._log(f"GET {url}")
            try:
                req = Request(url, method="GET")
                with urlopen(req, timeout=10) as resp:
                    body = resp.read().decode()
                data = json.loads(body)
                formatted = json.dumps(data, indent=2)
                self._log(f"Detect OK:\n{formatted}")
            except URLError as e:
                self._log(f"Query FAILED: {e.reason}")
            except Exception as e:
                self._log(f"Query FAILED: {e}")

        threading.Thread(target=_do, daemon=True).start()

    def on_close(self):
        """Handle window close — stop server if running."""
        if self.process and self.process.poll() is None:
            self._log("Shutting down server before exit...")
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.root.destroy()


def main():
    root = tk.Tk()
    VlmLauncher(root)
    root.mainloop()


if __name__ == "__main__":
    main()
