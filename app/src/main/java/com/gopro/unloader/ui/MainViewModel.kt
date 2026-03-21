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
import com.gopro.unloader.model.CameraInfo
import com.gopro.unloader.model.DownloadStatus
import com.gopro.unloader.model.MediaFile
import com.gopro.unloader.model.TranscodeStatus
import com.gopro.unloader.util.MediaStoreHelper
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

    private val _cameraInfo = MutableLiveData<CameraInfo?>()
    val cameraInfo: LiveData<CameraInfo?> = _cameraInfo

    private val _isRecording = MutableLiveData<Boolean?>(null)
    val isRecording: LiveData<Boolean?> = _isRecording

    // ----------------------------------------------------------- settings
    var skipBle: Boolean = false
    var keepOriginals: Boolean = false
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
        IDLE, BLE_SCAN, WIFI_WAIT, FETCHING_LIST, LIST_READY, DOWNLOADING, TRANSCODING, DONE,
        STARTING_RECORDING, STOPPING_RECORDING
    }

    // --------------------------------------------------------------- actions

    /**
     * Phase 1: BLE scan → WiFi connect → fetch and display the file list.
     * Stops at LIST_READY so the user can review and select files before transferring.
     */
    fun scanAndList() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _statusLog.value = ""
        _mediaFiles.value = emptyList()

        viewModelScope.launch {
            try {
                if (!skipBle) {
                    _phase.value = Phase.BLE_SCAN
                    log("Starting BLE scan…")
                    val bleManager = GoProBleManager(context).also { bleMgr = it }
                    val creds = bleManager.findAndEnableWifi(knownAddress = bleAddress) { msg -> log(msg) }
                    if (creds == null) {
                        log("BLE failed. Connect to GoPro WiFi manually, then use 'List Files (WiFi)'.")
                        return@launch
                    }
                    _wifiCredentials.postValue(creds)
                    log("WiFi AP credentials: SSID=${creds.ssid} / Password=${creds.password}")
                    log("Please connect your device to the GoPro WiFi network now.")
                    _phase.value = Phase.WIFI_WAIT
                    if (!waitForCameraConnection()) return@launch
                } else {
                    log("Skipping BLE. Checking WiFi connection…")
                    _phase.value = Phase.WIFI_WAIT
                    if (!waitForCameraConnection()) return@launch
                }

                _phase.value = Phase.FETCHING_LIST
                val files = fetchAndShowList()
                if (files.isEmpty()) {
                    log("No media files found on GoPro.")
                    return@launch
                }
                _phase.value = Phase.LIST_READY
                log("Select the files you want to transfer, then tap Transfer Selected.")
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    /** Fetch and display the file list without BLE (assumes WiFi already connected). */
    fun listFiles() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _statusLog.value = ""
        _mediaFiles.value = emptyList()

        viewModelScope.launch {
            try {
                _phase.value = Phase.WIFI_WAIT
                if (!waitForCameraConnection()) return@launch
                _phase.value = Phase.FETCHING_LIST
                val files = fetchAndShowList()
                if (files.isEmpty()) {
                    log("No media files found on GoPro.")
                    return@launch
                }
                _phase.value = Phase.LIST_READY
                log("Select the files you want to transfer, then tap Transfer Selected.")
            } finally {
                _isBusy.value = false
                _phase.value = if (_phase.value == Phase.FETCHING_LIST) Phase.IDLE else _phase.value
            }
        }
    }

    /**
     * Quick check: connects to the GoPro over WiFi (no BLE, no file transfer),
     * reads battery % and SD card free space, and posts estimates to [cameraInfo].
     */
    fun quickConnect() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _cameraInfo.value = null

        viewModelScope.launch {
            try {
                _phase.value = Phase.WIFI_WAIT
                log("Connecting to GoPro…")
                val reachable = withContext(Dispatchers.IO) { goProApi.isCameraReachable() }
                if (!reachable) {
                    log("Cannot reach GoPro. Make sure your phone is connected to the GoPro WiFi network.")
                    return@launch
                }
                log("Connected. Reading camera stats…")
                val info = withContext(Dispatchers.IO) { goProApi.getCameraInfo() }
                if (info == null) {
                    log("Could not read camera info.")
                    return@launch
                }
                _cameraInfo.postValue(info)
                log("Camera stats retrieved.")
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
                if (_phase.value == Phase.WIFI_WAIT) _phase.value = Phase.IDLE
            }
        }
    }

    /**
     * Starts recording on the GoPro.
     * If the camera is reachable via WiFi, sends the command directly.
     * If not, wakes the camera via Bluetooth LE first, then waits for WiFi.
     */
    fun startRecording() {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.STARTING_RECORDING
                log("Checking camera connection…")

                val reachable = withContext(Dispatchers.IO) { goProApi.isCameraReachable() }
                if (!reachable) {
                    // Camera not on WiFi — wake it via BLE
                    _phase.value = Phase.BLE_SCAN
                    log("Camera not reachable. Waking via Bluetooth LE…")
                    val bleManager = GoProBleManager(context).also { bleMgr = it }
                    val creds = bleManager.findAndEnableWifi(knownAddress = bleAddress) { msg -> log(msg) }
                    if (creds == null) {
                        log("BLE wake failed. Turn the camera on manually, then try again.")
                        return@launch
                    }
                    _wifiCredentials.postValue(creds)
                    log("Camera WiFi AP enabled. Connect your phone to \"${creds.ssid}\" then the recording will start.")

                    _phase.value = Phase.WIFI_WAIT
                    if (!waitForCameraConnection()) {
                        log("Cannot reach camera over WiFi. Connect to the GoPro network and try again.")
                        return@launch
                    }
                }

                _phase.value = Phase.STARTING_RECORDING
                log("Sending start recording command…")
                val ok = withContext(Dispatchers.IO) { goProApi.startRecording() }
                if (ok) {
                    _isRecording.postValue(true)
                    log("Recording started.")
                } else {
                    log("Failed to start recording. Check the camera mode and try again.")
                }
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    /**
     * Stops recording on the GoPro.
     * The camera must already be reachable over WiFi.
     */
    fun stopRecording() {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.STOPPING_RECORDING
                log("Sending stop recording command…")
                val ok = withContext(Dispatchers.IO) { goProApi.stopRecording() }
                if (ok) {
                    _isRecording.postValue(false)
                    log("Recording stopped.")
                } else {
                    log("Failed to stop recording. Is the camera reachable on the GoPro WiFi network?")
                }
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * Phase 2: Download + optionally transcode + optionally delete from camera.
     * Only transfers files that have [MediaFile.selected] == true.
     */
    fun startTransfer(transcode: Boolean, deleteFromCamera: Boolean) {
        val filesToTransfer = _mediaFiles.value?.filter { it.selected } ?: emptyList()
        if (filesToTransfer.isEmpty()) {
            log("No files selected.")
            return
        }
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                runTransfer(filesToTransfer, transcode, deleteFromCamera)
            } finally {
                _isBusy.value = false
            }
        }
    }

    // ----------------------------------------------------------- private

    private suspend fun runTransfer(
        files: List<MediaFile>,
        transcode: Boolean,
        deleteFromCamera: Boolean
    ) {
        _phase.value = Phase.DOWNLOADING
        log("Starting download of ${files.size} file(s)…")

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
                    log("Downloaded: ${file.name}")
                } else {
                    file.downloadStatus = DownloadStatus.ERROR
                    log("Error: ${file.name}")
                }
                notifyListChanged()
            }
        } finally {
            keepAliveJob.cancel()
        }

        if (deleteFromCamera) {
            log("Deleting ${downloaded.size} file(s) from camera…")
            for ((file, _) in downloaded) {
                val ok = withContext(Dispatchers.IO) { goProApi.deleteFile(file) }
                log(if (ok) "  Deleted ${file.name} from GoPro." else "  Could not delete ${file.name}.")
            }
        }

        if (transcode) {
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
                        MediaStoreHelper.addVideoToGallery(context, out)
                    } else {
                        file.transcodeStatus = TranscodeStatus.SKIPPED
                    }
                    notifyListChanged()
                }
            }
        } else {
            for ((_, dest) in downloaded) {
                if (dest.name.uppercase().endsWith(".MP4")) {
                    MediaStoreHelper.addVideoToGallery(context, dest)
                }
            }
        }

        val dlCount = downloaded.size
        val errCount = files.count { it.downloadStatus == DownloadStatus.ERROR }
        log("─────────────────────────")
        log("Done! Transferred: $dlCount  Errors: $errCount")
        log("Files saved to Movies/GoProUnloader")
        _phase.value = Phase.DONE
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
