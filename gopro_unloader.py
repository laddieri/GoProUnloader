#!/usr/bin/env python3
"""
GoPro Hero 11 Mini - Wi-Fi Offload Script
------------------------------------------
Uses Bluetooth to wake the GoPro's Wi-Fi, reads Wi-Fi credentials directly
from the camera, downloads new files over Wi-Fi, optionally deletes them from
the camera, and transcodes them to 1080p using FFmpeg.

Requirements:
    pip install requests tqdm bleak

External dependencies:
    ffmpeg + ffprobe (must be installed and available on PATH)

Usage:
    python gopro_unloader.py                          # BLE wake + download
    python gopro_unloader.py --skip-ble               # Skip BLE, Wi-Fi already on
    python gopro_unloader.py --output-dir D:\\Videos
    python gopro_unloader.py --list                   # List files, no download
    python gopro_unloader.py --all                    # Re-download everything
    python gopro_unloader.py --no-delete              # Keep files on camera
    python gopro_unloader.py --keep-originals         # Keep raw downloads too
    python gopro_unloader.py --no-transcode           # Skip FFmpeg step
    python gopro_unloader.py --ble-address AA:BB:... # Skip scan, use address
"""

import argparse
import asyncio
import logging
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import requests
from tqdm import tqdm

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
GOPRO_BASE            = "http://10.5.5.9:8080"
MEDIA_LIST_URL        = f"{GOPRO_BASE}/gopro/media/list"
MEDIA_DELETE_URL      = f"{GOPRO_BASE}/gopro/media/delete/file"
MEDIA_BASE_URL        = f"{GOPRO_BASE}/videos/DCIM"

# BLE UUIDs (OpenGoPro spec)
CMD_REQ_UUID          = "b5f90072-aa8d-11e3-9046-0002a5d5c51b"
CMD_RSP_UUID          = "b5f90073-aa8d-11e3-9046-0002a5d5c51b"
WIFI_AP_SSID_UUID     = "b5f90002-aa8d-11e3-9046-0002a5d5c51b"
WIFI_AP_PASSWORD_UUID = "b5f90003-aa8d-11e3-9046-0002a5d5c51b"
ENABLE_WIFI_CMD       = bytes([0x03, 0x17, 0x01, 0x01])

HTTP_TIMEOUT   = 30       # seconds
DOWNLOAD_CHUNK = 65536    # bytes


# ---------------------------------------------------------------------------
# FFmpeg helpers
# ---------------------------------------------------------------------------

def check_ffmpeg() -> None:
    """Raise if ffmpeg or ffprobe are not found on PATH."""
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            raise RuntimeError(
                f"{tool} not found on PATH. "
                "Install FFmpeg from https://ffmpeg.org/download.html "
                "and make sure it is added to PATH."
            )


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
            capture_output=True, text=True, timeout=15,
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
            capture_output=True, text=True, timeout=15,
        )
        val = result.stdout.strip()
        return float(val) if val else None
    except Exception:
        return None


def transcode_to_1080p(src: Path, dest: Path) -> bool:
    """
    Transcode src to 1080p H.264/AAC and write to dest with a live progress bar.
    Preserves aspect ratio with letterbox/pillarbox padding.
    Skips if source is already ≤1080p or dest already exists.
    Returns True on success, False on skip or failure.
    """
    if dest.exists():
        log.info("  %s — 1080p copy already exists, skipping", src.name)
        return False

    height = get_video_height(src)
    if height is None:
        log.warning("  %s — could not read resolution, skipping transcode", src.name)
        return False
    if height <= 1080:
        log.info("  %s — already %dp, skipping transcode", src.name, height)
        return False

    duration = get_video_duration(src)
    log.info("  %s — transcoding %dp → 1080p…", src.name, height)

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
        )

        current_time = 0.0
        with tqdm(
            total=int(duration) if duration else None,
            desc=f"  Transcoding {src.name}",
            unit="s",
            bar_format="{desc}: {percentage:3.0f}%|{bar}| {n}/{total}s",
            ncols=80,
            leave=True,
        ) as bar:
            for line in process.stdout:
                if line.startswith("out_time_ms="):
                    try:
                        ms = int(line.split("=")[1])
                        new_time = ms / 1_000_000
                        if new_time > current_time:
                            bar.update(int(new_time - current_time))
                            current_time = new_time
                    except ValueError:
                        pass

        process.wait()
        if process.returncode == 0:
            size_mb = dest.stat().st_size / (1024 * 1024)
            log.info("  %s — 1080p copy saved (%.1f MB)", src.name, size_mb)
            return True
        else:
            log.error("  %s — transcode failed", src.name)
            if dest.exists():
                dest.unlink()
            return False

    except FileNotFoundError:
        log.error("FFmpeg not found. Install it from https://ffmpeg.org/download.html")
        return False


# ---------------------------------------------------------------------------
# BLE helpers
# ---------------------------------------------------------------------------

async def ble_enable_wifi(address: str | None = None) -> tuple[str | None, str | None]:
    """
    Scan for a GoPro via BLE (or connect to a known address), read its Wi-Fi
    SSID/password from GATT characteristics, send the enable-WiFi-AP command,
    and return (ssid, password).
    """
    from bleak import BleakScanner, BleakClient

    gopro_address = address

    if gopro_address is None:
        log.info("Scanning for GoPro via Bluetooth LE…")
        log.info("  (Camera can be off — BLE will wake it up automatically)")
        for attempt in range(4):
            if attempt > 0:
                log.info("  Not found yet, retrying scan (%d/4)…", attempt + 1)
            devices = await BleakScanner.discover(timeout=8)
            for d in devices:
                if (d.name or "").startswith("GoPro"):
                    gopro_address = d.address
                    log.info("  Found: %s (%s)", d.name, d.address)
                    break
            if gopro_address:
                break

    if gopro_address is None:
        log.error("No GoPro found via Bluetooth after multiple scans.")
        log.error("  - The GoPro advertises BLE for 8 hours after being put to sleep")
        log.error("  - If off longer, press the power button once to wake it")
        log.error("  - Make sure it has been paired with this PC via Bluetooth settings")
        return None, None

    log.info("  Connecting to %s… (GoPro will power on if asleep)", gopro_address)

    response_event = asyncio.Event()

    def notification_handler(sender, data):
        if len(data) >= 3 and data[1] == 0x17:
            if data[2] == 0x00:
                log.info("  Wi-Fi AP enabled successfully.")
            else:
                log.warning("  Wi-Fi enable response status: %#x", data[2])
            response_event.set()

    ble_kwargs = {}
    if sys.platform == "win32":
        ble_kwargs["winrt"] = {"use_cached_services": False}

    async with BleakClient(gopro_address, **ble_kwargs) as client:
        log.info("  Connected. Reading Wi-Fi credentials…")
        await asyncio.sleep(1.5)  # Let connection stabilise

        ssid     = (await client.read_gatt_char(WIFI_AP_SSID_UUID)).decode("utf-8")
        password = (await client.read_gatt_char(WIFI_AP_PASSWORD_UUID)).decode("utf-8")

        log.info("  GoPro Wi-Fi SSID    : %s", ssid)
        log.info("  GoPro Wi-Fi Password: %s", password)

        await client.start_notify(CMD_RSP_UUID, notification_handler)
        log.info("  Sending Wi-Fi enable command…")
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

def prompt_wifi_connect(ssid: str, password: str) -> None:
    """Tell the user to connect their Wi-Fi adapter, then wait until the GoPro responds."""
    print()
    print("─" * 54)
    print("  ACTION REQUIRED")
    print("─" * 54)
    print(f"  Connect your Wi-Fi adapter to:")
    print(f"    Network : {ssid}")
    print(f"    Password: {password}")
    print()
    print("  Waiting for Wi-Fi connection to GoPro", end="", flush=True)

    while True:
        try:
            r = requests.get(f"{GOPRO_BASE}/gopro/camera/state", timeout=0.5)
            if r.status_code == 200:
                print(" connected!")
                print()
                return
        except Exception:
            pass
        print(".", end="", flush=True)
        time.sleep(0.5)


def check_gopro_connection(retries: int = 5) -> bool:
    """Check if GoPro is reachable over Wi-Fi, with retries."""
    log.info("Checking GoPro Wi-Fi connection…")
    for attempt in range(retries):
        try:
            r = requests.get(f"{GOPRO_BASE}/gopro/camera/state", timeout=5)
            r.raise_for_status()
            battery = r.json().get("status", {}).get("_2", "?")
            log.info("  GoPro connected. Battery: %s%%", battery)
            return True
        except requests.exceptions.ConnectionError:
            if attempt < retries - 1:
                log.info("  Not reachable yet, retrying (%d/%d)…", attempt + 1, retries)
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
            requests.get(f"{GOPRO_BASE}/gopro/camera/state", timeout=3)
        except Exception:
            pass
        stop_event.wait(timeout=2.5)


def get_media_list() -> list[dict]:
    """Fetch the flat list of media files from the GoPro."""
    try:
        r = requests.get(MEDIA_LIST_URL, timeout=HTTP_TIMEOUT)
        r.raise_for_status()
        files = []
        for folder in r.json().get("media", []):
            directory = folder["d"]
            for f in folder.get("fs", []):
                name = f["n"]
                if name.upper().endswith((".MP4", ".LRV", ".THM")):
                    files.append({
                        "name"     : name,
                        "directory": directory,
                        "size"     : int(f.get("s", 0)),
                        "url"      : f"{MEDIA_BASE_URL}/{directory}/{name}",
                    })
        return files
    except Exception as e:
        log.error("Error fetching media list: %s", e)
        return []


def download_file(file_info: dict, dest_path: Path) -> str:
    """
    Download a single file with a tqdm progress bar.
    Returns 'downloaded', 'skipped', or 'error'.
    """
    expected_size = file_info["size"]

    if dest_path.exists() and dest_path.stat().st_size == expected_size:
        return "skipped"

    try:
        with requests.get(file_info["url"], stream=True, timeout=HTTP_TIMEOUT) as r:
            r.raise_for_status()
            total = int(r.headers.get("content-length", expected_size))
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            with open(dest_path, "wb") as fh, tqdm(
                desc=f"  {file_info['name']}",
                total=total,
                unit="B",
                unit_scale=True,
                unit_divisor=1024,
                ncols=80,
            ) as bar:
                for chunk in r.iter_content(chunk_size=DOWNLOAD_CHUNK):
                    fh.write(chunk)
                    bar.update(len(chunk))
        return "downloaded"
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


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GoPro Hero 11 Mini Offload Tool — BLE wake, Wi-Fi download, FFmpeg transcode"
    )
    parser.add_argument(
        "--output-dir", "-o", type=Path, default=Path("F:/gopro"),
        help="Root output directory (default: F:/gopro)",
    )
    parser.add_argument(
        "--skip-ble", "--no-ble", action="store_true",
        help="Skip Bluetooth wake — use if Wi-Fi is already enabled on the GoPro",
    )
    parser.add_argument(
        "--ble-address", type=str, default=None,
        help="Manually specify GoPro BLE address instead of scanning",
    )
    parser.add_argument(
        "--all", "-a", action="store_true",
        help="Re-download all files, even if they already exist locally",
    )
    parser.add_argument(
        "--list", "-l", action="store_true",
        help="List files on the GoPro without downloading",
    )
    parser.add_argument(
        "--no-delete", action="store_true",
        help="Skip deleting files from the camera after downloading",
    )
    parser.add_argument(
        "--no-transcode", action="store_true",
        help="Skip FFmpeg transcoding step entirely",
    )
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args = parse_args()

    print("=" * 54)
    print("  GoPro Hero 11 Mini Offload Tool")
    print("=" * 54)
    print()

    if not args.no_transcode and not args.list:
        check_ffmpeg()

    raw_dir        = args.output_dir / "raw"
    transcoded_dir = args.output_dir / "transcoded"

    # 1. BLE wake + read Wi-Fi credentials
    ssid, password = None, None
    if not args.skip_ble:
        try:
            import bleak  # noqa: F401
        except ImportError:
            log.error("'bleak' library not installed. Run: pip install bleak")
            log.error("Or skip BLE with --skip-ble if Wi-Fi is already on.")
            sys.exit(1)

        ssid, password = asyncio.run(ble_enable_wifi(args.ble_address))
        if ssid is None:
            log.error("BLE wake failed. Enable Wi-Fi manually and re-run with --skip-ble.")
            sys.exit(1)

        prompt_wifi_connect(ssid, password)
    else:
        log.info("Skipping Bluetooth wake (--skip-ble).")
        if not check_gopro_connection():
            sys.exit(1)

    # 2. Get file list
    log.info("Fetching media list from GoPro…")
    files = get_media_list()
    if not files:
        log.info("No media files found on GoPro.")
        sys.exit(0)

    log.info("Found %d file(s) on GoPro.", len(files))

    # 3. List mode
    if args.list:
        print(f"\n{'Filename':<30} {'Size':>10}")
        print("-" * 42)
        for f in files:
            print(f"{f['name']:<30} {f['size'] / (1024*1024):>9.1f} MB")
        sys.exit(0)

    # 4. Download
    raw_dir.mkdir(parents=True, exist_ok=True)
    log.info("Saving files to: %s", raw_dir)

    stop_keepalive   = threading.Event()
    keepalive_thread = threading.Thread(
        target=keepalive_worker, args=(stop_keepalive,), daemon=True
    )
    keepalive_thread.start()
    log.info("Keepalive started — GoPro will stay awake during transfer.")

    counts           = {"downloaded": 0, "skipped": 0, "error": 0}
    downloaded_files : list[tuple[dict, Path]] = []

    try:
        for f in files:
            dest = raw_dir / f["name"]
            if args.all and dest.exists():
                dest.unlink()
            result = download_file(f, dest)
            counts[result] += 1
            if result == "skipped":
                log.info("  %s — already exists, skipping", f["name"])
            elif result == "downloaded":
                downloaded_files.append((f, dest))
    finally:
        stop_keepalive.set()
        keepalive_thread.join()

    # 5. Delete from camera (only successfully downloaded files)
    if not args.no_delete:
        for f, _ in downloaded_files:
            if delete_file(f):
                log.info("  %s — deleted from GoPro", f["name"])
            else:
                log.warning("  %s — could not delete from GoPro", f["name"])
    else:
        log.info("Skipping camera deletion (--no-delete).")

    # 6. Transcode to 1080p
    transcode_errors: list[str] = []
    if not args.no_transcode:
        mp4s = [(f, dest) for f, dest in downloaded_files if f["name"].lower().endswith(".mp4")]
        if mp4s:
            transcoded_dir.mkdir(parents=True, exist_ok=True)
            keep = input("Keep original (raw) videos after transcoding? [y/N] ").strip().lower() == "y"
            log.info("Transcoding %d video(s) to 1080p…", len(mp4s))
            for f, src in mp4s:
                if src.exists() and src.stat().st_size == f["size"]:
                    out = transcoded_dir / src.name
                    ok  = transcode_to_1080p(src, out)
                    if ok and not keep:
                        src.unlink()
                    elif not ok:
                        transcode_errors.append(src.name)
                else:
                    log.warning("  %s — file size mismatch, skipping transcode", f["name"])

    # 7. Summary
    print()
    print("=" * 54)
    print("  Done!")
    print(f"  Downloaded : {counts['downloaded']}")
    print(f"  Skipped    : {counts['skipped']}")
    print(f"  Errors     : {counts['error']}")
    if transcode_errors:
        print(f"  Transcode errors: {', '.join(transcode_errors)}")
    print("=" * 54)

    if counts["error"] or transcode_errors:
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nCancelled by user.")
    except Exception as e:
        log.error("Unexpected error: %s", e)
        sys.exit(1)
    finally:
        print()
        if sys.platform == "win32":
            input("Press ENTER to close…")
