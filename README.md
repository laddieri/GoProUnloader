# GoPro Unloader

A **Python script** and **Android app** that automate the full offload workflow for a **GoPro Hero 11 Mini**:

1. **Wakes** the camera via Bluetooth LE
2. **Downloads** all videos over WiFi using the [GoPro Open API](https://gopro.github.io/OpenGoPro/)
3. **Deletes** the downloaded files from the camera
4. **Transcodes** them to 1080p H.264/AAC using FFmpeg

---

## Requirements

| Dependency | Purpose |
|---|---|
| Python 3.11+ | Runtime |
| [`bleak`](https://github.com/hbldh/bleak) | Bluetooth LE communication |
| [`requests`](https://docs.python-requests.org/) | HTTP downloads & GoPro API |
| [`tqdm`](https://github.com/tqdm/tqdm) | Download & transcode progress bars |
| [`ffmpeg`](https://ffmpeg.org/) | Video transcoding (must be on `PATH`) |

### Install Python dependencies

```bash
pip install -r requirements.txt
```

### Install FFmpeg

```bash
# macOS
brew install ffmpeg

# Ubuntu / Debian
sudo apt install ffmpeg

# Windows – download from https://ffmpeg.org/download.html
```

---

## Setup

1. **Enable Bluetooth** on your computer.
2. **Enable WiFi** on your computer and connect to the GoPro's WiFi network
   (`SSID` and password are shown in the camera's *Connections → Connect Device* menu).
3. The script will handle waking the camera via BLE automatically.

> **Tip:** If your camera is already on and the WiFi is connected, use `--skip-ble`
> to bypass the Bluetooth step.

---

## Usage

```
python gopro_unloader.py [OPTIONS]
```

### Options

| Flag | Default | Description |
|---|---|---|
| `--output-dir`, `-o` | `F:/gopro` | Directory for downloaded and transcoded files |
| `--keep-originals` | off | Keep raw downloaded files alongside transcoded versions (skips the interactive prompt) |
| `--no-delete` | off | Download videos but do **not** delete them from the camera |
| `--no-transcode` | off | Skip the FFmpeg transcoding step entirely |
| `--all`, `-a` | off | Re-download every file, even ones that already exist locally |
| `--list`, `-l` | off | List the files on the camera and exit without downloading |
| `--skip-ble`, `--no-ble` | off | Skip Bluetooth wake (camera is already awake) |
| `--ble-address` | *(auto)* | Manually specify the GoPro BLE address |

If `--keep-originals` is not passed, the script asks once before transcoding whether
to keep the raw downloads.

### Examples

```bash
# Basic usage – download, delete from camera, transcode
python gopro_unloader.py

# Save to a custom directory and keep the raw files
python gopro_unloader.py --output-dir ~/Videos/GoPro --keep-originals

# Camera is already on; skip BLE scan
python gopro_unloader.py --skip-ble

# Download only, don't delete from camera
python gopro_unloader.py --no-delete

# See what's on the camera without downloading anything
python gopro_unloader.py --list
```

---

## Output Structure

```
F:/gopro/         # or whatever --output-dir points at
├── raw/          # Original files downloaded from camera (deleted after transcode
│                 # unless --keep-originals is set or you answer "y" at the prompt)
└── transcoded/   # 1080p H.264/AAC MP4 files
```

---

## How It Works

### 1. Bluetooth Wake
Uses `bleak` to scan for a device whose name starts with `"GoPro"`, connects, and writes a BLE command to the GoPro Command characteristic (`b5f90072-…`). This wakes the camera and brings up the WiFi Access Point.

### 2. WiFi Download
Polls `http://10.5.5.9:8080/gopro/media/list` until the camera's HTTP server responds, then iterates over all `.MP4` files and streams them down in 1 MiB chunks.

### 3. Delete from Camera
Sends `DELETE http://10.5.5.9:8080/gopro/media/delete/file?path=<folder>/<name>` for each successfully downloaded file.

### 4. FFmpeg Transcode
Calls FFmpeg with:
- Video: `libx264`, `fast` preset, CRF 23
- Scaling: letterbox/pillarbox to exactly `1920×1080`
- Audio: `aac` at 192 kbps
- Container flag: `+faststart` for streaming-friendly output

---

## Android App

The `app/` directory contains a native Android application that provides the same
offload workflow as the Python script, optimised for phones and tablets.

### Requirements

| Requirement | Details |
|---|---|
| Android 8.0+ (API 26) | Minimum supported OS |
| Bluetooth LE | Camera wake & WiFi credential reading |
| WiFi | File downloads from the GoPro hotspot |
| Storage | Files saved to `Android/data/com.gopro.unloader/files/Movies/GoProUnloader/` |

### Build

1. Open the `GoProUnloader` folder in **Android Studio Hedgehog** (or newer).
2. Let Gradle sync finish.
3. Connect a device (API 26+) or start an emulator with BLE support.
4. Run **▶ Run 'app'**.

### Usage

1. Tap **Start Offload** — the app will:
   - Scan for a nearby GoPro via Bluetooth LE and wake its WiFi AP.
   - Display the WiFi SSID and password on-screen.
   - Wait while you connect your phone to the GoPro's WiFi hotspot.
   - Download all new MP4 files with a per-file progress bar.
   - Delete downloaded files from the camera (unless **Don't delete** is checked).
   - Transcode each video to 1080p H.264/AAC using FFmpeg Kit.
2. Tap **List Files** to browse camera contents without downloading.
3. Tap the **⋮ Options** menu to toggle:
   - *Skip BLE* — if WiFi is already connected
   - *Keep originals* — retain raw downloads alongside 1080p copies
   - *Don't delete* — leave files on the camera
   - *Skip transcoding* — save raw downloads only
   - *Set BLE Address* — skip scanning if the camera's BLE address is known

### Output Structure

```
Android/data/com.gopro.unloader/files/Movies/GoProUnloader/
├── raw/          # Downloaded originals (removed after transcode unless --keep-originals)
└── transcoded/   # 1080p H.264/AAC MP4 files
```

---

## Troubleshooting

| Problem | Solution |
|---|---|
| Camera not found via BLE | Ensure Bluetooth is on and camera is within ~10 m |
| WiFi not reachable | Connect your computer/phone to the GoPro WiFi AP first |
| `ffmpeg not found` | Install FFmpeg and ensure it is on your `PATH` (Python) |
| Download fails mid-way | Re-run the script/app; already-deleted files are gone but untouched files can be retried |
| Android: BLE permission denied | Grant *Nearby devices* permission in Android settings |
| Android: transcoding fails | Ensure the phone has sufficient free storage |

---

## License

MIT
