package com.gopro.unloader.util

import android.content.Context

/**
 * What to do with each file during a transfer.
 *
 * Snapshotted from [AppSettings] when a transfer starts, so toggling an option
 * mid-run can't change the rules half way through.
 */
data class TransferOptions(
    val transcode: Boolean,
    val keepOriginals: Boolean,
    val deleteFromCamera: Boolean,
    val skipExisting: Boolean
)

/**
 * Offload preferences, remembered between launches.
 *
 * Mirrors the desktop app's settings file so both front ends behave the same
 * way. Backed by SharedPreferences, which handles its own persistence.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Transcode downloaded videos to 1080p. */
    var transcode: Boolean
        get() = prefs.getBoolean(KEY_TRANSCODE, true)
        set(value) = prefs.edit().putBoolean(KEY_TRANSCODE, value).apply()

    /** Keep the full-size original alongside the 1080p copy. */
    var keepOriginals: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ORIGINALS, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ORIGINALS, value).apply()

    /** Delete each file from the camera once it has copied across. */
    var deleteFromCamera: Boolean
        get() = prefs.getBoolean(KEY_DELETE_FROM_CAMERA, true)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_FROM_CAMERA, value).apply()

    /** Leave files that are already on the phone alone instead of refetching. */
    var skipExisting: Boolean
        get() = prefs.getBoolean(KEY_SKIP_EXISTING, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_EXISTING, value).apply()

    /** Manually entered camera BLE address, or null to scan for one. */
    var bleAddress: String?
        get() = prefs.getString(KEY_BLE_ADDRESS, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_BLE_ADDRESS, value).apply()

    fun toTransferOptions() = TransferOptions(
        transcode = transcode,
        keepOriginals = keepOriginals,
        deleteFromCamera = deleteFromCamera,
        skipExisting = skipExisting
    )

    companion object {
        private const val PREFS_NAME = "gopro_unloader_settings"
        private const val KEY_TRANSCODE = "transcode"
        private const val KEY_KEEP_ORIGINALS = "keep_originals"
        private const val KEY_DELETE_FROM_CAMERA = "delete_from_camera"
        private const val KEY_SKIP_EXISTING = "skip_existing"
        private const val KEY_BLE_ADDRESS = "ble_address"
    }
}
