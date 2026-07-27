#!/usr/bin/env python3
"""
GoPro Hero 11 Mini - Windows offload UI
----------------------------------------
A desktop front end for gopro_core: wake the camera over Bluetooth, browse
what is on the card as a thumbnail grid, preview a clip before committing to
the download, and offload the ones you want with the options you want.

Run:
    python gopro_gui.py

Requirements:
    pip install -r requirements.txt
    ffmpeg / ffprobe / ffplay on PATH (ffplay powers the preview window)
"""

import asyncio
import base64
import io
import logging
import queue
import subprocess
import sys
import threading
import tkinter as tk
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import gopro_core as core
import gopro_settings

try:
    from PIL import Image, ImageTk
    HAVE_PILLOW = True
except ImportError:  # Thumbnails degrade to text-only cards.
    HAVE_PILLOW = False

log = logging.getLogger("gopro")

THUMB_W, THUMB_H = 160, 120
CARD_W           = THUMB_W + 36
DEFAULT_OUTPUT   = Path("F:/gopro")

BG        = "#1e1e22"
BG_PANEL  = "#26262c"
BG_CARD   = "#2f2f37"
FG        = "#e8e8ea"
FG_DIM    = "#9a9aa4"
ACCENT    = "#4a9eff"


# ---------------------------------------------------------------------------
# Log plumbing: route core's logger into the GUI's log pane
# ---------------------------------------------------------------------------

def make_photo(data: bytes, max_w: int, max_h: int):
    """
    Turn JPEG bytes into something Tk can draw, scaled to fit a box.

    Uses Pillow when it is installed. Without it, FFmpeg re-encodes the JPEG
    as a PNG, which Tk can display natively - so thumbnails work with only the
    dependencies the transcoding already needs. Returns None if neither is
    available or the bytes won't decode.
    """
    if HAVE_PILLOW:
        try:
            image = Image.open(io.BytesIO(data))
            image.thumbnail((max_w, max_h))
            return ImageTk.PhotoImage(image)
        except Exception:
            return None
    png = core.jpeg_to_png(data, max_w, max_h)
    if png is None:
        return None
    try:
        return tk.PhotoImage(data=base64.b64encode(png).decode("ascii"))
    except Exception:
        return None


def no_image_message() -> str:
    """Explain, once we know an image can't be drawn, what would fix it."""
    if not HAVE_PILLOW and core.find_ffmpeg_tool("ffmpeg") is None:
        return "install Pillow\nor FFmpeg"
    return "no preview"


class QueueLogHandler(logging.Handler):
    def __init__(self, sink) -> None:
        super().__init__()
        self.sink = sink

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.sink(record.levelno, self.format(record))
        except Exception:
            pass


# ---------------------------------------------------------------------------
# One tile in the media grid
# ---------------------------------------------------------------------------

class MediaCard(ttk.Frame):
    def __init__(self, parent, file_info: dict, app: "GoProApp") -> None:
        super().__init__(parent, style="Card.TFrame", padding=6)
        self.file_info = file_info
        self.app       = app
        self.selected  = tk.BooleanVar(value=file_info["kind"] == "video")
        self._photo    = None  # keep a reference or Tk garbage-collects it
        self.thumb_bytes: bytes | None = None

        self.thumb = tk.Label(
            self, width=THUMB_W, height=THUMB_H,
            bg=BG_CARD, fg=FG_DIM, text="loading...", compound="center",
        )
        self.thumb.pack()

        name = file_info["name"]
        ttk.Label(self, text=name, style="CardName.TLabel").pack(anchor="w", pady=(4, 0))

        meta = core.human_size(file_info["size"])
        if file_info["kind"] == "video":
            meta += f"   {core.human_duration(file_info['duration'])}"
        ttk.Label(self, text=meta, style="CardMeta.TLabel").pack(anchor="w")

        row = ttk.Frame(self, style="Card.TFrame")
        row.pack(fill="x", pady=(4, 0))
        ttk.Checkbutton(
            row, text="Select", variable=self.selected, style="Card.TCheckbutton",
            command=app.update_selection_summary,
        ).pack(side="left")
        if file_info["kind"] == "video":
            ttk.Button(
                row, text="Preview", width=8, style="Small.TButton",
                command=lambda: app.preview(file_info),
            ).pack(side="right")

        for widget in (self, self.thumb):
            widget.bind("<Button-1>", lambda e: app.show_details(file_info))
            widget.bind("<Double-Button-1>", lambda e: app.preview(file_info))

    def set_thumbnail(self, data: bytes | None) -> None:
        self.thumb_bytes = data
        photo = make_photo(data, THUMB_W, THUMB_H) if data else None
        if photo is None:
            self.thumb.configure(text=no_image_message(), image="")
            return
        self._photo = photo
        self.thumb.configure(image=self._photo, text="")


# ---------------------------------------------------------------------------
# Main window
# ---------------------------------------------------------------------------

class GoProApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        root.title("GoPro Unloader")
        root.geometry("1180x780")
        root.minsize(900, 600)
        root.configure(bg=BG)

        self._ui_queue: queue.Queue = queue.Queue()
        # One worker on purpose: the camera's HTTP server is easily overwhelmed
        # by parallel requests and starts refusing them, which shows up as
        # thumbnails that never load.
        self._thumb_pool = ThreadPoolExecutor(max_workers=1)
        self._cancel     = threading.Event()
        self._busy       = False
        self._keepalive  = core.KeepAlive()
        self._preview_procs: list[subprocess.Popen] = []

        self.files: list[dict] = []
        self.cards: list[MediaCard] = []
        self.ssid: str | None = None
        self.password: str | None = None
        self.detail_photo = None
        self._conn_label = "Connected"
        self._auto_join_enabled = False

        saved = gopro_settings.load()
        self.output_dir        = tk.StringVar(value=saved["output_dir"])
        self.opt_transcode     = tk.BooleanVar(value=saved["transcode"])
        self.opt_delete        = tk.BooleanVar(value=saved["delete_from_cam"])
        self.opt_keep_raw      = tk.BooleanVar(value=saved["keep_originals"])
        self.opt_skip_existing = tk.BooleanVar(value=saved["skip_existing"])
        self.opt_auto_join     = tk.BooleanVar(value=saved["auto_join_wifi"])
        self.status_text  = tk.StringVar(value="Not connected")
        self.progress_text = tk.StringVar(value="")
        self.selection_text = tk.StringVar(value="Nothing selected")

        self._build_styles()
        self._build_layout()
        self._install_log_handler()
        self._watch_settings()

        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(50, self._pump)

        if not HAVE_PILLOW:
            if core.find_ffmpeg_tool("ffmpeg") is not None:
                log.info("Pillow isn't installed - drawing thumbnails with FFmpeg "
                         "instead. 'pip install Pillow' makes them faster.")
            else:
                log.warning("Neither Pillow nor FFmpeg is available, so thumbnails "
                            "can't be drawn. Run: pip install -r requirements.txt")

    # -- styling ----------------------------------------------------------

    def _build_styles(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background=BG)
        style.configure("Panel.TFrame", background=BG_PANEL)
        style.configure("Card.TFrame", background=BG_CARD, relief="flat")
        style.configure("TLabel", background=BG, foreground=FG)
        style.configure("Panel.TLabel", background=BG_PANEL, foreground=FG)
        style.configure("Dim.TLabel", background=BG_PANEL, foreground=FG_DIM)
        style.configure("Heading.TLabel", background=BG_PANEL, foreground=FG,
                        font=("Segoe UI", 10, "bold"))
        style.configure("Status.TLabel", background=BG, foreground=ACCENT,
                        font=("Segoe UI", 10, "bold"))
        style.configure("CardName.TLabel", background=BG_CARD, foreground=FG,
                        font=("Segoe UI", 8, "bold"))
        style.configure("CardMeta.TLabel", background=BG_CARD, foreground=FG_DIM,
                        font=("Segoe UI", 8))
        style.configure("TCheckbutton", background=BG_PANEL, foreground=FG)
        style.map("TCheckbutton", background=[("active", BG_PANEL)])
        style.configure("Card.TCheckbutton", background=BG_CARD, foreground=FG_DIM,
                        font=("Segoe UI", 8))
        style.map("Card.TCheckbutton", background=[("active", BG_CARD)])
        style.configure("Small.TButton", font=("Segoe UI", 8), padding=2)
        style.configure("Accent.TButton", font=("Segoe UI", 9, "bold"), padding=6)
        style.configure("TEntry", fieldbackground=BG_CARD, foreground=FG,
                        insertcolor=FG, bordercolor=BG_CARD)
        style.configure("TProgressbar", background=ACCENT, troughcolor=BG_CARD,
                        bordercolor=BG_CARD, lightcolor=ACCENT, darkcolor=ACCENT)
        style.configure("TSeparator", background=BG_CARD)

    # -- layout -----------------------------------------------------------

    def _build_layout(self) -> None:
        self._build_connection_bar()

        body = ttk.Frame(self.root)
        body.pack(fill="both", expand=True, padx=10, pady=(0, 6))

        self._build_grid(body)
        self._build_sidebar(body)
        self._build_footer()

    def _build_connection_bar(self) -> None:
        bar = ttk.Frame(self.root, padding=(10, 8))
        bar.pack(fill="x")

        self.btn_connect = ttk.Button(
            bar, text="Connect via Bluetooth", style="Accent.TButton",
            command=self.connect_ble,
        )
        self.btn_connect.pack(side="left")

        self.btn_already = ttk.Button(
            bar, text="Already on Wi-Fi", command=self.connect_wifi_only,
        )
        self.btn_already.pack(side="left", padx=6)

        self.btn_refresh = ttk.Button(
            bar, text="Refresh", command=self.refresh_media, state="disabled",
        )
        self.btn_refresh.pack(side="left")

        ttk.Label(bar, textvariable=self.status_text, style="Status.TLabel").pack(
            side="right"
        )

        # Credentials banner, shown only while a Wi-Fi switch is pending.
        self.wifi_bar = ttk.Frame(self.root, style="Panel.TFrame", padding=(10, 8))
        self.wifi_creds = ttk.Label(self.wifi_bar, text="", style="Panel.TLabel")
        self.wifi_creds.pack(side="left")
        ttk.Button(
            self.wifi_bar, text="Copy password", command=self.copy_password,
        ).pack(side="right")
        self.btn_autojoin = ttk.Button(
            self.wifi_bar, text="Join automatically", command=self.auto_join_wifi,
        )
        self.btn_autojoin.pack(side="right", padx=6)

    def _build_grid(self, parent) -> None:
        wrapper = ttk.Frame(parent)
        wrapper.pack(side="left", fill="both", expand=True)

        header = ttk.Frame(wrapper)
        header.pack(fill="x", pady=(0, 4))
        ttk.Label(header, text="On the camera", style="Heading.TLabel").pack(side="left")
        ttk.Button(header, text="None", width=6, style="Small.TButton",
                   command=lambda: self.select_all(False)).pack(side="right")
        ttk.Button(header, text="All", width=6, style="Small.TButton",
                   command=lambda: self.select_all(True)).pack(side="right", padx=4)
        ttk.Label(header, textvariable=self.selection_text, style="Dim.TLabel").pack(
            side="right", padx=10
        )

        self.canvas = tk.Canvas(wrapper, bg=BG, highlightthickness=0)
        scroll = ttk.Scrollbar(wrapper, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)

        self.grid_frame = ttk.Frame(self.canvas)
        self.canvas.create_window((0, 0), window=self.grid_frame, anchor="nw",
                                  tags="grid")
        self.grid_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")),
        )
        self.canvas.bind("<Configure>", self._on_canvas_resize)
        self.canvas.bind_all("<MouseWheel>", self._on_mousewheel)

        self.empty_label = ttk.Label(
            self.grid_frame,
            text="Connect to the camera to see what's on the card.",
            style="Dim.TLabel",
        )
        self.empty_label.grid(row=0, column=0, padx=20, pady=20)

    def _build_sidebar(self, parent) -> None:
        side = ttk.Frame(parent, style="Panel.TFrame", padding=12, width=330)
        side.pack(side="right", fill="y", padx=(10, 0))
        side.pack_propagate(False)

        ttk.Label(side, text="Preview", style="Heading.TLabel").pack(anchor="w")
        self.detail_thumb = tk.Label(
            side, bg=BG_CARD, fg=FG_DIM, width=260, height=160,
            text="Select a file", compound="center",
        )
        self.detail_thumb.pack(fill="x", pady=(6, 4))
        self.detail_name = ttk.Label(side, text="", style="Panel.TLabel",
                                     wraplength=260, justify="left")
        self.detail_name.pack(anchor="w")
        self.detail_meta = ttk.Label(side, text="", style="Dim.TLabel",
                                     wraplength=260, justify="left")
        self.detail_meta.pack(anchor="w", pady=(0, 6))
        self.btn_preview = ttk.Button(
            side, text="Play preview", state="disabled", command=self.preview_current,
        )
        self.btn_preview.pack(fill="x")

        ttk.Separator(side, orient="horizontal").pack(fill="x", pady=12)

        opt_head = ttk.Frame(side, style="Panel.TFrame")
        opt_head.pack(fill="x")
        ttk.Label(opt_head, text="Options", style="Heading.TLabel").pack(side="left")
        ttk.Label(opt_head, text="remembered", style="Dim.TLabel").pack(side="right")
        ttk.Label(side, text="Copy files to", style="Dim.TLabel").pack(
            anchor="w", pady=(6, 2)
        )
        dest_row = ttk.Frame(side, style="Panel.TFrame")
        dest_row.pack(fill="x")
        ttk.Entry(dest_row, textvariable=self.output_dir).pack(
            side="left", fill="x", expand=True
        )
        ttk.Button(dest_row, text="...", width=3, command=self.choose_output_dir).pack(
            side="right", padx=(4, 0)
        )

        ttk.Checkbutton(side, text="Transcode to 1080p", variable=self.opt_transcode,
                        command=self._sync_option_states).pack(anchor="w", pady=(10, 0))
        self.chk_keep = ttk.Checkbutton(
            side, text="Also keep the full-size original",
            variable=self.opt_keep_raw,
        )
        self.chk_keep.pack(anchor="w", padx=(18, 0))
        ttk.Checkbutton(side, text="Delete from camera after copying",
                        variable=self.opt_delete).pack(anchor="w", pady=(6, 0))
        ttk.Checkbutton(side, text="Skip files already downloaded",
                        variable=self.opt_skip_existing).pack(anchor="w", pady=(6, 0))

        label = "Join camera Wi-Fi automatically"
        if sys.platform != "win32":
            label += " (Windows)"
        self.chk_auto_join = ttk.Checkbutton(
            side, text=label, variable=self.opt_auto_join,
        )
        self.chk_auto_join.pack(anchor="w", pady=(6, 0))
        if sys.platform != "win32":
            self.chk_auto_join.configure(state="disabled")

        self._sync_option_states()

    def _build_footer(self) -> None:
        footer = ttk.Frame(self.root, padding=(10, 0))
        footer.pack(fill="x")

        self.progress = ttk.Progressbar(footer, mode="determinate", maximum=100)
        self.progress.pack(side="left", fill="x", expand=True)
        ttk.Label(footer, textvariable=self.progress_text, style="TLabel").pack(
            side="left", padx=10
        )
        self.btn_stop = ttk.Button(footer, text="Stop", state="disabled",
                                   command=self.cancel)
        self.btn_stop.pack(side="right")
        self.btn_download = ttk.Button(
            footer, text="Download selected", style="Accent.TButton",
            state="disabled", command=self.start_transfer,
        )
        self.btn_download.pack(side="right", padx=6)

        self.log_view = tk.Text(
            self.root, height=7, bg="#141417", fg=FG_DIM, insertbackground=FG,
            relief="flat", wrap="word", font=("Consolas", 9),
        )
        self.log_view.pack(fill="x", padx=10, pady=(6, 10))
        self.log_view.tag_configure("error", foreground="#ff6b6b")
        self.log_view.tag_configure("warn", foreground="#ffd166")
        self.log_view.configure(state="disabled")

    def _install_log_handler(self) -> None:
        handler = QueueLogHandler(
            lambda level, msg: self._post(self._append_log, level, msg)
        )
        handler.setFormatter(logging.Formatter("%(asctime)s  %(message)s", "%H:%M:%S"))
        log.addHandler(handler)
        log.setLevel(logging.INFO)

    # -- thread / UI plumbing ---------------------------------------------

    def _post(self, fn, *args) -> None:
        """Queue a callable to run on the Tk main thread."""
        self._ui_queue.put((fn, args))

    def _pump(self) -> None:
        while True:
            try:
                fn, args = self._ui_queue.get_nowait()
            except queue.Empty:
                break
            try:
                fn(*args)
            except Exception:
                log.exception("UI update failed")
        self.root.after(50, self._pump)

    def _run_worker(self, fn, *args) -> None:
        threading.Thread(target=fn, args=args, daemon=True).start()

    def _append_log(self, level: int, msg: str) -> None:
        tag = "error" if level >= logging.ERROR else "warn" if level >= logging.WARNING else ""
        self.log_view.configure(state="normal")
        self.log_view.insert("end", msg + "\n", tag)
        self.log_view.see("end")
        self.log_view.configure(state="disabled")

    def _set_status(self, text: str) -> None:
        self.status_text.set(text)

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        state = "disabled" if busy else "normal"
        for btn in (self.btn_connect, self.btn_already, self.btn_refresh):
            btn.configure(state=state)
        self.btn_download.configure(
            state="disabled" if busy or not self.files else "normal"
        )
        self.btn_stop.configure(state="normal" if busy else "disabled")
        if not busy:
            self.btn_refresh.configure(state="normal" if self.files else "disabled")

    def _set_progress(self, fraction: float, text: str = "") -> None:
        self.progress["value"] = max(0.0, min(1.0, fraction)) * 100
        self.progress_text.set(text)

    # -- connection -------------------------------------------------------

    def connect_ble(self) -> None:
        if self._busy:
            return
        try:
            import bleak  # noqa: F401
        except ImportError:
            messagebox.showerror(
                "Bluetooth unavailable",
                "The 'bleak' package is not installed.\n\n"
                "Run:  pip install -r requirements.txt\n\n"
                "Or turn the camera's Wi-Fi on yourself and click "
                "'Already on Wi-Fi'.",
            )
            return
        self._cancel.clear()
        self._set_busy(True)
        self._set_status("Waking camera over Bluetooth...")
        # Read the Tk variable here: the worker thread must not touch it.
        self._auto_join_enabled = (
            self.opt_auto_join.get() and sys.platform == "win32"
        )
        self._run_worker(self._ble_worker)

    def _ble_worker(self) -> None:
        try:
            ssid, password = asyncio.run(
                core.ble_enable_wifi(status_cb=lambda m: self._post(self._set_status, m.strip()))
            )
        except Exception as e:
            log.error("Bluetooth wake failed: %s", e)
            self._post(self._set_status, "Bluetooth wake failed")
            self._post(self._set_busy, False)
            return

        if ssid is None:
            self._post(self._set_status, "No GoPro found over Bluetooth")
            self._post(self._set_busy, False)
            return

        self.ssid, self.password = ssid, password
        self._post(self._show_wifi_banner, ssid, password)
        self._post(self._set_status, f"Waiting for Wi-Fi: {ssid}")
        if self._auto_join_enabled:
            log.info("Joining %s automatically...", ssid)
            self._auto_join_worker(ssid, password)
        self._run_worker(self._wait_for_wifi_worker)

    def _wait_for_wifi_worker(self) -> None:
        ok = core.wait_until_reachable(timeout=180, cancel_event=self._cancel)
        if not ok:
            self._post(self._set_status, "Timed out waiting for Wi-Fi")
            self._post(self._set_busy, False)
            return
        self._post(self._hide_wifi_banner)
        self._on_connected()

    def connect_wifi_only(self) -> None:
        if self._busy:
            return
        self._cancel.clear()
        self._set_busy(True)
        self._set_status("Looking for the camera on Wi-Fi...")
        self._run_worker(self._wifi_only_worker)

    def _wifi_only_worker(self) -> None:
        if not core.wait_until_reachable(timeout=10, cancel_event=self._cancel):
            log.error("Cannot reach the GoPro at 10.5.5.9.")
            log.error("  Connect this PC's Wi-Fi to the camera's hotspot first.")
            self._post(self._set_status, "Camera not reachable")
            self._post(self._set_busy, False)
            return
        self._on_connected()

    def _on_connected(self) -> None:
        """Shared tail of both connection paths, called from a worker thread."""
        self._keepalive.start()
        core.reset_thumbnail_strategy()
        battery = core.get_battery_percent()
        self._conn_label = (
            "Connected" + (f"   Battery {battery}%" if battery is not None else "")
        )
        self._post(self._set_status, self._conn_label)
        log.info("Connected to camera. %s", f"Battery {battery}%." if battery else "")
        self._load_media()

    def _show_wifi_banner(self, ssid: str, password: str) -> None:
        self.wifi_creds.configure(
            text=f"Connect this PC to Wi-Fi   Network: {ssid}    Password: {password}"
        )
        self.wifi_bar.pack(fill="x", padx=10, pady=(0, 6))
        self.btn_autojoin.configure(
            state="normal" if sys.platform == "win32" else "disabled"
        )

    def _hide_wifi_banner(self) -> None:
        self.wifi_bar.pack_forget()

    def copy_password(self) -> None:
        if not self.password:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(self.password)
        self._set_status("Password copied to clipboard")

    def auto_join_wifi(self) -> None:
        if not (self.ssid and self.password):
            return
        self.btn_autojoin.configure(state="disabled")
        self._run_worker(self._auto_join_worker, self.ssid, self.password)

    def _auto_join_worker(self, ssid: str, password: str) -> None:
        ok, message = core.wifi_connect_windows(ssid, password)
        if ok:
            log.info(message)
        else:
            log.warning("%s Connect to '%s' from the Windows Wi-Fi menu.", message, ssid)
            self._post(lambda: self.btn_autojoin.configure(state="normal"))

    # -- media ------------------------------------------------------------

    def refresh_media(self) -> None:
        if self._busy:
            return
        self._set_busy(True)
        self._run_worker(self._load_media)

    def _load_media(self) -> None:
        """Fetch the media list and rebuild the grid. Runs on a worker thread."""
        self._post(self._set_status, "Reading the card...")
        files = core.get_media_list(include_proxies=True)
        visible = [f for f in files if f["kind"] in ("video", "photo")]
        self.files = files
        self._post(self._rebuild_grid, visible)
        self._post(self._set_status, self._conn_label)
        self._post(self._set_busy, False)
        if visible:
            log.info("Found %d file(s) on the camera.", len(visible))
        else:
            log.info("No media on the camera.")

    def _rebuild_grid(self, visible: list[dict]) -> None:
        for card in self.cards:
            card.destroy()
        self.cards.clear()
        self.empty_label.grid_forget()

        if not visible:
            self.empty_label.configure(text="Nothing on the camera's card.")
            self.empty_label.grid(row=0, column=0, padx=20, pady=20)
            self.update_selection_summary()
            return

        for f in visible:
            card = MediaCard(self.grid_frame, f, self)
            self.cards.append(card)
            self._thumb_pool.submit(self._fetch_thumb, card, f)

        self._relayout_grid()
        self.update_selection_summary()
        self.btn_download.configure(state="normal")
        self.btn_refresh.configure(state="normal")

    def _fetch_thumb(self, card: MediaCard, file_info: dict) -> None:
        data = core.get_thumbnail(file_info)
        self._post(self._apply_thumb, card, data)

    def _apply_thumb(self, card: MediaCard, data: bytes | None) -> None:
        if card.winfo_exists():
            card.set_thumbnail(data)

    def _relayout_grid(self) -> None:
        width   = max(self.canvas.winfo_width(), CARD_W)
        columns = max(1, width // (CARD_W + 10))
        for index, card in enumerate(self.cards):
            card.grid(row=index // columns, column=index % columns, padx=5, pady=5,
                      sticky="n")

    def _on_canvas_resize(self, event) -> None:
        self.canvas.itemconfigure("grid", width=event.width)
        if self.cards:
            self._relayout_grid()

    def _on_mousewheel(self, event) -> None:
        self.canvas.yview_scroll(int(-event.delta / 120), "units")

    def select_all(self, value: bool) -> None:
        for card in self.cards:
            card.selected.set(value)
        self.update_selection_summary()

    def selected_files(self) -> list[dict]:
        return [c.file_info for c in self.cards if c.selected.get()]

    def update_selection_summary(self) -> None:
        chosen = self.selected_files()
        if not chosen:
            self.selection_text.set("Nothing selected")
            return
        total = sum(f["size"] for f in chosen)
        self.selection_text.set(f"{len(chosen)} selected  ({core.human_size(total)})")

    # -- preview ----------------------------------------------------------

    def show_details(self, file_info: dict) -> None:
        self.current = file_info
        self.detail_name.configure(text=file_info["name"])
        meta = [core.human_size(file_info["size"])]
        if file_info["kind"] == "video":
            meta.append(core.human_duration(file_info["duration"]))
            meta.append("proxy available" if file_info["proxy_url"] else "full-res only")
        self.detail_meta.configure(text="   ".join(meta))
        self.btn_preview.configure(
            state="normal" if file_info["kind"] == "video" else "disabled"
        )

        # Show the grid thumbnail straight away so the pane never sits empty,
        # then queue the sharper screennail behind whatever else is loading.
        card = next((c for c in self.cards if c.file_info is file_info), None)
        if card is not None and card.thumb_bytes:
            self._render_detail(card.thumb_bytes)
        else:
            self.detail_photo = None
            self.detail_thumb.configure(text="loading...", image="")
        self._thumb_pool.submit(self._fetch_detail_thumb, file_info)

    def _fetch_detail_thumb(self, file_info: dict) -> None:
        data = core.get_thumbnail(file_info, large=True)
        self._post(self._apply_detail_thumb, file_info, data)

    def _apply_detail_thumb(self, file_info: dict, data: bytes | None) -> None:
        if getattr(self, "current", None) is not file_info:
            return  # selection moved on while we were fetching
        if data is None:
            # Leave whatever the grid thumbnail already put there.
            if self.detail_photo is None:
                self.detail_thumb.configure(text="no preview available", image="")
            return
        self._render_detail(data)

    def _render_detail(self, data: bytes) -> None:
        photo = make_photo(data, 260, 180)
        if photo is None:
            self.detail_photo = None
            self.detail_thumb.configure(text=no_image_message(), image="")
            return
        self.detail_photo = photo
        self.detail_thumb.configure(image=self.detail_photo, text="")

    def preview_current(self) -> None:
        current = getattr(self, "current", None)
        if current is not None:
            self.preview(current)

    def preview(self, file_info: dict) -> None:
        """Stream the clip from the camera in an ffplay window - no download."""
        if file_info["kind"] != "video":
            return
        if not core.is_reachable():
            messagebox.showwarning(
                "Camera not connected",
                "The camera isn't reachable, so there's nothing to stream.",
            )
            return
        url = core.preview_url(file_info)
        proxy = file_info["proxy_url"] is not None
        try:
            proc = core.launch_preview(url, f"Preview - {file_info['name']}")
        except RuntimeError as e:
            messagebox.showerror("Preview unavailable", str(e))
            return
        self._preview_procs = [p for p in self._preview_procs if p.poll() is None]
        self._preview_procs.append(proc)
        log.info(
            "Previewing %s (%s)", file_info["name"],
            "low-res proxy" if proxy else "full resolution - may buffer",
        )

    # -- options ----------------------------------------------------------

    def _sync_option_states(self) -> None:
        self.chk_keep.configure(
            state="normal" if self.opt_transcode.get() else "disabled"
        )

    # -- persisted preferences --------------------------------------------

    def _settings_values(self) -> dict:
        return {
            "output_dir"     : self.output_dir.get(),
            "transcode"      : self.opt_transcode.get(),
            "keep_originals" : self.opt_keep_raw.get(),
            "delete_from_cam": self.opt_delete.get(),
            "skip_existing"  : self.opt_skip_existing.get(),
            "auto_join_wifi" : self.opt_auto_join.get(),
        }

    def _watch_settings(self) -> None:
        """Save preferences whenever one of them changes."""
        for var in (self.output_dir, self.opt_transcode, self.opt_delete,
                    self.opt_keep_raw, self.opt_skip_existing, self.opt_auto_join):
            var.trace_add("write", lambda *_: self.save_settings())

    def save_settings(self) -> None:
        gopro_settings.save(self._settings_values())

    def _snapshot_options(self) -> dict:
        """
        Read the option widgets into a plain dict on the main thread.

        Tk variables can only be touched from the thread running the event
        loop, and it also means toggling a checkbox mid-transfer can't change
        the rules half way through a run.
        """
        return {
            "transcode"    : self.opt_transcode.get(),
            "delete"       : self.opt_delete.get(),
            "keep_raw"     : self.opt_keep_raw.get(),
            "skip_existing": self.opt_skip_existing.get(),
        }

    def choose_output_dir(self) -> None:
        chosen = filedialog.askdirectory(
            title="Where should files be copied?",
            initialdir=self.output_dir.get() or str(Path.home()),
        )
        if chosen:
            self.output_dir.set(chosen)

    # -- transfer ---------------------------------------------------------

    def start_transfer(self) -> None:
        if self._busy:
            return
        chosen = self.selected_files()
        if not chosen:
            messagebox.showinfo("Nothing selected", "Tick at least one file to copy.")
            return

        out_root = Path(self.output_dir.get()).expanduser()
        try:
            out_root.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            messagebox.showerror("Bad destination", f"Cannot use that folder:\n{e}")
            return

        opts = self._snapshot_options()

        if opts["transcode"]:
            try:
                core.check_ffmpeg()
            except RuntimeError as e:
                messagebox.showerror("FFmpeg missing", str(e))
                return

        if opts["delete"]:
            if not messagebox.askyesno(
                "Delete from camera?",
                f"{len(chosen)} file(s) will be copied and then DELETED from the "
                "camera.\n\nOnly files that download successfully are deleted.\n\n"
                "Continue?",
            ):
                return

        self._cancel.clear()
        self._set_busy(True)
        self._run_worker(self._transfer_worker, chosen, out_root, opts)

    def _transfer_worker(self, chosen: list[dict], out_root: Path,
                         opts: dict) -> None:
        raw_dir        = out_root / "raw"
        transcoded_dir = out_root / "transcoded"
        raw_dir.mkdir(parents=True, exist_ok=True)

        counts = {"downloaded": 0, "skipped": 0, "error": 0}
        done: list[tuple[dict, Path]] = []
        transcode_errors: list[str] = []
        total = len(chosen)
        self._keepalive.start()

        try:
            # 1. Download
            for index, f in enumerate(chosen):
                if self._cancel.is_set():
                    break
                dest = raw_dir / f["name"]
                if not opts["skip_existing"] and dest.exists():
                    dest.unlink()

                label = f"{index + 1}/{total}  {f['name']}"

                def progress(bytes_done: int, bytes_total: int, label=label,
                             index=index) -> None:
                    file_fraction = bytes_done / bytes_total if bytes_total else 0
                    overall = (index + file_fraction) / total
                    self._post(
                        self._set_progress, overall,
                        f"{label}  -  {core.human_size(bytes_done)}"
                        f" / {core.human_size(bytes_total)}",
                    )

                result = core.download_file(f, dest, progress_cb=progress,
                                            cancel_event=self._cancel)
                counts[result] += 1
                if result == "downloaded":
                    done.append((f, dest))
                    log.info("Downloaded %s", f["name"])
                elif result == "skipped":
                    log.info("%s already downloaded, skipping", f["name"])

            # 2. Delete from camera
            if opts["delete"] and done and not self._cancel.is_set():
                self._post(self._set_progress, 1.0, "Deleting from camera...")
                for f, _ in done:
                    targets = [f] + core.sibling_files(f, self.files)
                    if all(core.delete_file(t) for t in targets):
                        log.info("Deleted %s from the camera", f["name"])
                    else:
                        log.warning("Could not fully delete %s from the camera",
                                    f["name"])

            # 3. Transcode
            if opts["transcode"] and not self._cancel.is_set():
                videos = [(f, p) for f, p in done if f["kind"] == "video"]
                if videos:
                    transcoded_dir.mkdir(parents=True, exist_ok=True)
                    for index, (f, src) in enumerate(videos):
                        if self._cancel.is_set():
                            break
                        label = f"Transcoding {index + 1}/{len(videos)}  {src.name}"

                        def progress(seconds: float, duration: float | None,
                                     label=label, index=index) -> None:
                            frac = (seconds / duration) if duration else 0
                            overall = (index + min(frac, 1.0)) / len(videos)
                            self._post(self._set_progress, overall,
                                       f"{label}  -  {int(frac * 100)}%")

                        result = core.transcode_to_1080p(
                            src, transcoded_dir / src.name,
                            progress_cb=progress, cancel_event=self._cancel,
                        )
                        if result == "transcoded":
                            if not opts["keep_raw"]:
                                src.unlink(missing_ok=True)
                        elif result == "error":
                            transcode_errors.append(src.name)

            if self._cancel.is_set():
                log.warning("Stopped. %d file(s) copied before cancelling.",
                            counts["downloaded"])
                self._post(self._set_progress, 0, "Stopped")
            else:
                log.info(
                    "Finished. %d downloaded, %d skipped, %d error(s).",
                    counts["downloaded"], counts["skipped"], counts["error"],
                )
                if transcode_errors:
                    log.warning("Transcode failed for: %s", ", ".join(transcode_errors))
                self._post(self._set_progress, 1.0, "Done")

        except core.Cancelled:
            log.warning("Stopped.")
            self._post(self._set_progress, 0, "Stopped")
        except Exception as e:
            log.exception("Transfer failed: %s", e)
            self._post(self._set_progress, 0, "Failed")
        finally:
            self._post(self._set_busy, False)
            if done and opts["delete"]:
                self._run_worker(self._load_media)

    def cancel(self) -> None:
        self._cancel.set()
        self.progress_text.set("Stopping...")

    # -- shutdown ---------------------------------------------------------

    def on_close(self) -> None:
        if self._busy and not messagebox.askyesno(
            "Still working", "A transfer is running. Quit anyway?"
        ):
            return
        self.save_settings()
        self._cancel.set()
        for proc in self._preview_procs:
            if proc.poll() is None:
                proc.terminate()
        self._keepalive.stop()
        self._thumb_pool.shutdown(wait=False)
        self.root.destroy()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s",
                        datefmt="%H:%M:%S")
    root = tk.Tk()
    GoProApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
