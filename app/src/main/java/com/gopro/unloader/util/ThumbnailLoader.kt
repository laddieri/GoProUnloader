package com.gopro.unloader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.gopro.unloader.api.GoProApi
import com.gopro.unloader.model.MediaFile
import java.util.concurrent.Executors

/**
 * Loads camera thumbnails into list rows.
 *
 * Fetches run on a *single* background thread on purpose: the camera's HTTP
 * server drops requests that arrive in parallel, which shows up as rows that
 * never get a picture. Decoded bitmaps are cached, so scrolling back doesn't
 * hit the camera again.
 *
 * Takes the [GoProApi] the rest of the app is using rather than making its
 * own, so thumbnail requests go over the same network binding as everything
 * else - the phone stays on mobile data otherwise and can't reach the camera.
 */
class ThumbnailLoader(private val api: GoProApi) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Roughly 4 MB of decoded thumbnails, plenty for a card full of clips. */
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Names we already tried and failed, so we don't retry on every rebind. */
    private val failed = mutableSetOf<String>()

    /**
     * Puts a thumbnail for [file] into [view].
     *
     * Safe to call from onBindViewHolder: the view is tagged with the file it
     * is waiting for, so a recycled row never shows another clip's picture.
     * Exactly one of [onLoaded] / [onMissing] runs on the main thread, which
     * is how the caller knows when to take its placeholder down.
     */
    fun load(
        file: MediaFile,
        view: ImageView,
        onLoaded: () -> Unit = {},
        onMissing: () -> Unit = {}
    ) {
        val key = file.cameraPath
        view.tag = key

        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            onLoaded()
            return
        }

        view.setImageBitmap(null)
        if (synchronized(failed) { key in failed }) {
            onMissing()
            return
        }

        executor.execute {
            val bytes = api.getThumbnail(file)
            val bitmap = bytes?.let {
                try {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
            } else {
                synchronized(failed) { failed.add(key) }
            }
            main.post {
                // The row may have been recycled onto a different file.
                if (view.tag != key) return@post
                if (bitmap != null) {
                    view.setImageBitmap(bitmap)
                    onLoaded()
                } else {
                    onMissing()
                }
            }
        }
    }

    /** Drops cached images and lets previously-failed files be retried. */
    fun clear() {
        cache.evictAll()
        synchronized(failed) { failed.clear() }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
