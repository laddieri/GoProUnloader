package com.gopro.unloader.api

import android.net.Network
import android.util.Log
import com.gopro.unloader.model.CameraInfo
import com.gopro.unloader.model.MediaFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val TAG = "GoProApi"

class GoProApi {

    companion object {
        const val GOPRO_BASE = "http://10.5.5.9:8080"
        const val MEDIA_LIST_URL = "$GOPRO_BASE/gopro/media/list"
        const val MEDIA_DELETE_URL = "$GOPRO_BASE/gopro/media/delete/file"
        const val MEDIA_BASE_URL = "$GOPRO_BASE/videos/DCIM"
        const val MEDIA_INFO_URL = "$GOPRO_BASE/gopro/media/info"
        const val MEDIA_THUMBNAIL_URL = "$GOPRO_BASE/gopro/media/thumbnail"
        const val MEDIA_SCREENNAIL_URL = "$GOPRO_BASE/gopro/media/screennail"
        const val CAMERA_STATE_URL = "$GOPRO_BASE/gopro/camera/state"
        const val KEEP_ALIVE_URL = "$GOPRO_BASE/gopro/camera/keep_alive"
        const val SHUTTER_START_URL = "$GOPRO_BASE/gopro/camera/shutter/start"
        const val SHUTTER_STOP_URL = "$GOPRO_BASE/gopro/camera/shutter/stop"
    }

    private var client = buildClient(30, 60, null)
    private var pingClient = buildClient(3, 3, null)

    /**
     * Rebuild HTTP clients to route traffic through the given network.
     * Must be called after WiFi connects so OkHttp uses the GoPro network's
     * socket factory instead of the default (which stays on mobile/home WiFi).
     */
    fun bindToNetwork(network: Network) {
        val factory = network.socketFactory
        client = buildClient(30, 60, factory)
        pingClient = buildClient(3, 3, factory)
        Log.d(TAG, "HTTP clients bound to network $network")
    }

    /** Reset HTTP clients to the default socket factory. */
    fun unbindNetwork() {
        client = buildClient(30, 60, null)
        pingClient = buildClient(3, 3, null)
        Log.d(TAG, "HTTP clients unbound from network")
    }

    private fun buildClient(connectSec: Long, readSec: Long, factory: SocketFactory?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
        if (factory != null) builder.socketFactory(factory)
        return builder.build()
    }

    /** Returns true if the GoPro is reachable over WiFi. Uses fast timeouts. */
    fun isCameraReachable(): Boolean {
        return try {
            val request = Request.Builder().url(CAMERA_STATE_URL).build()
            pingClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetches battery %, remaining SD space (status 54, in KB), and remaining
     * recording time (status 70, in seconds) in a single camera-state call.
     * Returns null if the camera is unreachable.
     */
    fun getCameraInfo(): CameraInfo? {
        return try {
            val request = Request.Builder().url(CAMERA_STATE_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val status = JSONObject(response.body?.string() ?: return null)
                    .optJSONObject("status") ?: return null

                val battery = status.optInt("_2", -1)
                // Status 54: remaining space in KB (uint64 in spec, fits Long)
                val spaceKb = status.optLong("_54", -1L)
                val spaceMb = if (spaceKb >= 0) spaceKb / 1024L else -1L
                // Status 70: remaining video time in seconds for current mode
                val videoSec = status.optLong("_70", -1L)

                CameraInfo(
                    batteryPercent = battery,
                    remainingSpaceMb = spaceMb,
                    remainingVideoSec = videoSec
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCameraInfo failed", e)
            null
        }
    }

    /** Returns battery level (0–100) or null if not available. */
    fun getBatteryLevel(): Int? {
        return try {
            val request = Request.Builder().url(CAMERA_STATE_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                json.optJSONObject("status")?.optInt("_2")
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fetches the list of MP4 (and auxiliary) files from the GoPro. */
    fun getMediaList(): List<MediaFile> {
        return try {
            val request = Request.Builder().url(MEDIA_LIST_URL).build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Media list request failed: ${response.code}")
                    return emptyList()
                }
                response.body?.string()
            } ?: return emptyList()

            val files = mutableListOf<MediaFile>()
            val json = JSONObject(body)
            val media = json.optJSONArray("media") ?: return emptyList()

            for (i in 0 until media.length()) {
                val folder = media.getJSONObject(i)
                val directory = folder.getString("d")
                val fileArray = folder.optJSONArray("fs") ?: continue

                for (j in 0 until fileArray.length()) {
                    val f = fileArray.getJSONObject(j)
                    val name = f.getString("n")
                    if (!name.uppercase().endsWith(".MP4") &&
                        !name.uppercase().endsWith(".LRV") &&
                        !name.uppercase().endsWith(".THM")
                    ) continue

                    val size = f.optLong("s", 0L)
                    val url = "$MEDIA_BASE_URL/$directory/$name"
                    files.add(MediaFile(name = name, directory = directory, size = size, url = url))
                }
            }
            pairSidecars(files)
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching media list", e)
            emptyList()
        }
    }

    /**
     * Links each clip to the .LRV proxy and .THM thumbnail the camera writes
     * beside it, matching on the digits they share.
     */
    private fun pairSidecars(files: List<MediaFile>) {
        val proxies = mutableMapOf<String, MediaFile>()
        val thumbs = mutableMapOf<String, MediaFile>()
        for (f in files) {
            val id = f.clipId ?: continue
            when {
                f.name.uppercase().endsWith(".LRV") -> proxies[id] = f
                f.name.uppercase().endsWith(".THM") -> thumbs[id] = f
            }
        }
        for (f in files) {
            if (f.isSidecar) continue
            val id = f.clipId ?: continue
            f.proxyUrl = proxies[id]?.url
            f.thumbUrl = thumbs[id]?.url
        }
    }

    /**
     * Fetches a JPEG preview frame for [file], trying every source the camera
     * offers until one returns a real image.
     *
     * The thumbnail endpoints aren't reliable across firmware versions, so
     * this falls back to the .THM sidecar, which is a plain JPEG served over
     * the same path as the downloads themselves.
     *
     * The path keeps its "/" un-escaped: the camera answers HTTP 400 to a
     * percent-encoded separator.
     */
    fun getThumbnail(file: MediaFile, large: Boolean = false): ByteArray? {
        val endpoints = if (large) {
            listOf(MEDIA_SCREENNAIL_URL, MEDIA_THUMBNAIL_URL)
        } else {
            listOf(MEDIA_THUMBNAIL_URL, MEDIA_SCREENNAIL_URL)
        }

        val sources = mutableListOf<String>()
        endpoints.forEach { sources.add("$it?path=${file.cameraPath}") }
        // Some firmware only accepts the DCIM-prefixed form.
        endpoints.forEach { sources.add("$it?path=DCIM/${file.cameraPath}") }
        file.thumbUrl?.let { sources.add(it) }

        for (url in sources) {
            val bytes = fetchJpeg(url)
            if (bytes != null) return bytes
        }
        Log.w(TAG, "No thumbnail available for ${file.name}")
        return null
    }

    /** GETs a URL and returns the body only if it really is a JPEG. */
    private fun fetchJpeg(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Thumbnail source ${response.code}: $url")
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                // The camera sometimes answers 200 with a JSON error body.
                if (bytes.size < 2 ||
                    bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()
                ) {
                    Log.d(TAG, "Thumbnail source returned non-JPEG: $url")
                    return null
                }
                bytes
            }
        } catch (e: Exception) {
            Log.d(TAG, "Thumbnail source failed ($url): ${e.message}")
            null
        }
    }

    /**
     * Fetches duration (in seconds) for a single file via /gopro/media/info.
     * Returns 0 if unavailable.
     */
    fun getMediaInfo(directory: String, name: String): Long {
        return try {
            val url = "$MEDIA_INFO_URL?path=$directory/$name"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L
                val json = JSONObject(response.body?.string() ?: return 0L)
                json.optString("dur", "0").toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMediaInfo failed for $name", e)
            0L
        }
    }

    /**
     * Deletes a file from the GoPro camera.
     * Tries two path formats for firmware compatibility.
     * Returns true on success.
     */
    fun deleteFile(file: MediaFile): Boolean {
        val paths = listOf(
            "${file.directory}/${file.name}",
            "DCIM/${file.directory}/${file.name}"
        )
        for (path in paths) {
            try {
                val url = "$MEDIA_DELETE_URL?path=$path"
                val request = Request.Builder().url(url).build()
                val success = client.newCall(request).execute().use { it.isSuccessful }
                if (success) {
                    Log.d(TAG, "Deleted ${file.name} from camera")
                    return true
                }
            } catch (e: IOException) {
                Log.w(TAG, "Delete attempt failed for path=$path: ${e.message}")
            }
        }
        Log.w(TAG, "Failed to delete ${file.name} from camera")
        return false
    }

    /**
     * Deletes [file] along with the .LRV/.THM sidecars the camera keeps beside
     * it. Removing only the .MP4 leaves those behind, so the card never fully
     * frees up. Returns true only if every part went.
     */
    fun deleteFileWithSidecars(file: MediaFile, allFiles: List<MediaFile>): Boolean {
        var ok = deleteFile(file)
        val id = file.clipId
        if (id != null) {
            for (other in allFiles) {
                if (other !== file && other.isSidecar && other.clipId == id) {
                    if (!deleteFile(other)) ok = false
                }
            }
        }
        return ok
    }

    /**
     * Returns true if the camera is currently recording, false if idle, or null if unreachable.
     * Uses status field 8 (encodingActive) from the camera state endpoint.
     */
    fun isRecording(): Boolean? {
        return try {
            val request = Request.Builder().url(CAMERA_STATE_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val status = JSONObject(body).optJSONObject("status") ?: return null
                status.optInt("_8", 0) == 1
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Sends the shutter/start command to begin recording. Returns true on success. */
    fun startRecording(): Boolean {
        return try {
            val request = Request.Builder().url(SHUTTER_START_URL).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            false
        }
    }

    /** Sends the shutter/stop command to stop recording. Returns true on success. */
    fun stopRecording(): Boolean {
        return try {
            val request = Request.Builder().url(SHUTTER_STOP_URL).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            false
        }
    }

    /**
     * Sends a keepalive ping. Call periodically during downloads.
     *
     * Uses the dedicated keep_alive endpoint: polling camera/state does not
     * reset the camera's sleep timer, so it powers off mid-transfer.
     */
    fun keepAlive() {
        try {
            val request = Request.Builder().url(KEEP_ALIVE_URL).build()
            pingClient.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }
}
