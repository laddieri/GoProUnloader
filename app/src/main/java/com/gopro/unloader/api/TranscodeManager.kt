package com.gopro.unloader.api

import android.media.MediaMetadataRetriever
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.StatisticsCallback
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

    /**
     * Use Android's [MediaMetadataRetriever] to get video height.
     * This is more reliable than ffprobe, which may not be bundled
     * in minimal ffmpegkit packages.
     */
    private fun probeHeight(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "probeHeight failed for ${file.name}", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Use Android's [MediaMetadataRetriever] to get video duration in seconds.
     */
    private fun probeDuration(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { it / 1000.0 }
        } catch (e: Exception) {
            Log.e(TAG, "probeDuration failed for ${file.name}", e)
            null
        } finally {
            retriever.release()
        }
    }
}
