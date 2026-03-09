#!/usr/bin/env python3
"""
GoPro Hero 11 Mini Unloader
----------------------------
Wakes a GoPro Hero 11 Mini via Bluetooth LE, downloads all videos over WiFi,
deletes them from the camera, and transcodes them to 1080p using FFmpeg.

Requirements:
    pip install bleak requests

External dependencies:
    ffmpeg (must be installed and available on PATH)

Usage:
    python gopro_unloader.py [--output-dir OUTPUT_DIR] [--keep-originals] [--no-delete]
"""

import argparse
import asyncio
import logging
import shutil
import subprocess
import sys
import time
from pathlib import Path

import requests
from bleak import BleakClient, BleakScanner

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
# Constants – GoPro Open API
# ---------------------------------------------------------------------------
GOPRO_WIFI_BASE = "http://10.5.5.9:8080"
GOPRO_MEDIA_LIST = f"{GOPRO_WIFI_BASE}/gopro/media/list"
GOPRO_MEDIA_DELETE = f"{GOPRO_WIFI_BASE}/gopro/media/delete/file"
GOPRO_MEDIA_BASE_URL = f"{GOPRO_WIFI_BASE}/videos/DCIM"

# BLE – GoPro Command Service
GOPRO_BLE_NAME_PREFIX = "GoPro"
GOPRO_SERVICE_UUID = "0000fea6-0000-1000-8000-00805f9b34fb"
GOPRO_COMMAND_CHAR_UUID = "b5f90072-aa8d-11e3-9046-0002a5d5c51b"

# Command to wake / keep-alive (0x01 0x01 0x01 = set shutter off; any valid
# command wakes the camera – we use the "set shutter" no-op approach)
BLE_WAKE_COMMAND = bytes([0x03, 0x17, 0x01, 0x01])  # set video mode

HTTP_TIMEOUT = 30          # seconds for HTTP requests
DOWNLOAD_CHUNK = 1 << 20   # 1 MiB read chunks


# ---------------------------------------------------------------------------
# Bluetooth helpers
# ---------------------------------------------------------------------------

async def find_gopro(timeout: float = 15.0) -> str | None:
    """Scan for a GoPro camera and return its BLE address, or None."""
    log.info("Scanning for GoPro via Bluetooth LE (%.0fs timeout)…", timeout)
    devices = await BleakScanner.discover(timeout=timeout)
    for device in devices:
        if device.name and device.name.startswith(GOPRO_BLE_NAME_PREFIX):
            log.info("Found: %s  [%s]", device.name, device.address)
            return device.address
    return None


async def wake_gopro(address: str) -> None:
    """Connect to GoPro over BLE and send a command to wake it."""
    log.info("Connecting to GoPro BLE (%s)…", address)
    async with BleakClient(address) as client:
        log.info("Connected. Sending wake command…")
        await client.write_gatt_char(GOPRO_COMMAND_CHAR_UUID, BLE_WAKE_COMMAND, response=True)
        log.info("Wake command sent. Waiting for WiFi AP to come up…")
        await asyncio.sleep(5)


# ---------------------------------------------------------------------------
# WiFi / HTTP helpers
# ---------------------------------------------------------------------------

def wait_for_wifi(retries: int = 20, delay: float = 3.0) -> None:
    """Poll the GoPro HTTP endpoint until it responds."""
    log.info("Waiting for GoPro WiFi to become reachable…")
    for attempt in range(1, retries + 1):
        try:
            r = requests.get(GOPRO_MEDIA_LIST, timeout=5)
            if r.status_code == 200:
                log.info("GoPro WiFi is up.")
                return
        except requests.exceptions.ConnectionError:
            pass
        log.debug("Attempt %d/%d – not reachable yet, retrying in %.0fs…", attempt, retries, delay)
        time.sleep(delay)
    raise RuntimeError(
        "Could not reach GoPro over WiFi after multiple attempts. "
        "Make sure you are connected to the GoPro WiFi network."
    )


def list_media() -> list[dict]:
    """Return a flat list of media file descriptors from the GoPro."""
    r = requests.get(GOPRO_MEDIA_LIST, timeout=HTTP_TIMEOUT)
    r.raise_for_status()
    data = r.json()

    files: list[dict] = []
    for directory in data.get("media", []):
        folder = directory["d"]
        for entry in directory.get("fs", []):
            name: str = entry["n"]
            if name.upper().endswith((".MP4", ".LRV", ".THM")):
                files.append({"folder": folder, "name": name, "size": int(entry.get("s", 0))})
    return files


def download_file(folder: str, name: str, dest_path: Path) -> None:
    """Stream-download a single file from the GoPro."""
    url = f"{GOPRO_MEDIA_BASE_URL}/{folder}/{name}"
    log.info("  Downloading %s/%s → %s", folder, name, dest_path)
    with requests.get(url, stream=True, timeout=HTTP_TIMEOUT) as r:
        r.raise_for_status()
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        with open(dest_path, "wb") as fh:
            for chunk in r.iter_content(chunk_size=DOWNLOAD_CHUNK):
                fh.write(chunk)


def delete_file(folder: str, name: str) -> None:
    """Delete a single file from the GoPro."""
    path_param = f"{folder}/{name}"
    r = requests.delete(GOPRO_MEDIA_DELETE, params={"path": path_param}, timeout=HTTP_TIMEOUT)
    r.raise_for_status()
    log.info("  Deleted from camera: %s", path_param)


# ---------------------------------------------------------------------------
# FFmpeg transcoding
# ---------------------------------------------------------------------------

def check_ffmpeg() -> None:
    """Raise if ffmpeg is not found on PATH."""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError(
            "ffmpeg not found on PATH. Please install ffmpeg before running this script."
        )


def transcode_to_1080p(src: Path, dest: Path) -> None:
    """
    Transcode *src* to 1080p H.264/AAC and write to *dest*.

    Scaling: scale to 1920×1080, preserving aspect ratio with letterbox/pillarbox.
    """
    log.info("  Transcoding → %s", dest)
    cmd = [
        "ffmpeg",
        "-y",                          # overwrite output
        "-i", str(src),
        "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease,"
               "pad=1920:1080:(ow-iw)/2:(oh-ih)/2",
        "-c:v", "libx264",
        "-preset", "fast",
        "-crf", "23",
        "-c:a", "aac",
        "-b:a", "192k",
        "-movflags", "+faststart",
        str(dest),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(
            f"ffmpeg failed for {src.name}:\n{result.stderr[-2000:]}"
        )


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Wake GoPro Hero 11 Mini via BLE, download videos over WiFi, "
                    "delete from camera, and transcode to 1080p with FFmpeg."
    )
    parser.add_argument(
        "--output-dir", "-o",
        type=Path,
        default=Path("./gopro_output"),
        help="Directory where downloaded and transcoded files are saved (default: ./gopro_output)",
    )
    parser.add_argument(
        "--keep-originals",
        action="store_true",
        help="Keep the raw downloaded files in addition to the transcoded versions.",
    )
    parser.add_argument(
        "--no-delete",
        action="store_true",
        help="Skip deleting files from the camera after downloading.",
    )
    parser.add_argument(
        "--skip-ble",
        action="store_true",
        help="Skip Bluetooth wake step (use if camera is already on and WiFi is up).",
    )
    parser.add_argument(
        "--ble-address",
        type=str,
        default=None,
        help="Manually specify the GoPro BLE address instead of scanning.",
    )
    return parser.parse_args()


async def ble_wake(args: argparse.Namespace) -> None:
    """BLE discovery + wake (async portion)."""
    if args.skip_ble:
        log.info("Skipping BLE wake (--skip-ble).")
        return

    address = args.ble_address
    if address is None:
        address = await find_gopro()
        if address is None:
            raise RuntimeError(
                "No GoPro camera found via Bluetooth. "
                "Make sure the camera is within range and Bluetooth is enabled. "
                "You can also use --skip-ble if the camera is already awake."
            )

    await wake_gopro(address)


def main() -> None:
    args = parse_args()

    check_ffmpeg()

    raw_dir = args.output_dir / "raw"
    transcoded_dir = args.output_dir / "transcoded"
    raw_dir.mkdir(parents=True, exist_ok=True)
    transcoded_dir.mkdir(parents=True, exist_ok=True)

    # 1. Wake camera via BLE
    asyncio.run(ble_wake(args))

    # 2. Wait for WiFi to be reachable
    wait_for_wifi()

    # 3. List media on camera
    log.info("Fetching media list from GoPro…")
    media = list_media()
    videos = [m for m in media if m["name"].upper().endswith(".MP4")]
    log.info("Found %d video(s) on camera.", len(videos))

    if not videos:
        log.info("Nothing to download. Exiting.")
        return

    downloaded: list[Path] = []
    failed: list[str] = []

    # 4. Download each video
    for item in videos:
        folder, name = item["folder"], item["name"]
        dest = raw_dir / name
        try:
            download_file(folder, name, dest)
            downloaded.append(dest)
        except Exception as exc:
            log.error("  Failed to download %s: %s", name, exc)
            failed.append(name)

    # 5. Delete from camera (only successfully downloaded files)
    if not args.no_delete:
        for item in videos:
            if item["name"] not in failed:
                try:
                    delete_file(item["folder"], item["name"])
                except Exception as exc:
                    log.warning("  Could not delete %s from camera: %s", item["name"], exc)
    else:
        log.info("Skipping camera deletion (--no-delete).")

    # 6. Transcode to 1080p
    log.info("Transcoding %d video(s) to 1080p…", len(downloaded))
    transcode_errors: list[str] = []
    for raw_path in downloaded:
        out_path = transcoded_dir / raw_path.name
        try:
            transcode_to_1080p(raw_path, out_path)
            if not args.keep_originals:
                raw_path.unlink()
        except Exception as exc:
            log.error("  Transcode failed for %s: %s", raw_path.name, exc)
            transcode_errors.append(raw_path.name)

    # 7. Summary
    success = len(downloaded) - len(transcode_errors)
    log.info("Done. %d video(s) transcoded successfully.", success)
    if failed:
        log.warning("Download failures: %s", ", ".join(failed))
    if transcode_errors:
        log.warning("Transcode failures: %s", ", ".join(transcode_errors))

    if failed or transcode_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
