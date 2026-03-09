"""
GoPro Hero 11 Mini - Wi-Fi Offload Script
------------------------------------------
Uses Bluetooth to wake the GoPro's Wi-Fi, then downloads new files over Wi-Fi.
Skips files that already exist with the same size (so re-running is safe).

Requirements:
    pip install requests tqdm bleak

Usage:
    python gopro_offload.py                          # BLE wake + download
    python gopro_offload.py --no-ble                 # Skip BLE, Wi-Fi already on
    python gopro_offload.py --output C:\\Videos\\GoPro
    python gopro_offload.py --list                   # Just list files, no download
    python gopro_offload.py --all                    # Re-download everything

Notes:
    - The GoPro must be paired with your PC via Bluetooth at least once before
      running this script. Use Windows Bluetooth settings to pair it first.
    - After BLE enables Wi-Fi, connect your USB Wi-Fi dongle to the GoPro
      hotspot before the download begins. The script will wait for you.
    - Your Ethernet internet connection is unaffected.
"""

import argparse
import asyncio
import os
import sys
import time

import subprocess

import requests
from tqdm import tqdm

# ── Wi-Fi / HTTP settings ────────────────────────────────────────────────────
GOPRO_BASE        = "http://10.5.5.9:8080"
MEDIA_LIST_URL    = f"{GOPRO_BASE}/gopro/media/list"
DEFAULT_OUTPUT      = r"F:\gopro"
DEFAULT_OUTPUT_1080 = r"F:\gopro\1080p"

# ── BLE UUIDs (OpenGoPro spec) ───────────────────────────────────────────────
CMD_REQ_UUID          = "b5f90072-aa8d-11e3-9046-0002a5d5c51b"
CMD_RSP_UUID          = "b5f90073-aa8d-11e3-9046-0002a5d5c51b"
WIFI_AP_SSID_UUID     = "b5f90002-aa8d-11e3-9046-0002a5d5c51b"
WIFI_AP_PASSWORD_UUID = "b5f90003-aa8d-11e3-9046-0002a5d5c51b"

# Command bytes to enable Wi-Fi AP (OpenGoPro spec)
ENABLE_WIFI_CMD = bytes([0x03, 0x17, 0x01, 0x01])


# ── BLE functions ─────────────────────────────────────────────────────────────

async def ble_enable_wifi():
    """
    Scan for a GoPro via BLE, read its Wi-Fi SSID/password,
    send the enable-WiFi-AP command, and return (ssid, password).
    """
    from bleak import BleakScanner, BleakClient

    print("Scanning for GoPro via Bluetooth...")
    print("  (Camera can be off — BLE will wake it up automatically)")
    gopro_device = None

    # Retry scan a few times — sleeping GoPros advertise at a slower rate
    # so a single short scan can miss them
    for attempt in range(4):
        if attempt > 0:
            print(f"  Not found yet, retrying scan ({attempt + 1}/4)...")
        devices = await BleakScanner.discover(timeout=8)
        for d in devices:
            name = d.name or ""
            if name.startswith("GoPro"):
                gopro_device = d
                print(f"  Found: {d.name} ({d.address})")
                break
        if gopro_device:
            break

    if gopro_device is None:
        print("\nERROR: No GoPro found via Bluetooth after multiple scans.")
        print("  - The GoPro advertises BLE for 8 hours after being put to sleep")
        print("  - If it has been off longer, press the power button once to wake it")
        print("  - Make sure it has been paired with this PC via Windows Bluetooth settings")
        return None, None

    print("  Connecting... (if GoPro is asleep, it will power on now)")


    response_event = asyncio.Event()

    def notification_handler(sender, data):
        # Response: [length, command_id, status, ...]  status 0x00 = success
        if len(data) >= 3 and data[1] == 0x17:
            if data[2] == 0x00:
                print("  Wi-Fi AP enabled successfully.")
            else:
                print(f"  Warning: Wi-Fi enable response status: {data[2]:#x}")
            response_event.set()

    async with BleakClient(gopro_device.address, winrt={"use_cached_services": False}) as client:
        print("  Connected. Reading Wi-Fi credentials...")

        # Small delay to let the connection stabilize before reading
        await asyncio.sleep(1.5)

        ssid_bytes     = await client.read_gatt_char(WIFI_AP_SSID_UUID)
        password_bytes = await client.read_gatt_char(WIFI_AP_PASSWORD_UUID)
        ssid           = ssid_bytes.decode("utf-8")
        password       = password_bytes.decode("utf-8")

        print(f"  GoPro Wi-Fi SSID    : {ssid}")
        print(f"  GoPro Wi-Fi Password: {password}")

        await client.start_notify(CMD_RSP_UUID, notification_handler)

        print("  Sending Wi-Fi enable command...")
        response_event.clear()
        await client.write_gatt_char(CMD_REQ_UUID, ENABLE_WIFI_CMD, response=True)

        try:
            await asyncio.wait_for(response_event.wait(), timeout=5.0)
        except asyncio.TimeoutError:
            print("  Warning: No response received (Wi-Fi may still be enabled)")

        await client.stop_notify(CMD_RSP_UUID)

    return ssid, password


# ── Wi-Fi / HTTP functions ────────────────────────────────────────────────────

def prompt_wifi_connect(ssid, password):
    """
    Tell the user to connect their dongle to the GoPro hotspot,
    then automatically detect when the connection is established.
    """
    print()
    print("─" * 50)
    print("  ACTION REQUIRED")
    print("─" * 50)
    print(f"  Connect your USB Wi-Fi dongle to:")
    print(f"    Network : {ssid}")
    print(f"    Password: {password}")
    print()
    print("  Click the Wi-Fi icon in the Windows taskbar")
    print(f"  and select '{ssid}'.")
    print()
    print("  Waiting for Wi-Fi connection to GoPro", end="", flush=True)

    # Poll until the GoPro responds on 10.5.5.9
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


def check_gopro_connection(retries=5):
    """Check if GoPro is reachable over Wi-Fi, with retries."""
    print("Checking GoPro Wi-Fi connection...")
    for attempt in range(retries):
        try:
            r       = requests.get(f"{GOPRO_BASE}/gopro/camera/state", timeout=5)
            r.raise_for_status()
            state   = r.json().get("status", {})
            battery = state.get("_2", "?")
            print(f"  GoPro connected. Battery: {battery}%\n")
            return True
        except requests.exceptions.ConnectionError:
            if attempt < retries - 1:
                print(f"  Not reachable yet, retrying ({attempt + 1}/{retries})...")
                time.sleep(2)
            else:
                print("\nERROR: Cannot reach GoPro at 10.5.5.9")
                print("  - Make sure your Wi-Fi dongle is connected to the GoPro hotspot")
                return False
        except Exception as e:
            print(f"\nERROR: {e}")
            return False
    return False


def delete_file(file_info):
    """Delete a file from the GoPro after successful download."""
    directory = file_info["directory"]
    filename  = file_info["name"]

    # Try the standard single-file delete endpoint
    paths_to_try = [
        f"{directory}/{filename}",   # e.g. 100GOPRO/GX010310.MP4
        f"DCIM/{directory}/{filename}",  # fallback with DCIM prefix
    ]
    for path in paths_to_try:
        try:
            r = requests.get(
                f"{GOPRO_BASE}/gopro/media/delete/file",
                params={"path": path},
                timeout=10
            )
            if r.status_code == 200:
                return True
        except Exception as e:
            print(f"  Warning: Could not delete {filename} from GoPro: {e}")
            return False

    print(f"  Warning: Delete failed for {filename} (tried multiple path formats)")
    return False


def get_video_height(filepath):
    """Use FFprobe to get the video height in pixels."""
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=height",
                "-of", "csv=p=0",
                filepath
            ],
            capture_output=True, text=True, timeout=15
        )
        height = result.stdout.strip()
        return int(height) if height.isdigit() else None
    except Exception:
        return None


def get_video_duration(filepath):
    """Use FFprobe to get the video duration in seconds."""
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                filepath
            ],
            capture_output=True, text=True, timeout=15
        )
        val = result.stdout.strip()
        return float(val) if val else None
    except Exception:
        return None


def transcode_to_1080p(source_path, output_dir):
    """
    Transcode a video to 1080p using FFmpeg with a live progress bar.
    Skips if the source is already 1080p or lower.
    Uses CRF 28 with H.264 for smaller file size.
    Returns True on success, False on skip or failure.
    """
    filename  = os.path.basename(source_path)
    dest_path = os.path.join(output_dir, filename)

    # Skip if already transcoded
    if os.path.exists(dest_path):
        print(f"  {filename} — 1080p copy already exists, skipping")
        return False

    # Check source resolution
    height = get_video_height(source_path)
    if height is None:
        print(f"  {filename} — could not read resolution, skipping transcode")
        return False
    if height <= 1080:
        print(f"  {filename} — already {height}p, skipping transcode")
        return False

    # Get duration for progress bar
    duration = get_video_duration(source_path)

    print(f"  {filename} — transcoding {height}p → 1080p...")
    try:
        process = subprocess.Popen(
            [
                "ffmpeg", "-y",
                "-i", source_path,
                "-vf", "scale=-2:1080",
                "-c:v", "libx264",
                "-crf", "28",
                "-preset", "fast",
                "-c:a", "aac",
                "-b:a", "128k",
                "-progress", "pipe:1",  # Stream progress to stdout
                "-nostats",
                dest_path
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True
        )

        # Parse FFmpeg progress output and display a progress bar
        bar_width = 30
        current_time = 0.0
        with tqdm(
            total=int(duration) if duration else None,
            desc=f"  Transcoding",
            unit="s",
            bar_format="{desc}: {percentage:3.0f}%|{bar}| {n}/{total}s",
            ncols=70,
            leave=True
        ) as bar:
            for line in process.stdout:
                line = line.strip()
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
            size_mb = os.path.getsize(dest_path) / (1024 * 1024)
            print(f"  {filename} — 1080p copy saved ({size_mb:.1f} MB)")
            return True
        else:
            print(f"  {filename} — transcode failed")
            if os.path.exists(dest_path):
                os.remove(dest_path)
            return False
    except FileNotFoundError:
        print("  WARNING: FFmpeg not found. Install it from https://ffmpeg.org/download.html")
        print("  Make sure to add FFmpeg to your PATH during installation.")
        return False


def keepalive_worker(stop_event):
    """
    Runs in a background thread during downloads.
    Sends a lightweight HTTP request to the GoPro every 2.5 seconds
    to prevent it from timing out and going to sleep.
    """
    while not stop_event.is_set():
        try:
            requests.get(f"{GOPRO_BASE}/gopro/camera/state", timeout=3)
        except Exception:
            pass  # Silently ignore — main thread handles real failures
        stop_event.wait(timeout=2.5)


def get_media_list():
    """Fetch the list of all media files from the GoPro."""
    try:
        r    = requests.get(MEDIA_LIST_URL, timeout=10)
        r.raise_for_status()
        data  = r.json()
        files = []
        for folder in data.get("media", []):
            directory = folder["d"]
            for f in folder.get("fs", []):
                files.append({
                    "name"     : f["n"],
                    "directory": directory,
                    "size"     : int(f.get("s", 0)),
                    "url"      : f"{GOPRO_BASE}/videos/DCIM/{directory}/{f['n']}"
                })
        return files
    except Exception as e:
        print(f"ERROR fetching media list: {e}")
        return []


def download_file(file_info, output_dir):
    """Download a single file with a progress bar."""
    url           = file_info["url"]
    filename      = file_info["name"]
    expected_size = file_info["size"]
    dest_path     = os.path.join(output_dir, filename)

    if os.path.exists(dest_path):
        if os.path.getsize(dest_path) == expected_size:
            return "skipped"

    try:
        with requests.get(url, stream=True, timeout=30) as r:
            r.raise_for_status()
            total = int(r.headers.get("content-length", expected_size))
            with open(dest_path, "wb") as f, tqdm(
                desc=f"  {filename}",
                total=total,
                unit="B",
                unit_scale=True,
                unit_divisor=1024,
                ncols=70,
            ) as bar:
                for chunk in r.iter_content(chunk_size=65536):
                    f.write(chunk)
                    bar.update(len(chunk))
        return "downloaded"
    except Exception as e:
        print(f"\n  ERROR downloading {filename}: {e}")
        if os.path.exists(dest_path):
            os.remove(dest_path)
        return "error"


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="GoPro Hero 11 Mini Offload Tool")
    parser.add_argument(
        "--output", "-o",
        default=DEFAULT_OUTPUT,
        help=f"Destination folder (default: {DEFAULT_OUTPUT})"
    )
    parser.add_argument(
        "--output-1080",
        default=DEFAULT_OUTPUT_1080,
        dest="output_1080",
        help=f"Folder for 1080p copies (default: {DEFAULT_OUTPUT_1080})"
    )
    parser.add_argument(
        "--no-ble",
        action="store_true",
        help="Skip Bluetooth wake — use if Wi-Fi is already enabled on the GoPro"
    )
    parser.add_argument(
        "--all", "-a",
        action="store_true",
        help="Re-download all files, even if they already exist locally"
    )
    parser.add_argument(
        "--list", "-l",
        action="store_true",
        help="List files on the GoPro without downloading"
    )
    args = parser.parse_args()

    print("=" * 50)
    print("  GoPro Hero 11 Mini Offload Tool")
    print("=" * 50)
    print()

    # ── Step 1: BLE wake ──────────────────────────────────────────────────────
    ssid, password = None, None
    if not args.no_ble:
        try:
            import bleak  # noqa: F401
        except ImportError:
            print("ERROR: 'bleak' library not installed. Run:")
            print("  pip install bleak")
            print("Or skip BLE with --no-ble if Wi-Fi is already on.\n")
            sys.exit(1)

        ssid, password = asyncio.run(ble_enable_wifi())
        if ssid is None:
            print("\nBLE wake failed.")
            print("  Enable Wi-Fi manually on the GoPro and re-run with --no-ble")
            sys.exit(1)

        prompt_wifi_connect(ssid, password)
    else:
        print("Skipping Bluetooth wake (--no-ble).\n")

    # ── Step 2: Verify Wi-Fi ──────────────────────────────────────────────────
    if args.no_ble:
        # Only need to explicitly check if we skipped the BLE+auto-detect flow
        if not check_gopro_connection():
            sys.exit(1)

    # ── Step 3: Get file list ─────────────────────────────────────────────────
    print("Fetching media list from GoPro...")
    files = get_media_list()
    if not files:
        print("No media files found on GoPro.")
        sys.exit(0)

    print(f"  Found {len(files)} file(s) on GoPro.\n")

    # ── Step 4: List mode ─────────────────────────────────────────────────────
    if args.list:
        print(f"{'Filename':<30} {'Size':>10}")
        print("-" * 42)
        for f in files:
            size_mb = f["size"] / (1024 * 1024)
            print(f"{f['name']:<30} {size_mb:>9.1f}M")
        sys.exit(0)

    # ── Step 5: Download ──────────────────────────────────────────────────────
    os.makedirs(args.output, exist_ok=True)
    print(f"Saving files to: {args.output}\n")

    # Start keepalive thread to prevent GoPro from sleeping during transfer
    import threading
    stop_keepalive = threading.Event()
    keepalive_thread = threading.Thread(target=keepalive_worker, args=(stop_keepalive,), daemon=True)
    keepalive_thread.start()
    print("  Keepalive started — GoPro will stay awake during transfer.\n")

    counts = {"downloaded": 0, "skipped": 0, "error": 0}
    try:
        for f in files:
            if args.all:
                dest = os.path.join(args.output, f["name"])
                if os.path.exists(dest):
                    os.remove(dest)
            result = download_file(f, args.output)
            counts[result] += 1
            if result == "skipped":
                print(f"  {f['name']} — already exists, skipping")
            elif result == "downloaded":
                if delete_file(f):
                    print(f"  {f['name']} — deleted from GoPro")
                else:
                    print(f"  {f['name']} — WARNING: could not delete from GoPro")
                # Transcode to 1080p if source is higher resolution
                if f['name'].lower().endswith('.mp4'):
                    os.makedirs(args.output_1080, exist_ok=True)
                    source = os.path.join(args.output, f['name'])
                    # Verify file is fully written before transcoding
                    if os.path.exists(source) and os.path.getsize(source) == f['size']:
                        transcode_to_1080p(source, args.output_1080)
                    else:
                        print(f"  {f['name']} — file size mismatch, skipping transcode")
    finally:
        stop_keepalive.set()
        keepalive_thread.join()

    # ── Summary ───────────────────────────────────────────────────────────────
    print()
    print("=" * 50)
    print(f"  Done!")
    print(f"  Downloaded : {counts['downloaded']}")
    print(f"  Skipped    : {counts['skipped']}")
    print(f"  Errors     : {counts['error']}")
    print("=" * 50)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nCancelled by user.")
    except Exception as e:
        print(f"\n\nUnexpected error: {e}")
    finally:
        print()
        input("Press ENTER to close...")
