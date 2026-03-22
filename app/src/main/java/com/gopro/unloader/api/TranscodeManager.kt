package com.gopro.unloader.api

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import java.io.File

private const val TAG = "TranscodeManager"

class TranscodeManager {

    /**
     * Transcodes [src] to 1080p H.264/AAC and saves the output to
     * [transcodeDir]/[src.name].
     *
     * Progress is reported via [onProgress] (0–100).  The output file is
     * returned on success, or null on failure/skip.
     *
     * Videos that are already ≤1080p are skipped (returns null).
     * If [destFile] already exists it is also skipped.
     */
    fun transcodeTo1080p(
        src: File,
        transcodeDir: File,
        onProgress: (Int) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): File? {
        transcodeDir.mkdirs()
        val dest = File(transcodeDir, src.name)

        if (dest.exists()) {
            onStatus("${src.name} — 1080p copy already exists, skipping.")
            onProgress(100)
            return null
        }

        // Probe height
        val height = probeHeight(src)
        if (height == null) {
            onStatus("${src.name} — could not determine resolution, skipping transcode.")
            return null
        }
        if (height <= 1080) {
            onStatus("${src.name} — already ${height}p, skipping transcode.")
            onProgress(100)
            return null
        }

        val duration = probeDuration(src)
        onStatus("${src.name} — transcoding ${height}p → 1080p…")

        // Register statistics callback for progress
        val statsCallback = StatisticsCallback { stats ->
            if (duration != null && duration > 0) {
                val pct = ((stats.time / 1000.0 / duration) * 100).toInt().coerceIn(0, 99)
                onProgress(pct)
            }
        }
        FFmpegKitConfig.enableStatisticsCallback(statsCallback)

        val cmd = buildString {
            append("-y ")
            append("-i \"${src.absolutePath}\" ")
            append("-vf \"scale=1920:1080:force_original_aspect_ratio=decrease,")
            append("pad=1920:1080:(ow-iw)/2:(oh-ih)/2\" ")
            append("-c:v libx264 -preset fast -crf 23 ")
            append("-c:a aac -b:a 192k ")
            append("-movflags +faststart ")
            append("\"${dest.absolutePath}\"")
        }

        val session = FFmpegKit.execute(cmd)

        FFmpegKitConfig.enableStatisticsCallback(null)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            onProgress(100)
            val sizeMb = dest.length() / (1024 * 1024)
            onStatus("${src.name} — 1080p copy saved (${sizeMb} MB).")
            dest
        } else {
            Log.e(TAG, "FFmpeg failed for ${src.name}: ${session.logsAsString}")
            onStatus("${src.name} — transcode failed.")
            if (dest.exists()) dest.delete()
            null
        }
    }

    private fun probeHeight(file: File): Int? {
        val session = FFprobeKit.execute(
            "-v error -select_streams v:0 -show_entries stream=height " +
                    "-of csv=p=0 \"${file.absolutePath}\""
        )
        val output = session.output?.trim() ?: return null
        return output.toIntOrNull()
    }

    private fun probeDuration(file: File): Double? {
        val session = FFprobeKit.execute(
            "-v error -show_entries format=duration " +
                    "-of csv=p=0 \"${file.absolutePath}\""
        )
        val output = session.output?.trim() ?: return null
        return output.toDoubleOrNull()
    }
}
