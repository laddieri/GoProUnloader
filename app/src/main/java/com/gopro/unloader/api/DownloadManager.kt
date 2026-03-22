package com.gopro.unloader.api

import android.net.Network
import android.util.Log
import com.gopro.unloader.model.DownloadStatus
import com.gopro.unloader.model.MediaFile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "DownloadManager"
private const val CHUNK_SIZE = 65_536 // 64 KiB

class DownloadManager {

    private var client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Rebuild the HTTP client to route downloads through the given network. */
    fun bindToNetwork(network: Network) {
        client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun unbindNetwork() {
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Downloads [file] to [destDir]/raw/[file.name].
     * Reports progress via [onProgress] (0–100).
     * Returns the downloaded [File] on success, or null on failure/skip.
     */
    fun download(
        file: MediaFile,
        destDir: File,
        forceRedownload: Boolean = false,
        onProgress: (Int) -> Unit = {}
    ): File? {
        val rawDir = File(destDir, "raw").also { it.mkdirs() }
        val dest = File(rawDir, file.name)

        if (!forceRedownload && dest.exists() && dest.length() == file.size) {
            Log.d(TAG, "${file.name} already exists, skipping.")
            onProgress(100)
            return dest
        }

        if (dest.exists()) dest.delete()

        return try {
            val request = Request.Builder().url(file.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code} downloading ${file.name}")
                    return null
                }

                val total = response.body?.contentLength()?.takeIf { it > 0 } ?: file.size
                var downloaded = 0L

                FileOutputStream(dest).use { out ->
                    val body = response.body ?: return null
                    val buffer = ByteArray(CHUNK_SIZE)
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                            onProgress(pct.coerceIn(0, 99))
                        }
                    }
                }

                onProgress(100)
                Log.d(TAG, "Downloaded ${file.name} (${dest.length() / 1024} KB)")
                dest
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading ${file.name}", e)
            if (dest.exists()) dest.delete()
            null
        }
    }
}
