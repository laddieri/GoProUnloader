package com.gopro.unloader.api

import android.util.Log
import com.gopro.unloader.model.CameraInfo
import com.gopro.unloader.model.MediaFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "GoProApi"

class GoProApi {

    companion object {
        const val GOPRO_BASE = "http://10.5.5.9:8080"
        const val MEDIA_LIST_URL = "$GOPRO_BASE/gopro/media/list"
        const val MEDIA_DELETE_URL = "$GOPRO_BASE/gopro/media/delete/file"
        const val MEDIA_BASE_URL = "$GOPRO_BASE/videos/DCIM"
        const val CAMERA_STATE_URL = "$GOPRO_BASE/gopro/camera/state"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns true if the GoPro is reachable over WiFi. */
    fun isCameraReachable(): Boolean {
        return try {
            val request = Request.Builder().url(CAMERA_STATE_URL).build()
            client.newCall(request).execute().use { it.isSuccessful }
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
                    val duration = f.optString("dur", "0").toLongOrNull() ?: 0L
                    val url = "$MEDIA_BASE_URL/$directory/$name"
                    files.add(MediaFile(name = name, directory = directory, size = size, url = url, duration = duration))
                }
            }
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching media list", e)
            emptyList()
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

    /** Sends a keepalive ping. Call periodically during downloads. */
    fun keepAlive() {
        try {
            val request = Request.Builder().url(CAMERA_STATE_URL).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }
}
