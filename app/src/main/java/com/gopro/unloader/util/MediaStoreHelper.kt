package com.gopro.unloader.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

private const val TAG = "MediaStoreHelper"
private const val SUBFOLDER = "GoProUnloader"

/**
 * Publishes a video file into the shared Movies folder so it is visible
 * to gallery apps and file managers.
 *
 * - API 29+: uses MediaStore (required; direct File writes to public dirs are blocked)
 * - API < 29: copies directly to getExternalStoragePublicDirectory(MOVIES)
 */
object MediaStoreHelper {

    fun addVideoToGallery(context: Context, file: File, subfolder: String = SUBFOLDER): Boolean {
        if (!file.exists()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addViaMediaStore(context, file, subfolder)
        } else {
            addLegacy(file, subfolder)
        }
    }

    private fun addViaMediaStore(context: Context, file: File, subfolder: String): Boolean {
        val mimeType = if (file.name.uppercase().endsWith(".MP4")) "video/mp4" else "video/*"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$subfolder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: run {
            Log.e(TAG, "MediaStore insert returned null for ${file.name}")
            return false
        }

        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            Log.d(TAG, "Published to MediaStore: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${file.name} to MediaStore", e)
            context.contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun addLegacy(file: File, subfolder: String): Boolean {
        return try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val destDir = File(moviesDir, subfolder).also { it.mkdirs() }
            file.copyTo(File(destDir, file.name), overwrite = true)
            Log.d(TAG, "Copied to public Movies: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy ${file.name} to public Movies", e)
            false
        }
    }
}
