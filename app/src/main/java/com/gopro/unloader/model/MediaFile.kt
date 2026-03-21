package com.gopro.unloader.model

data class MediaFile(
    val name: String,
    val directory: String,
    val size: Long,
    val url: String,
    val duration: Long = 0L,          // seconds, parsed from GoPro "dur" field
    var selected: Boolean = true,
    var downloadStatus: DownloadStatus = DownloadStatus.PENDING,
    var transcodeStatus: TranscodeStatus = TranscodeStatus.PENDING,
    var downloadProgress: Int = 0,
    var transcodeProgress: Int = 0,
    var localPath: String? = null
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, DOWNLOADED, SKIPPED, ERROR
}

enum class TranscodeStatus {
    PENDING, TRANSCODING, DONE, SKIPPED, NOT_APPLICABLE, ERROR
}
