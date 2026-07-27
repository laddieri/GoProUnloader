package com.gopro.unloader.model

/**
 * An immutable snapshot of one row in the file list.
 *
 * The list adapter renders these rather than [MediaFile] itself. [MediaFile]
 * is mutable and shared with the transfer code, so the adapter's previous and
 * next lists would hold the very same objects: DiffUtil would compare an item
 * against itself, conclude nothing had changed, and never rebind the row. A
 * fresh snapshot per emission gives it something real to compare.
 */
data class MediaRow(
    val directory: String,
    val name: String,
    val cameraPath: String,
    val thumbUrl: String?,
    val sizeText: String,
    val durationText: String?,
    val selected: Boolean,
    val selectable: Boolean,
    val statusText: String?,
    /** Progress 0-100 while working, or null when no bar should show. */
    val progress: Int?
)

/** Builds the row the list should currently show for this file. */
fun MediaFile.toRow(): MediaRow {
    val status: String?
    val progress: Int?
    when {
        downloadStatus == DownloadStatus.DOWNLOADING -> {
            status = "Downloading… $downloadProgress%"
            progress = downloadProgress
        }
        transcodeStatus == TranscodeStatus.TRANSCODING -> {
            status = "Transcoding… $transcodeProgress%"
            progress = transcodeProgress
        }
        downloadStatus == DownloadStatus.ERROR -> {
            status = "Error"
            progress = null
        }
        downloadStatus == DownloadStatus.SKIPPED -> {
            status = "Skipped (already on phone)"
            progress = null
        }
        transcodeStatus == TranscodeStatus.DONE -> {
            status = "Done ✓"
            progress = null
        }
        downloadStatus == DownloadStatus.DOWNLOADED -> {
            status = "Downloaded"
            progress = null
        }
        else -> {
            status = null
            progress = null
        }
    }

    return MediaRow(
        directory = directory,
        name = name,
        cameraPath = cameraPath,
        thumbUrl = thumbUrl,
        sizeText = formatSize(size),
        durationText = formatDuration(duration),
        selected = selected,
        // Locked once the file starts moving, so a tick mid-transfer can't
        // contradict what is already happening.
        selectable = downloadStatus == DownloadStatus.PENDING,
        statusText = status,
        progress = progress
    )
}

private fun formatDuration(seconds: Long): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
