package com.gopro.unloader.ui

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gopro.unloader.api.DownloadManager
import com.gopro.unloader.api.GoProApi
import com.gopro.unloader.api.TranscodeManager
import com.gopro.unloader.ble.GoProBleManager
import com.gopro.unloader.ble.WifiCredentials
import com.gopro.unloader.model.DownloadStatus
import com.gopro.unloader.model.MediaFile
import com.gopro.unloader.model.TranscodeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ------------------------------------------------------------------ state

    private val _statusLog = MutableLiveData<String>()
    val statusLog: LiveData<String> = _statusLog

    private val _mediaFiles = MutableLiveData<List<MediaFile>>()
    val mediaFiles: LiveData<List<MediaFile>> = _mediaFiles

    private val _wifiCredentials = MutableLiveData<WifiCredentials?>()
    val wifiCredentials: LiveData<WifiCredentials?> = _wifiCredentials

    private val _isBusy = MutableLiveData(false)
    val isBusy: LiveData<Boolean> = _isBusy

    private val _phase = MutableLiveData(Phase.IDLE)
    val phase: LiveData<Phase> = _phase

    // ----------------------------------------------------------- settings
    var skipBle: Boolean = false
    var keepOriginals: Boolean = false
    var noDelete: Boolean = false
    var noTranscode: Boolean = false
    var forceRedownload: Boolean = false
    var bleAddress: String? = null

    // --------------------------------------------------------------- helpers
    private val goProApi = GoProApi()
    private val downloadMgr = DownloadManager()
    private val transcodeMgr = TranscodeManager()
    private var bleMgr: GoProBleManager? = null

    private val context: Context get() = getApplication()

    private val outputDir: File
        get() {
            val ext = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            return File(ext, "GoProUnloader")
        }

    private val transcodeDir: File get() = File(outputDir, "transcoded")

    // -------------------------------------------------------------- enums
    enum class Phase {
        IDLE, BLE_SCAN, WIFI_WAIT, FETCHING_LIST, DOWNLOADING, TRANSCODING, DONE
    }

    // --------------------------------------------------------------- actions

    /** Full offload workflow: BLE → WiFi check → list → download → delete → transcode */
    fun startOffload() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _statusLog.value = ""
        _mediaFiles.value = emptyList()

        viewModelScope.launch {
            try {
                runOffload()
            } finally {
                _isBusy.value = false
                _phase.value = Phase.IDLE
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    /** Fetch and display the file list without downloading. */
    fun listFiles() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _statusLog.value = ""
        _mediaFiles.value = emptyList()

        viewModelScope.launch {
            try {
                if (!ensureWifiConnected()) return@launch
                fetchAndShowList()
            } finally {
                _isBusy.value = false
                _phase.value = Phase.IDLE
            }
        }
    }

    // ----------------------------------------------------------- private

    private suspend fun runOffload() {
        if (!skipBle) {
            _phase.value = Phase.BLE_SCAN
            log("Starting BLE scan…")
            val bleManager = GoProBleManager(context).also { bleMgr = it }
            val creds = bleManager.findAndEnableWifi(knownAddress = bleAddress) { msg -> log(msg) }
            if (creds == null) {
                log("BLE failed. Connect to GoPro WiFi manually, then retry with 'Skip BLE'.")
                return
            }
            _wifiCredentials.postValue(creds)
            log("WiFi AP credentials: SSID=${creds.ssid} / Password=${creds.password}")
            log("Please connect your device to the GoPro WiFi network now.")
            _phase.value = Phase.WIFI_WAIT
            waitForCameraConnection()
        } else {
            log("Skipping BLE (--skip-ble). Checking WiFi connection…")
            if (!ensureWifiConnected()) return
        }

        _phase.value = Phase.FETCHING_LIST
        val files = fetchAndShowList()
        if (files.isEmpty()) {
            log("No media files found on GoPro.")
            return
        }

        _phase.value = Phase.DOWNLOADING
        log("Starting download of ${files.size} file(s)…")

        // Keepalive coroutine
        val keepAliveJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                goProApi.keepAlive()
                delay(2_500)
            }
        }

        val downloaded = mutableListOf<Pair<MediaFile, File>>()

        try {
            for (file in files) {
                file.downloadStatus = DownloadStatus.DOWNLOADING
                notifyListChanged()

                val dest = withContext(Dispatchers.IO) {
                    downloadMgr.download(
                        file = file,
                        destDir = outputDir,
                        forceRedownload = forceRedownload,
                        onProgress = { pct ->
                            file.downloadProgress = pct
                            notifyListChanged()
                        }
                    )
                }

                if (dest != null) {
                    file.downloadStatus = DownloadStatus.DOWNLOADED
                    file.localPath = dest.absolutePath
                    downloaded.add(file to dest)
                    log("Downloaded: ${file.name} → ${dest.absolutePath}")
                } else {
                    val wasSkipped = dest == null &&
                        File(outputDir, "raw/${file.name}").exists()
                    file.downloadStatus =
                        if (wasSkipped) DownloadStatus.SKIPPED else DownloadStatus.ERROR
                    log("${if (wasSkipped) "Skipped" else "Error"}: ${file.name}")
                }
                notifyListChanged()
            }
        } finally {
            keepAliveJob.cancel()
        }

        // Delete from camera
        if (!noDelete) {
            log("Deleting ${downloaded.size} file(s) from camera…")
            for ((file, _) in downloaded) {
                val ok = withContext(Dispatchers.IO) { goProApi.deleteFile(file) }
                log(if (ok) "  Deleted ${file.name} from GoPro." else "  Could not delete ${file.name}.")
            }
        } else {
            log("Skipping camera deletion (--no-delete).")
        }

        // Transcode
        if (!noTranscode) {
            val mp4s = downloaded.filter { (f, _) -> f.name.uppercase().endsWith(".MP4") }
            if (mp4s.isNotEmpty()) {
                _phase.value = Phase.TRANSCODING
                log("Transcoding ${mp4s.size} video(s) to 1080p…")
                for ((file, src) in mp4s) {
                    file.transcodeStatus = TranscodeStatus.TRANSCODING
                    notifyListChanged()

                    val out = withContext(Dispatchers.IO) {
                        transcodeMgr.transcodeTo1080p(
                            src = src,
                            transcodeDir = transcodeDir,
                            onProgress = { pct ->
                                file.transcodeProgress = pct
                                notifyListChanged()
                            },
                            onStatus = { msg -> log(msg) }
                        )
                    }

                    if (out != null) {
                        file.transcodeStatus = TranscodeStatus.DONE
                        if (!keepOriginals) src.delete()
                    } else {
                        file.transcodeStatus = TranscodeStatus.SKIPPED
                    }
                    notifyListChanged()
                }
            }
        } else {
            log("Skipping transcode (--no-transcode).")
        }

        val dlCount = downloaded.size
        val errCount = files.count { it.downloadStatus == DownloadStatus.ERROR }
        val skipCount = files.count { it.downloadStatus == DownloadStatus.SKIPPED }
        log("─────────────────────────")
        log("Done! Downloaded: $dlCount  Skipped: $skipCount  Errors: $errCount")
        log("Output: ${outputDir.absolutePath}")
        _phase.value = Phase.DONE
    }

    private suspend fun ensureWifiConnected(): Boolean {
        _phase.value = Phase.WIFI_WAIT
        log("Checking GoPro WiFi connection…")
        return waitForCameraConnection()
    }

    private suspend fun waitForCameraConnection(): Boolean {
        var attempts = 0
        while (attempts < 30) {
            val reachable = withContext(Dispatchers.IO) { goProApi.isCameraReachable() }
            if (reachable) {
                val battery = withContext(Dispatchers.IO) { goProApi.getBatteryLevel() }
                log("GoPro connected. Battery: ${battery ?: "?"}%")
                return true
            }
            attempts++
            if (attempts % 5 == 0) log("Waiting for GoPro WiFi… ($attempts/30)")
            delay(2_000)
        }
        log("Cannot reach GoPro at 10.5.5.9. Make sure you are connected to the GoPro WiFi.")
        return false
    }

    private suspend fun fetchAndShowList(): List<MediaFile> {
        log("Fetching media list…")
        val files = withContext(Dispatchers.IO) { goProApi.getMediaList() }
        _mediaFiles.postValue(files)
        log("Found ${files.size} file(s) on GoPro.")
        return files
    }

    private fun log(msg: String) {
        val current = _statusLog.value ?: ""
        _statusLog.postValue(if (current.isEmpty()) msg else "$current\n$msg")
    }

    private fun notifyListChanged() {
        _mediaFiles.postValue(_mediaFiles.value)
    }
}
