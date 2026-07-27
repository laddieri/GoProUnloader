package com.gopro.unloader.model

data class MediaFile(
    val name: String,
    val directory: String,
    val size: Long,
    val url: String,
    var duration: Long = 0L,          // seconds, from /gopro/media/info
    var selected: Boolean = true,
    var downloadStatus: DownloadStatus = DownloadStatus.PENDING,
    var transcodeStatus: TranscodeStatus = TranscodeStatus.PENDING,
    var downloadProgress: Int = 0,
    var transcodeProgress: Int = 0,
    var localPath: String? = null,
    /** URL of the .THM sidecar the camera stores beside a clip, if it has one. */
    var thumbUrl: String? = null,
    /** URL of the low-res .LRV proxy, if the camera made one. */
    var proxyUrl: String? = null
) {
    /** Path as the camera's API wants it, e.g. "100GOPRO/GX010042.MP4". */
    val cameraPath: String get() = "$directory/$name"

    val isVideo: Boolean get() = name.uppercase().endsWith(".MP4")

    val isPhoto: Boolean get() = name.uppercase().endsWith(".JPG")

    /** True for the .LRV/.THM sidecars, which are hidden from the file list. */
    val isSidecar: Boolean get() = name.uppercase().let {
        it.endsWith(".LRV") || it.endsWith(".THM")
    }

    /**
     * The digits shared by a clip and its sidecars: GX010042.MP4 and
     * GL010042.LRV both yield "010042". Null if the name isn't in that form.
     */
    val clipId: String? get() = CLIP_ID.matchEntire(name)?.groupValues?.get(1)

    private companion object {
        val CLIP_ID = Regex("^G[A-Z](\\d+)\\.[A-Za-z0-9]+$")
    }
}

enum class DownloadStatus {
    PENDING, DOWNLOADING, DOWNLOADED, SKIPPED, ERROR
}

enum class TranscodeStatus {
    PENDING, TRANSCODING, DONE, SKIPPED, NOT_APPLICABLE, ERROR
}
