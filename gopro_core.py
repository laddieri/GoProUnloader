#!/usr/bin/env python3
"""
GoPro Hero 11 Mini - shared camera logic
----------------------------------------
Transport and media handling used by both the command-line tool
(gopro_unloader.py) and the Windows GUI (gopro_gui.py).

Nothing in here touches the terminal or a GUI toolkit: progress is reported
through optional callbacks so each front end can render it however it likes.
"""

import asyncio
import logging
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import requests

log = logging.getLogger("gopro")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
GOPRO_BASE            = "http://10.5.5.9:8080"
MEDIA_LIST_URL        = f"{GOPRO_BASE}/gopro/media/list"
MEDIA_DELETE_URL      = f"{GOPRO_BASE}/gopro/media/delete/file"
MEDIA_BASE_URL        = f"{GOPRO_BASE}/videos/DCIM"
MEDIA_THUMBNAIL_URL   = f"{GOPRO_BASE}/gopro/media/thumbnail"
MEDIA_SCREENNAIL_URL  = f"{GOPRO_BASE}/gopro/media/screennail"
CAMERA_STATE_URL      = f"{GOPRO_BASE}/gopro/camera/state"
KEEP_ALIVE_URL        = f"{GOPRO_BASE}/gopro/camera/keep_alive"

# BLE UUIDs (OpenGoPro spec)
CMD_REQ_UUID          = "b5f90072-aa8d-11e3-9046-0002a5d5c51b"
CMD_RSP_UUID          = "b5f90073-aa8d-11e3-9046-0002a5d5c51b"
WIFI_AP_SSID_UUID     = "b5f90002-aa8d-11e3-9046-0002a5d5c51b"
WIFI_AP_PASSWORD_UUID = "b5f90003-aa8d-11e3-9046-0002a5d5c51b"
ENABLE_WIFI_CMD       = bytes([0x03, 0x17, 0x01, 0x01])

HTTP_TIMEOUT   = 30       # seconds
DOWNLOAD_CHUNK = 65536    # bytes

VIDEO_EXTS = (".MP4",)
PHOTO_EXTS = (".JPG",)
PROXY_EXTS = (".LRV", ".THM")


class Cancelled(Exception):
    """Raised inside a worker when the caller sets its cancel event."""


def _check_cancel(cancel_event) -> None:
    if cancel_event is not None and cancel_event.is_set():
        raise Cancelled()


# ---------------------------------------------------------------------------
# FFmpeg helpers
# ---------------------------------------------------------------------------

def find_ffmpeg_tool(tool: str) -> str | None:
    """Return the full path to an FFmpeg tool, or None if it isn't on PATH."""
    return shutil.which(tool)


def check_ffmpeg() -> None:
    """Raise if ffmpeg or ffprobe are not found on PATH."""
    for tool in ("ffmpeg", "ffprobe"):
        if find_ffmpeg_tool(tool) is None:
            raise RuntimeError(
                f"{tool} not found on PATH. "
                "Install FFmpeg from https://ffmpeg.org/download.html "
                "and make sure it is added to PATH."
            )


def _no_window_kwargs() -> dict:
    """Keep subprocesses from flashing a console window on Windows."""
    if sys.platform == "win32":
        return {"creationflags": subprocess.CREATE_NO_WINDOW}
    return {}


def get_video_height(filepath: Path) -> int | None:
    """Use ffprobe to get the video height in pixels."""
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=height",
                "-of", "csv=p=0",
                str(filepath),
            ],
            capture_output=True, text=True, timeout=15, **_no_window_kwargs(),
        )
        h = result.stdout.strip()
        return int(h) if h.isdigit() else None
    except Exception:
        return None


def get_video_duration(filepath: Path) -> float | None:
    """Use ffprobe to get the video duration in seconds."""
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                str(filepath),
            ],
            capture_output=True, text=True, timeout=15, **_no_window_kwargs(),
        )
        val = result.stdout.strip()
        return float(val) if val else None
    except Exception:
        return None


def transcode_to_1080p(
    src: Path,
    dest: Path,
    progress_cb=None,
    cancel_event: threading.Event | None = None,
) -> str:
    """
    Transcode src to 1080p H.264/AAC and write to dest.

    Preserves aspect ratio with letterbox/pillarbox padding. Skips if the
    source is already <=1080p or dest already exists.

    progress_cb(seconds_done, total_seconds_or_None) is called as encoding
    advances. Returns 'transcoded', 'skipped', or 'error' - a skip is a
    normal outcome and callers should not report it as a failure.
    """
    if dest.exists():
        log.info("  %s - 1080p copy already exists, skipping", src.name)
        return "skipped"

    height = get_video_height(src)
    if height is None:
        log.warning("  %s - could not read resolution, skipping transcode", src.name)
        return "error"
    if height <= 1080:
        log.info("  %s - already %dp, skipping transcode", src.name, height)
        return "skipped"

    duration = get_video_duration(src)
    log.info("  %s - transcoding %dp -> 1080p...", src.name, height)

    process = None
    try:
        process = subprocess.Popen(
            [
                "ffmpeg", "-y",
                "-i", str(src),
                "-vf", (
                    "scale=1920:1080:force_original_aspect_ratio=decrease,"
                    "pad=1920:1080:(ow-iw)/2:(oh-ih)/2"
                ),
                "-c:v", "libx264", "-preset", "fast", "-crf", "23",
                "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart",
                "-progress", "pipe:1",
                "-nostats",
                str(dest),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            **_no_window_kwargs(),
        )

        for line in process.stdout:
            if cancel_event is not None and cancel_event.is_set():
                process.terminate()
                process.wait(timeout=10)
                if dest.exists():
                    dest.unlink()
                raise Cancelled()
            if line.startswith("out_time_ms=") and progress_cb is not None:
                try:
                    seconds = int(line.split("=")[1]) / 1_000_000
                except ValueError:
                    continue
                progress_cb(seconds, duration)

        process.wait()
        if process.returncode == 0:
            size_mb = dest.stat().st_size / (1024 * 1024)
            log.info("  %s - 1080p copy saved (%.1f MB)", src.name, size_mb)
            return "transcoded"

        log.error("  %s - transcode failed", src.name)
        if dest.exists():
            dest.unlink()
        return "error"

    except Cancelled:
        raise
    except FileNotFoundError:
        log.error("FFmpeg not found. Install it from https://ffmpeg.org/download.html")
        return "error"
    finally:
        if process is not None and process.stdout is not None:
            process.stdout.close()


def launch_preview(url: str, title: str) -> subprocess.Popen:
    """
    Stream a clip straight from the camera in an ffplay window, without
    downloading it first. Raises RuntimeError if ffplay is unavailable.
    """
    if find_ffmpeg_tool("ffplay") is None:
        raise RuntimeError(
            "ffplay not found on PATH. It ships with the full FFmpeg build - "
            "install from https://ffmpeg.org/download.html and add it to PATH."
        )
    return subprocess.Popen(
        [
            "ffplay",
            "-autoexit",
            "-loglevel", "error",
            "-window_title", title,
            url,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        **_no_window_kwargs(),
    )


# ---------------------------------------------------------------------------
# BLE helpers
# ---------------------------------------------------------------------------

async def ble_enable_wifi(
    address: str | None = None,
    status_cb=None,
) -> tuple[str | None, str | None]:
    """
    Scan for a GoPro via BLE (or connect to a known address), read its Wi-Fi
    SSID/password from GATT characteristics, send the enable-WiFi-AP command,
    and return (ssid, password).

    status_cb(message) receives human-readable progress, so a GUI can show
    what stage the handshake is at.
    """
    from bleak import BleakScanner, BleakClient

    def say(msg: str) -> None:
        log.info(msg)
        if status_cb is not None:
            status_cb(msg)

    gopro_address = address

    if gopro_address is None:
        say("Scanning for GoPro via Bluetooth LE...")
        say("  (Camera can be off - BLE will wake it up automatically)")
        for attempt in range(4):
            if attempt > 0:
                say(f"  Not found yet, retrying scan ({attempt + 1}/4)...")
            devices = await BleakScanner.discover(timeout=8)
            for d in devices:
                if (d.name or "").startswith("GoPro"):
                    gopro_address = d.address
                    say(f"  Found: {d.name} ({d.address})")
                    break
            if gopro_address:
                break

    if gopro_address is None:
        log.error("No GoPro found via Bluetooth after multiple scans.")
        log.error("  - The GoPro advertises BLE for 8 hours after being put to sleep")
        log.error("  - If off longer, press the power button once to wake it")
        log.error("  - Make sure it has been paired with this PC via Bluetooth settings")
        return None, None

    say(f"  Connecting to {gopro_address}... (GoPro will power on if asleep)")

    response_event = asyncio.Event()

    def notification_handler(sender, data):
        if len(data) >= 3 and data[1] == 0x17:
            if data[2] == 0x00:
                say("  Wi-Fi AP enabled successfully.")
            else:
                log.warning("  Wi-Fi enable response status: %#x", data[2])
            response_event.set()

    ble_kwargs = {}
    if sys.platform == "win32":
        ble_kwargs["winrt"] = {"use_cached_services": False}

    async with BleakClient(gopro_address, **ble_kwargs) as client:
        say("  Connected. Reading Wi-Fi credentials...")
        await asyncio.sleep(1.5)  # Let connection stabilise

        ssid     = (await client.read_gatt_char(WIFI_AP_SSID_UUID)).decode("utf-8")
        password = (await client.read_gatt_char(WIFI_AP_PASSWORD_UUID)).decode("utf-8")

        log.info("  GoPro Wi-Fi SSID    : %s", ssid)
        log.info("  GoPro Wi-Fi Password: %s", password)

        await client.start_notify(CMD_RSP_UUID, notification_handler)
        say("  Sending Wi-Fi enable command...")
        response_event.clear()
        await client.write_gatt_char(CMD_REQ_UUID, ENABLE_WIFI_CMD, response=True)

        try:
            await asyncio.wait_for(response_event.wait(), timeout=5.0)
        except asyncio.TimeoutError:
            log.warning("  No BLE response received (Wi-Fi may still be enabled)")

        await client.stop_notify(CMD_RSP_UUID)

    return ssid, password


# ---------------------------------------------------------------------------
# Wi-Fi / HTTP helpers
# ---------------------------------------------------------------------------

def get_camera_state() -> dict | None:
    """Return the camera's state blob, or None if it isn't reachable."""
    try:
        r = requests.get(CAMERA_STATE_URL, timeout=5)
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


def get_battery_percent() -> int | None:
    """Battery level as a percentage, or None if unavailable."""
    state = get_camera_state()
    if not state:
        return None
    value = state.get("status", {}).get("_2")
    return value if isinstance(value, int) else None


def is_reachable(timeout: float = 0.5) -> bool:
    """Quick check that the camera's HTTP server is answering."""
    try:
        return requests.get(CAMERA_STATE_URL, timeout=timeout).status_code == 200
    except Exception:
        return False


def wait_until_reachable(
    timeout: float | None = None,
    poll_cb=None,
    cancel_event: threading.Event | None = None,
) -> bool:
    """
    Block until the camera answers over Wi-Fi. Returns False on timeout or
    cancellation. poll_cb() fires on each attempt so a UI can animate.
    """
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        if cancel_event is not None and cancel_event.is_set():
            return False
        if is_reachable():
            return True
        if deadline is not None and time.monotonic() > deadline:
            return False
        if poll_cb is not None:
            poll_cb()
        time.sleep(0.5)


def check_gopro_connection(retries: int = 5) -> bool:
    """Check if GoPro is reachable over Wi-Fi, with retries."""
    log.info("Checking GoPro Wi-Fi connection...")
    for attempt in range(retries):
        try:
            r = requests.get(CAMERA_STATE_URL, timeout=5)
            r.raise_for_status()
            battery = r.json().get("status", {}).get("_2", "?")
            log.info("  GoPro connected. Battery: %s%%", battery)
            return True
        except requests.exceptions.ConnectionError:
            if attempt < retries - 1:
                log.info("  Not reachable yet, retrying (%d/%d)...", attempt + 1, retries)
                time.sleep(2)
            else:
                log.error("Cannot reach GoPro at 10.5.5.9")
                log.error("  Make sure your Wi-Fi adapter is connected to the GoPro hotspot")
                return False
        except Exception as e:
            log.error("Error: %s", e)
            return False
    return False


def keepalive_worker(stop_event: threading.Event) -> None:
    """Ping the GoPro every 2.5 s to prevent it sleeping during transfers."""
    while not stop_event.is_set():
        try:
            requests.get(KEEP_ALIVE_URL, timeout=3)
        except Exception:
            pass
        stop_event.wait(timeout=2.5)


class KeepAlive:
    """Context manager / handle around a background keepalive thread."""

    def __init__(self) -> None:
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        self._thread = threading.Thread(
            target=keepalive_worker, args=(self._stop,), daemon=True
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None

    def __enter__(self) -> "KeepAlive":
        self.start()
        return self

    def __exit__(self, *exc) -> None:
        self.stop()


# ---------------------------------------------------------------------------
# Media listing
# ---------------------------------------------------------------------------

def _media_kind(name: str) -> str:
    upper = name.upper()
    if upper.endswith(VIDEO_EXTS):
        return "video"
    if upper.endswith(PHOTO_EXTS):
        return "photo"
    if upper.endswith(".LRV"):
        return "proxy"
    if upper.endswith(".THM"):
        return "thumb"
    return "other"


def _clip_id(name: str) -> str | None:
    """
    Extract the shared identifier between a clip and its low-res proxy.

    GoPro names a clip GX010042.MP4 (HEVC) or GH010042.MP4 (AVC) and its
    proxy GL010042.LRV - same digits, different second letter.
    """
    match = re.match(r"^G[A-Z](\d+)\.[A-Za-z0-9]+$", name)
    return match.group(1) if match else None


def get_media_list(include_proxies: bool = True) -> list[dict]:
    """
    Fetch the flat list of media files from the GoPro.

    Each entry carries name, directory, size, url, kind, duration (seconds,
    videos only) and proxy_url (the .LRV stream used for instant preview).
    """
    try:
        r = requests.get(MEDIA_LIST_URL, timeout=HTTP_TIMEOUT)
        r.raise_for_status()
    except Exception as e:
        log.error("Error fetching media list: %s", e)
        return []

    entries: list[dict] = []
    for folder in r.json().get("media", []):
        directory = folder["d"]
        for f in folder.get("fs", []):
            name = f["n"]
            kind = _media_kind(name)
            if kind == "other":
                continue
            try:
                duration = int(f["dur"]) if f.get("dur") else None
            except (TypeError, ValueError):
                duration = None
            entries.append({
                "name"     : name,
                "directory": directory,
                "size"     : int(f.get("s", 0)),
                "url"      : f"{MEDIA_BASE_URL}/{directory}/{name}",
                "path"     : f"{directory}/{name}",
                "kind"     : kind,
                "duration" : duration,
                "proxy_url": None,
                "thumb_url": None,
            })

    # Pair each clip with the sidecar files the camera writes next to it: the
    # .LRV proxy (instant preview) and the .THM thumbnail (a plain JPEG, and a
    # dependable fallback when the thumbnail endpoint won't play ball).
    proxies = {}
    thumbs  = {}
    for e in entries:
        clip = _clip_id(e["name"])
        if clip is None:
            continue
        if e["kind"] == "proxy":
            proxies[clip] = e
        elif e["kind"] == "thumb":
            thumbs[clip] = e

    for entry in entries:
        if entry["kind"] not in ("video", "photo"):
            continue
        clip = _clip_id(entry["name"]) or ""
        proxy = proxies.get(clip)
        thumb = thumbs.get(clip)
        if proxy is not None:
            entry["proxy_url"] = proxy["url"]
        if thumb is not None:
            entry["thumb_url"] = thumb["url"]

    if not include_proxies:
        entries = [e for e in entries if e["kind"] in ("video", "photo")]

    return entries


def _looks_like_jpeg(data: bytes | None) -> bool:
    """The camera answers 200 with a JSON error body on some failures."""
    return bool(data) and data[:2] == b"\xff\xd8"


def _fetch_jpeg(url: str, params: dict | None = None, attempts: int = 2) -> bytes | None:
    """
    GET a JPEG from the camera, retrying once.

    Returns None (and explains why at WARNING) rather than raising, so one
    unhappy file never stops the rest of the grid loading.
    """
    last = "no attempt made"
    for attempt in range(attempts):
        try:
            r = requests.get(url, params=params, timeout=15)
            if r.status_code != 200:
                last = f"HTTP {r.status_code}"
            elif not _looks_like_jpeg(r.content):
                head = r.content[:60].decode("utf-8", "replace").strip()
                last = f"not a JPEG (got {len(r.content)} bytes: {head!r})"
            else:
                return r.content
        except Exception as e:
            last = f"{type(e).__name__}: {e}"
        if attempt + 1 < attempts:
            time.sleep(0.4)
    log.debug("  thumbnail source failed (%s): %s", url, last)
    _fetch_jpeg.last_error = last
    return None


_fetch_jpeg.last_error = ""


def get_thumbnail(file_info: dict, large: bool = False) -> bytes | None:
    """
    Fetch a JPEG preview frame for a file, trying every source the camera
    offers and falling back through them in order.

    large=False wants the small grid thumbnail; large=True prefers the
    higher-resolution "screennail" for the preview pane.

    Not every camera/firmware serves the thumbnail endpoints reliably, so if
    they fail we fetch the .THM sidecar the camera writes next to each clip.
    That is a plain JPEG served over the same file path as the downloads
    themselves, which makes it the most dependable source of the three.
    """
    sources: list[tuple[str, str, dict | None]] = []
    if large:
        sources.append(("screennail endpoint", MEDIA_SCREENNAIL_URL,
                        {"path": file_info["path"]}))
    sources.append(("thumbnail endpoint", MEDIA_THUMBNAIL_URL,
                    {"path": file_info["path"]}))
    if not large:
        sources.append(("screennail endpoint", MEDIA_SCREENNAIL_URL,
                        {"path": file_info["path"]}))
    if file_info.get("thumb_url"):
        sources.append((".THM sidecar", file_info["thumb_url"], None))

    failures = []
    for label, url, params in sources:
        data = _fetch_jpeg(url, params)
        if data is not None:
            if failures:
                log.info("  %s - thumbnail came from the %s (%s failed)",
                         file_info["name"], label, ", ".join(failures))
            return data
        failures.append(f"{label}: {_fetch_jpeg.last_error}")

    log.warning("No thumbnail for %s. Tried %s.", file_info["name"],
                "; ".join(failures))
    return None


def preview_url(file_info: dict) -> str:
    """
    Best URL to stream for a preview: the low-res .LRV proxy when the camera
    has one (a few MB), otherwise the full-resolution original.
    """
    return file_info.get("proxy_url") or file_info["url"]


# ---------------------------------------------------------------------------
# Transfer
# ---------------------------------------------------------------------------

def download_file(
    file_info: dict,
    dest_path: Path,
    progress_cb=None,
    cancel_event: threading.Event | None = None,
) -> str:
    """
    Download a single file.

    progress_cb(bytes_done, total_bytes) is called as data arrives.
    Returns 'downloaded', 'skipped', or 'error'.
    """
    expected_size = file_info["size"]

    if dest_path.exists() and dest_path.stat().st_size == expected_size:
        return "skipped"

    try:
        _check_cancel(cancel_event)
        with requests.get(file_info["url"], stream=True, timeout=HTTP_TIMEOUT) as r:
            r.raise_for_status()
            total = int(r.headers.get("content-length", expected_size))
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            done = 0
            if progress_cb is not None:
                progress_cb(0, total)
            with open(dest_path, "wb") as fh:
                for chunk in r.iter_content(chunk_size=DOWNLOAD_CHUNK):
                    _check_cancel(cancel_event)
                    fh.write(chunk)
                    done += len(chunk)
                    if progress_cb is not None:
                        progress_cb(done, total)
        return "downloaded"
    except Cancelled:
        if dest_path.exists():
            dest_path.unlink()
        raise
    except Exception as e:
        log.error("  Error downloading %s: %s", file_info["name"], e)
        if dest_path.exists():
            dest_path.unlink()
        return "error"


def delete_file(file_info: dict) -> bool:
    """Delete a file from the GoPro. Tries two path formats for compatibility."""
    directory = file_info["directory"]
    filename  = file_info["name"]
    for path in (f"{directory}/{filename}", f"DCIM/{directory}/{filename}"):
        try:
            r = requests.get(MEDIA_DELETE_URL, params={"path": path}, timeout=10)
            if r.status_code == 200:
                return True
        except Exception as e:
            log.warning("  Could not delete %s from camera: %s", filename, e)
            return False
    log.warning("  Delete failed for %s (tried multiple path formats)", filename)
    return False


def sibling_files(file_info: dict, all_files: list[dict]) -> list[dict]:
    """
    The .LRV proxy and .THM thumbnail the camera stores alongside a clip.

    Deleting only the .MP4 leaves these behind and the card never really frees
    up, so callers that delete a video should delete its siblings too.
    """
    clip = _clip_id(file_info["name"])
    if clip is None:
        return []
    return [
        f for f in all_files
        if f is not file_info
        and f["kind"] in ("proxy", "thumb")
        and _clip_id(f["name"]) == clip
    ]


# ---------------------------------------------------------------------------
# Windows Wi-Fi association (best effort)
# ---------------------------------------------------------------------------

WLAN_PROFILE_TEMPLATE = """<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>{ssid}</name>
  <SSIDConfig>
    <SSID><name>{ssid}</name></SSID>
  </SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM>
    <security>
      <authEncryption>
        <authentication>WPA2PSK</authentication>
        <encryption>AES</encryption>
        <useOneX>false</useOneX>
      </authEncryption>
      <sharedKey>
        <keyType>passPhrase</keyType>
        <protected>false</protected>
        <keyMaterial>{password}</keyMaterial>
      </sharedKey>
    </security>
  </MSM>
</WLANProfile>
"""


def wifi_connect_windows(ssid: str, password: str) -> tuple[bool, str]:
    """
    Ask Windows to join the camera's hotspot via netsh.

    Best effort only - it installs a WLAN profile for the SSID and asks the
    adapter to associate. Returns (ok, message); callers should fall back to
    telling the user to connect by hand.
    """
    if sys.platform != "win32":
        return False, "Automatic Wi-Fi switching is only supported on Windows."

    import tempfile
    from xml.sax.saxutils import escape

    profile = WLAN_PROFILE_TEMPLATE.format(
        ssid=escape(ssid), password=escape(password)
    )
    tmp_dir = Path(tempfile.mkdtemp(prefix="gopro-wlan-"))
    profile_path = tmp_dir / "gopro.xml"
    try:
        # netsh reads the profile as UTF-8 only when it carries a BOM.
        profile_path.write_text(profile, encoding="utf-8-sig")

        add = subprocess.run(
            ["netsh", "wlan", "add", "profile", f"filename={profile_path}",
             "user=current"],
            capture_output=True, text=True, timeout=20, **_no_window_kwargs(),
        )
        if add.returncode != 0:
            return False, (add.stdout or add.stderr or "").strip() or "netsh add profile failed"

        connect = subprocess.run(
            ["netsh", "wlan", "connect", f"name={ssid}", f"ssid={ssid}"],
            capture_output=True, text=True, timeout=20, **_no_window_kwargs(),
        )
        if connect.returncode != 0:
            return False, (connect.stdout or connect.stderr or "").strip() or "netsh connect failed"

        return True, f"Asked Windows to join {ssid}."
    except FileNotFoundError:
        return False, "netsh not found - connect to the camera's Wi-Fi manually."
    except subprocess.TimeoutExpired:
        return False, "netsh timed out - connect to the camera's Wi-Fi manually."
    except Exception as e:
        return False, f"Could not switch Wi-Fi automatically: {e}"
    finally:
        try:
            profile_path.unlink(missing_ok=True)
            tmp_dir.rmdir()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Formatting helpers shared by both front ends
# ---------------------------------------------------------------------------

def human_size(num_bytes: float) -> str:
    """Render a byte count as a compact human-readable string."""
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(num_bytes) < 1024 or unit == "TB":
            return f"{num_bytes:.0f} {unit}" if unit == "B" else f"{num_bytes:.1f} {unit}"
        num_bytes /= 1024
    return f"{num_bytes:.1f} TB"


def human_duration(seconds: float | None) -> str:
    """Render a duration as M:SS (or H:MM:SS past an hour)."""
    if not seconds:
        return "-"
    seconds = int(seconds)
    hours, rem = divmod(seconds, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"
