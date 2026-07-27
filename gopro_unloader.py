#!/usr/bin/env python3
"""
GoPro Hero 11 Mini - Wi-Fi Offload Script (command line)
---------------------------------------------------------
Uses Bluetooth to wake the GoPro's Wi-Fi, reads Wi-Fi credentials directly
from the camera, downloads new files over Wi-Fi, optionally deletes them from
the camera, and transcodes them to 1080p using FFmpeg.

For a point-and-click version with thumbnails and video preview, run
gopro_gui.py instead.

Requirements:
    pip install -r requirements.txt

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
import sys
from pathlib import Path

from tqdm import tqdm

import gopro_core as core

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("gopro")


# ---------------------------------------------------------------------------
# Terminal front end for the core helpers
# ---------------------------------------------------------------------------

def prompt_wifi_connect(ssid: str, password: str) -> None:
    """Tell the user to connect their Wi-Fi adapter, then wait for the GoPro."""
    print()
    print("-" * 54)
    print("  ACTION REQUIRED")
    print("-" * 54)
    print("  Connect your Wi-Fi adapter to:")
    print(f"    Network : {ssid}")
    print(f"    Password: {password}")
    print()
    print("  Waiting for Wi-Fi connection to GoPro", end="", flush=True)

    core.wait_until_reachable(poll_cb=lambda: print(".", end="", flush=True))
    print(" connected!")
    print()


def download_with_bar(file_info: dict, dest_path: Path) -> str:
    """core.download_file wrapped in a tqdm progress bar."""
    bar: tqdm | None = None

    def progress(done: int, total: int) -> None:
        nonlocal bar
        if bar is None:
            bar = tqdm(
                desc=f"  {file_info['name']}",
                total=total,
                unit="B",
                unit_scale=True,
                unit_divisor=1024,
                ncols=80,
            )
        bar.update(done - bar.n)

    try:
        return core.download_file(file_info, dest_path, progress_cb=progress)
    finally:
        if bar is not None:
            bar.close()


def transcode_with_bar(src: Path, dest: Path) -> str:
    """core.transcode_to_1080p wrapped in a tqdm progress bar."""
    bar: tqdm | None = None

    def progress(seconds: float, total: float | None) -> None:
        nonlocal bar
        if bar is None:
            bar = tqdm(
                total=int(total) if total else None,
                desc=f"  Transcoding {src.name}",
                unit="s",
                bar_format="{desc}: {percentage:3.0f}%|{bar}| {n}/{total}s",
                ncols=80,
                leave=True,
            )
        if int(seconds) > bar.n:
            bar.update(int(seconds) - bar.n)

    try:
        return core.transcode_to_1080p(src, dest, progress_cb=progress)
    finally:
        if bar is not None:
            bar.close()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GoPro Hero 11 Mini Offload Tool - BLE wake, Wi-Fi download, FFmpeg transcode"
    )
    parser.add_argument(
        "--output-dir", "-o", type=Path, default=Path("F:/gopro"),
        help="Root output directory (default: F:/gopro)",
    )
    parser.add_argument(
        "--skip-ble", "--no-ble", action="store_true",
        help="Skip Bluetooth wake - use if Wi-Fi is already enabled on the GoPro",
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
    parser.add_argument(
        "--keep-originals", action="store_true",
        help="Keep raw downloads after transcoding (skips the interactive prompt)",
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
        core.check_ffmpeg()

    raw_dir        = args.output_dir / "raw"
    transcoded_dir = args.output_dir / "transcoded"

    # 1. BLE wake + read Wi-Fi credentials
    if not args.skip_ble:
        try:
            import bleak  # noqa: F401
        except ImportError:
            log.error("'bleak' library not installed. Run: pip install bleak")
            log.error("Or skip BLE with --skip-ble if Wi-Fi is already on.")
            sys.exit(1)

        ssid, password = asyncio.run(core.ble_enable_wifi(args.ble_address))
        if ssid is None:
            log.error("BLE wake failed. Enable Wi-Fi manually and re-run with --skip-ble.")
            sys.exit(1)

        prompt_wifi_connect(ssid, password)
    else:
        log.info("Skipping Bluetooth wake (--skip-ble).")
        if not core.check_gopro_connection():
            sys.exit(1)

    # 2. Get file list
    log.info("Fetching media list from GoPro...")
    files = core.get_media_list()
    if not files:
        log.info("No media files found on GoPro.")
        sys.exit(0)

    log.info("Found %d file(s) on GoPro.", len(files))

    # 3. List mode
    if args.list:
        print(f"\n{'Filename':<30} {'Size':>10} {'Length':>8}")
        print("-" * 51)
        for f in files:
            print(
                f"{f['name']:<30} "
                f"{f['size'] / (1024*1024):>9.1f} MB "
                f"{core.human_duration(f['duration']):>8}"
            )
        sys.exit(0)

    # 4. Download
    raw_dir.mkdir(parents=True, exist_ok=True)
    log.info("Saving files to: %s", raw_dir)

    counts           = {"downloaded": 0, "skipped": 0, "error": 0}
    downloaded_files : list[tuple[dict, Path]] = []

    with core.KeepAlive():
        log.info("Keepalive started - GoPro will stay awake during transfer.")
        for f in files:
            dest = raw_dir / f["name"]
            if args.all and dest.exists():
                dest.unlink()
            result = download_with_bar(f, dest)
            counts[result] += 1
            if result == "skipped":
                log.info("  %s - already exists, skipping", f["name"])
            elif result == "downloaded":
                downloaded_files.append((f, dest))

    # 5. Delete from camera (only successfully downloaded files)
    if not args.no_delete:
        for f, _ in downloaded_files:
            if core.delete_file(f):
                log.info("  %s - deleted from GoPro", f["name"])
            else:
                log.warning("  %s - could not delete from GoPro", f["name"])
    else:
        log.info("Skipping camera deletion (--no-delete).")

    # 6. Transcode to 1080p
    transcode_errors: list[str] = []
    if not args.no_transcode:
        mp4s = [(f, dest) for f, dest in downloaded_files if f["kind"] == "video"]
        if mp4s:
            transcoded_dir.mkdir(parents=True, exist_ok=True)
            if args.keep_originals:
                keep = True
                log.info("Keeping original (raw) videos (--keep-originals).")
            else:
                keep = input(
                    "Keep original (raw) videos after transcoding? [y/N] "
                ).strip().lower() == "y"
            log.info("Transcoding %d video(s) to 1080p...", len(mp4s))
            for f, src in mp4s:
                if src.exists() and src.stat().st_size == f["size"]:
                    out    = transcoded_dir / src.name
                    result = transcode_with_bar(src, out)
                    if result == "transcoded" and not keep:
                        src.unlink()
                    elif result == "error":
                        transcode_errors.append(src.name)
                else:
                    log.warning("  %s - file size mismatch, skipping transcode", f["name"])

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
            input("Press ENTER to close...")
