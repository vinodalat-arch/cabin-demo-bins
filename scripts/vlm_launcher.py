#!/usr/bin/env python3
"""
VLM Server Launcher — tkinter GUI for managing vlm_server.py.

Provides a desktop GUI to:
  - Start/stop the VLM bridge server as a subprocess
  - Display the LAN URL (copy-to-clipboard) for Android app configuration
  - Test /api/health and /api/detect endpoints
  - Configure server settings (host, port, camera, vLLM connection)
  - Configure inference parameters (thresholds, temperature, tokens)
  - Stream real-time server logs

Two tabs:
  - Server Config: host, port, camera, FPS, vLLM connection mode
  - Inference: confidence thresholds, VLM generation parameters

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
from tkinter import ttk, scrolledtext
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
        self.root.geometry("620x720")
        self.root.minsize(520, 600)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.process: subprocess.Popen | None = None
        self.log_thread: threading.Thread | None = None

        self._build_ui()
        self._update_status()

    def _build_ui(self):
        pad = {"padx": 10, "pady": 4}

        # --- URL Section (always visible at top) ---
        url_frame = tk.LabelFrame(self.root, text="Configure in Android app", padx=8, pady=6)
        url_frame.pack(fill=tk.X, **pad)

        # Target toggle: real device vs emulator
        target_row = tk.Frame(url_frame)
        target_row.pack(fill=tk.X, pady=(0, 4))

        self.target_var = tk.StringVar(value="device")
        tk.Radiobutton(target_row, text="Real device (LAN IP)",
                        variable=self.target_var, value="device",
                        command=self._refresh_url).pack(side=tk.LEFT)
        tk.Radiobutton(target_row, text="Emulator (10.0.2.2)",
                        variable=self.target_var, value="emulator",
                        command=self._refresh_url).pack(side=tk.LEFT, padx=(12, 0))

        url_row = tk.Frame(url_frame)
        url_row.pack(fill=tk.X)

        self.url_var = tk.StringVar()
        self.url_entry = tk.Entry(url_row, textvariable=self.url_var, state="readonly",
                                  font=("Courier", 13), readonlybackground="#f0f0f0")
        self.url_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)

        self.copy_btn = tk.Button(url_row, text="Copy URL", width=10, command=self.copy_url)
        self.copy_btn.pack(side=tk.RIGHT, padx=(6, 0))

        # Status labels
        status_row = tk.Frame(url_frame)
        status_row.pack(fill=tk.X, pady=(4, 0))

        self.status_label = tk.Label(status_row, text="Server: \u25cf Stopped",
                                     fg="red", font=("", 11), anchor="w")
        self.status_label.pack(side=tk.LEFT)

        self.client_label = tk.Label(status_row, text="",
                                     font=("", 11), anchor="e")
        self.client_label.pack(side=tk.RIGHT)

        # --- Buttons ---
        btn_frame = tk.Frame(self.root)
        btn_frame.pack(fill=tk.X, **pad)

        self.start_btn = tk.Button(btn_frame, text="\u25b6  START SERVER", width=18,
                                   command=self.start_server,
                                   highlightbackground="#4CAF50",
                                   font=("Helvetica", 13, "bold"), pady=6)
        self.start_btn.pack(side=tk.LEFT, padx=(0, 8))

        self.health_btn = tk.Button(btn_frame, text="Test Health", width=12,
                                    command=self.test_health, font=("", 11))
        self.health_btn.pack(side=tk.LEFT, padx=4)

        self.query_btn = tk.Button(btn_frame, text="Test Query", width=12,
                                   command=self.test_query, font=("", 11))
        self.query_btn.pack(side=tk.LEFT, padx=4)

        # --- Tabbed Settings ---
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.X, **pad)

        self._build_server_tab()
        self._build_inference_tab()

        # --- Log ---
        log_frame = tk.LabelFrame(self.root, text="Server Log", padx=4, pady=4)
        log_frame.pack(fill=tk.BOTH, expand=True, **pad)

        self.log_text = scrolledtext.ScrolledText(log_frame, height=12, state=tk.DISABLED,
                                                  font=("Courier", 10), wrap=tk.WORD,
                                                  bg="#1e1e1e", fg="#d4d4d4",
                                                  insertbackground="#d4d4d4")
        self.log_text.pack(fill=tk.BOTH, expand=True)

        self._refresh_url()

        # Auto-refresh URL when host or port changes
        self.host_var.trace_add("write", lambda *_: self._refresh_url())
        self.port_var.trace_add("write", lambda *_: self._refresh_url())

        # Start client connection polling
        self._poll_client_status()

    def _build_server_tab(self):
        """Build the Server Config tab."""
        tab = tk.Frame(self.notebook, padx=8, pady=8)
        self.notebook.add(tab, text="  Server Config  ")

        # --- Network ---
        net_frame = tk.LabelFrame(tab, text="Network", padx=6, pady=4)
        net_frame.pack(fill=tk.X, pady=(0, 6))

        row1 = tk.Frame(net_frame)
        row1.pack(fill=tk.X, pady=2)

        tk.Label(row1, text="Host IP:").pack(side=tk.LEFT)
        self.host_var = tk.StringVar(value=get_lan_ip())
        tk.Entry(row1, textvariable=self.host_var, width=15).pack(side=tk.LEFT, padx=(2, 6))
        tk.Button(row1, text="Auto", width=4, command=self._auto_detect_ip).pack(side=tk.LEFT, padx=(0, 12))

        tk.Label(row1, text="Port:").pack(side=tk.LEFT)
        self.port_var = tk.StringVar(value="8000")
        tk.Entry(row1, textvariable=self.port_var, width=6).pack(side=tk.LEFT, padx=(2, 0))

        # --- Camera ---
        cam_frame = tk.LabelFrame(tab, text="Camera", padx=6, pady=4)
        cam_frame.pack(fill=tk.X, pady=(0, 6))

        cam_row = tk.Frame(cam_frame)
        cam_row.pack(fill=tk.X, pady=2)

        tk.Label(cam_row, text="Camera ID:").pack(side=tk.LEFT)
        self.camera_var = tk.StringVar(value="0")
        tk.Entry(cam_row, textvariable=self.camera_var, width=4).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(cam_row, text="FPS:").pack(side=tk.LEFT)
        self.fps_var = tk.StringVar(value="2.0")
        tk.Entry(cam_row, textvariable=self.fps_var, width=5).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(cam_row, text="JPEG Quality:").pack(side=tk.LEFT)
        self.jpeg_quality_var = tk.StringVar(value="80")
        tk.Entry(cam_row, textvariable=self.jpeg_quality_var, width=4).pack(side=tk.LEFT, padx=(2, 0))

        # --- vLLM Connection ---
        vllm_frame = tk.LabelFrame(tab, text="vLLM Connection", padx=6, pady=4)
        vllm_frame.pack(fill=tk.X, pady=(0, 6))

        # Mode radio buttons
        mode_row = tk.Frame(vllm_frame)
        mode_row.pack(fill=tk.X, pady=2)

        self.vllm_mode_var = tk.StringVar(value="mock")
        tk.Radiobutton(mode_row, text="Mock (no GPU)",
                        variable=self.vllm_mode_var, value="mock",
                        command=self._toggle_vllm_fields).pack(side=tk.LEFT)
        tk.Radiobutton(mode_row, text="Connect to vLLM",
                        variable=self.vllm_mode_var, value="connect",
                        command=self._toggle_vllm_fields).pack(side=tk.LEFT, padx=(10, 0))
        tk.Radiobutton(mode_row, text="Start vLLM",
                        variable=self.vllm_mode_var, value="start",
                        command=self._toggle_vllm_fields).pack(side=tk.LEFT, padx=(10, 0))

        # Connect mode: vLLM URL
        self.connect_frame = tk.Frame(vllm_frame)
        self.connect_frame.pack(fill=tk.X, pady=2)

        self.vllm_url_label = tk.Label(self.connect_frame, text="vLLM URL:")
        self.vllm_url_label.pack(side=tk.LEFT)
        self.vllm_url_var = tk.StringVar(value="http://localhost:8080")
        self.vllm_url_entry = tk.Entry(self.connect_frame, textvariable=self.vllm_url_var, width=30)
        self.vllm_url_entry.pack(side=tk.LEFT, padx=(2, 0))

        # Start mode: model path + vLLM port + GPU settings
        self.start_frame = tk.Frame(vllm_frame)
        self.start_frame.pack(fill=tk.X, pady=2)

        start_row1 = tk.Frame(self.start_frame)
        start_row1.pack(fill=tk.X, pady=1)
        self.model_path_label = tk.Label(start_row1, text="Model path:")
        self.model_path_label.pack(side=tk.LEFT)
        self.model_path_var = tk.StringVar(value="/home/kpit/code/qwen3_offline_4B")
        self.model_path_entry = tk.Entry(start_row1, textvariable=self.model_path_var, width=36)
        self.model_path_entry.pack(side=tk.LEFT, padx=(2, 0), fill=tk.X, expand=True)

        start_row2 = tk.Frame(self.start_frame)
        start_row2.pack(fill=tk.X, pady=1)
        tk.Label(start_row2, text="vLLM port:").pack(side=tk.LEFT)
        self.vllm_port_var = tk.StringVar(value="8080")
        tk.Entry(start_row2, textvariable=self.vllm_port_var, width=6).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(start_row2, text="GPU mem:").pack(side=tk.LEFT)
        self.gpu_mem_var = tk.StringVar(value="0.8")
        tk.Entry(start_row2, textvariable=self.gpu_mem_var, width=5).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(start_row2, text="Max model len:").pack(side=tk.LEFT)
        self.max_model_len_var = tk.StringVar(value="16384")
        tk.Entry(start_row2, textvariable=self.max_model_len_var, width=7).pack(side=tk.LEFT, padx=(2, 0))

        start_row3 = tk.Frame(self.start_frame)
        start_row3.pack(fill=tk.X, pady=1)
        tk.Label(start_row3, text="Startup timeout (s):").pack(side=tk.LEFT)
        self.startup_timeout_var = tk.StringVar(value="300")
        tk.Entry(start_row3, textvariable=self.startup_timeout_var, width=5).pack(side=tk.LEFT, padx=(2, 0))

        # Scenario (applies to mock mode)
        scenario_row = tk.Frame(vllm_frame)
        scenario_row.pack(fill=tk.X, pady=2)
        self.scenario_var = tk.BooleanVar(value=False)
        tk.Checkbutton(scenario_row, text="Test-all scenario (cycle all detections)",
                        variable=self.scenario_var).pack(side=tk.LEFT)

        # Initial state
        self._toggle_vllm_fields()

    def _build_inference_tab(self):
        """Build the Inference Parameters tab."""
        tab = tk.Frame(self.notebook, padx=8, pady=8)
        self.notebook.add(tab, text="  Inference Parameters  ")

        # --- VLM Generation ---
        gen_frame = tk.LabelFrame(tab, text="VLM Generation", padx=6, pady=4)
        gen_frame.pack(fill=tk.X, pady=(0, 6))

        gen_row = tk.Frame(gen_frame)
        gen_row.pack(fill=tk.X, pady=2)

        tk.Label(gen_row, text="Max tokens:").pack(side=tk.LEFT)
        self.max_tokens_var = tk.StringVar(value="300")
        tk.Entry(gen_row, textvariable=self.max_tokens_var, width=6).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(gen_row, text="Temperature:").pack(side=tk.LEFT)
        self.temperature_var = tk.StringVar(value="0.1")
        tk.Entry(gen_row, textvariable=self.temperature_var, width=6).pack(side=tk.LEFT, padx=(2, 12))

        tk.Label(gen_row, text="Timeout (s):").pack(side=tk.LEFT)
        self.request_timeout_var = tk.StringVar(value="30")
        tk.Entry(gen_row, textvariable=self.request_timeout_var, width=5).pack(side=tk.LEFT, padx=(2, 0))

        # --- Confidence Thresholds ---
        thresh_frame = tk.LabelFrame(tab, text="Confidence Thresholds (VLM score \u2192 boolean)", padx=6, pady=4)
        thresh_frame.pack(fill=tk.X, pady=(0, 6))

        # Store threshold vars in a dict for easy access
        self.thresh_vars = {}
        thresholds = [
            ("Phone use", "phone", "0.6"),
            ("Eyes closed", "eyes", "0.5"),
            ("Yawning", "yawning", "0.5"),
            ("Distracted", "distracted", "0.5"),
            ("Eating/drinking", "eating", "0.5"),
            ("Hands off wheel", "hands", "0.6"),
            ("Dangerous posture", "posture", "0.5"),
            ("Child present", "child", "0.4"),
            ("Child slouching", "slouching", "0.5"),
        ]

        # Layout: 3 columns of 3 rows
        for i, (label, key, default) in enumerate(thresholds):
            row_idx = i // 3
            col_idx = i % 3

            if col_idx == 0:
                row = tk.Frame(thresh_frame)
                row.pack(fill=tk.X, pady=1)

            tk.Label(row, text=f"{label}:", width=16, anchor="e").pack(side=tk.LEFT)
            var = tk.StringVar(value=default)
            self.thresh_vars[key] = var
            tk.Entry(row, textvariable=var, width=5).pack(side=tk.LEFT, padx=(2, 10))

        # Hint
        hint = tk.Label(tab, text="Higher threshold = fewer false positives. Range: 0.0 - 1.0",
                        font=("", 10), fg="gray")
        hint.pack(anchor="w", pady=(4, 0))

    def _toggle_vllm_fields(self):
        """Enable/disable fields based on vLLM connection mode."""
        mode = self.vllm_mode_var.get()

        # Connect mode fields
        state_connect = tk.NORMAL if mode == "connect" else tk.DISABLED
        fg_connect = "black" if mode == "connect" else "gray"
        self.vllm_url_entry.config(state=state_connect)
        self.vllm_url_label.config(fg=fg_connect)

        # Start mode fields
        state_start = tk.NORMAL if mode == "start" else tk.DISABLED
        fg_start = "black" if mode == "start" else "gray"
        self.model_path_entry.config(state=state_start)
        self.model_path_label.config(fg=fg_start)
        for child in self.start_frame.winfo_children():
            if isinstance(child, tk.Frame):
                for widget in child.winfo_children():
                    if isinstance(widget, tk.Entry):
                        widget.config(state=state_start)
                    elif isinstance(widget, tk.Label):
                        widget.config(fg=fg_start)

    def _refresh_url(self):
        """Update the displayed URL based on target and host/port settings."""
        if self.target_var.get() == "emulator":
            ip = "10.0.2.2"
        else:
            ip = self.host_var.get().strip() or get_lan_ip()
        port = self.port_var.get().strip() or "8000"
        url = f"http://{ip}:{port}"
        self.url_var.set(url)

    def _auto_detect_ip(self):
        """Re-detect LAN IP and update the host field."""
        ip = get_lan_ip()
        self.host_var.set(ip)
        self._refresh_url()
        self._log(f"Auto-detected IP: {ip}")

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
            self.status_label.config(text="Server: \u25cf Running", fg="green")
            self.start_btn.config(text="\u25a0  STOP SERVER",
                                  highlightbackground="#f44336",
                                  command=self.stop_server)
        else:
            self.status_label.config(text="Server: \u25cf Stopped", fg="red")
            self.start_btn.config(text="\u25b6  START SERVER",
                                  highlightbackground="#4CAF50",
                                  command=self.start_server)
            self.client_label.config(text="")

    def _poll_client_status(self):
        """Poll /api/health every 2s to update client connection indicator."""
        running = self.process is not None and self.process.poll() is None
        if not running:
            self.client_label.config(text="")
            self.root.after(2000, self._poll_client_status)
            return

        port = self.port_var.get().strip() or "8000"
        url = f"http://localhost:{port}/api/health"

        def _check():
            try:
                req = Request(url, method="GET")
                with urlopen(req, timeout=2) as resp:
                    data = json.loads(resp.read().decode())
                connected = data.get("client_connected", False)
                reqs = data.get("client_requests", 0)
                last_seen = data.get("client_last_seen_s")
                ready = data.get("ready", False)
                model = data.get("model", "unknown")

                def _update_label():
                    # Show model + ready state
                    ready_str = f"  [{model}]" if ready else "  [loading...]"
                    if connected:
                        self.client_label.config(
                            text=f"Device: \u25cf Connected  ({reqs} reqs){ready_str}",
                            fg="green")
                    elif last_seen is not None and last_seen >= 0:
                        self.client_label.config(
                            text=f"Device: \u25cf Lost  (last {last_seen:.0f}s ago){ready_str}",
                            fg="orange")
                    else:
                        self.client_label.config(
                            text=f"Device: \u25cb Waiting...{ready_str}",
                            fg="gray")
                self.root.after(0, _update_label)
            except Exception:
                self.root.after(0, lambda: self.client_label.config(
                    text="Device: ? Server error", fg="red"))

            self.root.after(2000, self._poll_client_status)

        threading.Thread(target=_check, daemon=True).start()

    def start_server(self):
        """Start vlm_server.py as a subprocess with all configured parameters."""
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
        fps = self.fps_var.get().strip() or "2.0"
        jpeg_quality = self.jpeg_quality_var.get().strip() or "80"

        cmd = [sys.executable, server_script,
               "--port", port,
               "--camera", camera,
               "--fps", fps,
               "--jpeg-quality", jpeg_quality,
               "--host", "0.0.0.0"]

        mode = self.vllm_mode_var.get()
        if self.scenario_var.get() and mode == "mock":
            cmd.extend(["--mock", "--scenario", "test-all"])
        elif mode == "mock":
            cmd.append("--mock")
        elif mode == "connect":
            vllm_url = self.vllm_url_var.get().strip()
            if not vllm_url:
                self._log("ERROR: vLLM URL is required")
                return
            cmd.extend(["--vllm-url", vllm_url])
        elif mode == "start":
            model_path = self.model_path_var.get().strip()
            if not model_path:
                self._log("ERROR: Model path is required")
                return
            vllm_port = self.vllm_port_var.get().strip() or "8080"
            gpu_mem = self.gpu_mem_var.get().strip() or "0.8"
            max_model_len = self.max_model_len_var.get().strip() or "16384"
            startup_timeout = self.startup_timeout_var.get().strip() or "300"
            cmd.extend([
                "--start-vllm",
                "--model-path", model_path,
                "--vllm-port", vllm_port,
                "--gpu-memory-utilization", gpu_mem,
                "--max-model-len", max_model_len,
                "--vllm-startup-timeout", startup_timeout,
            ])

        # Inference parameters (always pass — they have defaults on server side too)
        if mode in ("connect", "start"):
            max_tokens = self.max_tokens_var.get().strip() or "300"
            temperature = self.temperature_var.get().strip() or "0.1"
            request_timeout = self.request_timeout_var.get().strip() or "30"
            cmd.extend([
                "--max-tokens", max_tokens,
                "--temperature", temperature,
                "--request-timeout", request_timeout,
            ])

            # Confidence thresholds
            thresh_map = {
                "phone": "--thresh-phone",
                "eyes": "--thresh-eyes",
                "yawning": "--thresh-yawning",
                "distracted": "--thresh-distracted",
                "eating": "--thresh-eating",
                "hands": "--thresh-hands",
                "posture": "--thresh-posture",
                "child": "--thresh-child",
                "slouching": "--thresh-slouching",
            }
            for key, flag in thresh_map.items():
                val = self.thresh_vars[key].get().strip()
                if val:
                    cmd.extend([flag, val])

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
