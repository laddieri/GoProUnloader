package com.gopro.unloader.model

/**
 * Snapshot of GoPro camera stats fetched from the camera state endpoint.
 *
 * @param batteryPercent   Battery level 0–100, or -1 if unavailable.
 * @param remainingSpaceMb Free SD card space in MB, or -1 if unavailable.
 * @param remainingVideoSec Remaining recording time in seconds (status 70, current mode).
 *                          -1 if not reported by the camera — caller should fall back to
 *                          [estimatedStorageVideoSec].
 */
data class CameraInfo(
    val batteryPercent: Int,
    val remainingSpaceMb: Long,
    val remainingVideoSec: Long
) {
    /**
     * Rough battery recording estimate: ~1.2 min per percent (≈120 min at 100%).
     * Returns -1 if battery level is unknown.
     */
    val estimatedBatteryVideoSec: Long
        get() = if (batteryPercent >= 0) (batteryPercent * 72L) else -1L  // 72 s/percent = 120 min total

    /**
     * Storage estimate when status 70 is unavailable: assumes ~60 Mbps (1080p/60fps).
     * 60 Mbps = 7 500 KB/s.  Returns -1 if space is unknown.
     */
    val estimatedStorageVideoSec: Long
        get() = if (remainingSpaceMb >= 0) (remainingSpaceMb * 1024L / 7500L) else -1L

    /** Whichever recording-time figure to show for storage (prefer camera-reported). */
    val storageVideoSec: Long
        get() = if (remainingVideoSec >= 0) remainingVideoSec else estimatedStorageVideoSec
}
