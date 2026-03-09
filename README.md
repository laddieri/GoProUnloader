# GoPro Unloader

A Python script that automates the full offload workflow for a **GoPro Hero 11 Mini**:

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
| `--output-dir`, `-o` | `./gopro_output` | Directory for downloaded and transcoded files |
| `--keep-originals` | off | Keep raw downloaded files alongside transcoded versions |
| `--no-delete` | off | Download videos but do **not** delete them from the camera |
| `--skip-ble` | off | Skip Bluetooth wake (camera is already awake) |
| `--ble-address` | *(auto)* | Manually specify the GoPro BLE address |

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
```

---

## Output Structure

```
gopro_output/
├── raw/          # Original files downloaded from camera (deleted after transcode
│                 # unless --keep-originals is set)
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

## Troubleshooting

| Problem | Solution |
|---|---|
| Camera not found via BLE | Ensure Bluetooth is on and camera is within ~10 m |
| WiFi not reachable | Connect your computer to the GoPro WiFi AP first |
| `ffmpeg not found` | Install FFmpeg and ensure it is on your `PATH` |
| Download fails mid-way | Re-run the script; already-deleted files are gone but untouched files can be retried |

---

## License

MIT
