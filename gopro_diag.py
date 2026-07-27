#!/usr/bin/env python3
"""
GoPro thumbnail diagnostics
----------------------------
Works out where thumbnails can actually be fetched from on *your* camera and
firmware, by trying every source against the first few files on the card and
reporting exactly what came back.

Connect this PC to the camera's Wi-Fi first (or run gopro_gui.py's Bluetooth
step and leave it connected), then:

    python gopro_diag.py

Paste the output into a bug report if the grid still comes up empty.
"""

import argparse
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

import gopro_core as core


def describe(response: requests.Response) -> str:
    """One-line summary of what the camera actually sent back."""
    ctype = response.headers.get("Content-Type", "?")
    body  = response.content
    if body[:2] == b"\xff\xd8":
        shape = f"JPEG, {len(body)} bytes"
    else:
        head = body[:70].decode("utf-8", "replace").strip().replace("\n", " ")
        shape = f"NOT JPEG, {len(body)} bytes, starts {head!r}"
    return f"HTTP {response.status_code}  {ctype}  {shape}"


def probe(label: str, url: str, params: dict | None = None) -> bool:
    try:
        start = time.monotonic()
        r = requests.get(url, params=params, timeout=15)
        elapsed = time.monotonic() - start
        ok = r.status_code == 200 and r.content[:2] == b"\xff\xd8"
        mark = "OK  " if ok else "FAIL"
        print(f"    [{mark}] {label:<22} {describe(r)}  ({elapsed:.1f}s)")
        return ok
    except Exception as e:
        print(f"    [FAIL] {label:<22} {type(e).__name__}: {e}")
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description="Diagnose GoPro thumbnail fetching")
    parser.add_argument("--count", type=int, default=3,
                        help="How many files to probe (default: 3)")
    args = parser.parse_args()

    print("=" * 68)
    print("  GoPro thumbnail diagnostics")
    print("=" * 68)

    print(f"\nCamera base URL: {core.GOPRO_BASE}")
    if not core.is_reachable(timeout=5):
        print("\nCamera is NOT reachable.")
        print("  Connect this PC's Wi-Fi to the camera's hotspot and re-run.")
        sys.exit(1)

    battery = core.get_battery_percent()
    print(f"Reachable. Battery: {battery if battery is not None else '?'}%")

    files = core.get_media_list()
    media = [f for f in files if f["kind"] in ("video", "photo")]
    print(f"\nMedia list: {len(files)} entries, {len(media)} clips/photos")

    kinds: dict[str, int] = {}
    for f in files:
        kinds[f["kind"]] = kinds.get(f["kind"], 0) + 1
    print(f"  by kind: {kinds}")
    if kinds.get("thumb", 0) == 0:
        print("  NOTE: the card has no .THM sidecars, so that fallback is "
              "unavailable.")

    if not media:
        print("\nNothing on the card to probe.")
        sys.exit(0)

    print("\n" + "-" * 68)
    print("Probing each thumbnail source, one request at a time")
    print("-" * 68)

    tally = {"thumbnail endpoint": 0, "screennail endpoint": 0, ".THM sidecar": 0}
    for f in media[:args.count]:
        print(f"\n  {f['name']}   (path={f['path']})")
        if probe("thumbnail endpoint", core.MEDIA_THUMBNAIL_URL, {"path": f["path"]}):
            tally["thumbnail endpoint"] += 1
        if probe("screennail endpoint", core.MEDIA_SCREENNAIL_URL, {"path": f["path"]}):
            tally["screennail endpoint"] += 1
        if f.get("thumb_url"):
            if probe(".THM sidecar", f["thumb_url"]):
                tally[".THM sidecar"] += 1
        else:
            print("    [SKIP] .THM sidecar          no .THM for this file")

        # Some firmwares only accept the DCIM-prefixed form.
        probe("thumbnail w/ DCIM", core.MEDIA_THUMBNAIL_URL,
              {"path": f"DCIM/{f['path']}"})

    print("\n" + "-" * 68)
    print("Concurrency check (4 parallel thumbnail requests)")
    print("-" * 68)
    target = media[0]
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(
            lambda _: core.get_thumbnail(target),
            range(4),
        ))
    good = sum(1 for r in results if r)
    print(f"  {good}/4 parallel requests returned a usable image")
    if good < 4:
        print("  -> The camera drops parallel requests. The GUI already sends "
              "them one at a time.")

    print("\n" + "=" * 68)
    print("  Summary")
    print("=" * 68)
    probed = len(media[:args.count])
    for label, count in tally.items():
        print(f"  {label:<22} worked for {count}/{probed} file(s)")
    if not any(tally.values()):
        print("\n  No source worked. Please paste this whole output into an issue.")
    else:
        best = max(tally, key=lambda k: tally[k])
        print(f"\n  The GUI will use: {best} (it tries all of the above in order).")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled.")
    finally:
        print()
        if sys.platform == "win32":
            input("Press ENTER to close...")
