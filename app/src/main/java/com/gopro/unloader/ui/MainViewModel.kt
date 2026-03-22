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
import com.gopro.unloader.wifi.WifiConnectResult
import com.gopro.unloader.wifi.WifiConnector
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

    private val _isBusy = MutableLiveData(false)
    val isBusy: LiveData<Boolean> = _isBusy

    private val _phase = MutableLiveData(Phase.IDLE)
    val phase: LiveData<Phase> = _phase

    private val _cameraInfo = MutableLiveData<CameraInfo?>()
    val cameraInfo: LiveData<CameraInfo?> = _cameraInfo

    private val _isRecording = MutableLiveData<Boolean?>(null)
    val isRecording: LiveData<Boolean?> = _isRecording

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    // ----------------------------------------------------------- settings
    var keepOriginals: Boolean = false
    var bleAddress: String? = null

    // --------------------------------------------------------------- helpers
    private val goProApi = GoProApi()
    private val downloadMgr = DownloadManager()
    private val transcodeMgr = TranscodeManager()
    private var bleMgr: GoProBleManager? = null
    private var wifiConnector: WifiConnector? = null

    private val context: Context get() = getApplication()

    /** Stored WiFi credentials from last BLE wake — used for automatic WiFi connection. */
    private var storedWifiCredentials: WifiCredentials? = null

    /** BLE address discovered during last scan — skip re-scanning for subsequent operations. */
    private var lastKnownBleAddress: String? = null

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
        STARTING_RECORDING, STOPPING_RECORDING, DELETING
    }

    // ===================================================================
    // WAKE CAMERA (BLE only — no WiFi)
    // Queries battery/storage, then enables the WiFi AP for later use.
    // ===================================================================

    fun wakeCamera() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _statusLog.value = ""
        _cameraInfo.value = null

        viewModelScope.launch {
            try {
                _phase.value = Phase.BLE_SCAN
                log("Waking camera via Bluetooth…")

                // Step 1: Query camera status (battery, storage) over BLE
                val bleQuery = GoProBleManager(context).also { bleMgr = it }
                val info = bleQuery.queryCameraInfo(
                    knownAddress = bleAddress,
                    onStatus = { log(it) }
                )
                lastKnownBleAddress = bleQuery.lastFoundAddress ?: bleAddress
                bleQuery.close()
                bleMgr = null

                if (info != null) {
                    _cameraInfo.postValue(info)
                    log("Battery: ${info.batteryPercent}%  •  Storage: ${formatMb(info.remainingSpaceMb)} free")
                }

                // Step 2: Enable WiFi AP and store credentials for later
                log("Enabling WiFi AP…")
                val bleWifi = GoProBleManager(context).also { bleMgr = it }
                val creds = bleWifi.findAndEnableWifi(
                    knownAddress = lastKnownBleAddress,
                    onStatus = { log(it) }
                )
                bleWifi.close()
                bleMgr = null

                if (creds != null) {
                    storedWifiCredentials = creds
                    log("Camera is awake. WiFi AP ready for file transfers.")
                } else if (info == null) {
                    log("Could not connect to camera. Make sure it's nearby and powered on.")
                    return@launch
                } else {
                    log("Status retrieved but WiFi AP could not be enabled. File transfers may not work.")
                }

                _isConnected.postValue(true)
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
                if (_phase.value == Phase.BLE_SCAN) _phase.value = Phase.IDLE
            }
        }
    }

    // ===================================================================
    // RECORDING CONTROL (BLE only — no WiFi)
    // ===================================================================

    fun startRecording() {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.STARTING_RECORDING
                log("Starting recording via Bluetooth…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendBleCommand(
                    GoProBleManager.SHUTTER_START_CMD,
                    knownAddress = lastKnownBleAddress ?: bleAddress,
                    onStatus = { log(it) }
                )
                if (ok) {
                    _isRecording.postValue(true)
                    log("Recording started.")
                } else {
                    log("Failed to start recording. Make sure the camera is awake.")
                }
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    fun stopRecording() {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.STOPPING_RECORDING
                log("Stopping recording via Bluetooth…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendBleCommand(
                    GoProBleManager.SHUTTER_STOP_CMD,
                    knownAddress = lastKnownBleAddress ?: bleAddress,
                    onStatus = { log(it) }
                )
                if (ok) {
                    _isRecording.postValue(false)
                    log("Recording stopped.")
                } else {
                    log("Failed to stop recording.")
                }
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    // ===================================================================
    // BROWSE FILES (WiFi — auto-connects using stored BLE credentials)
    // ===================================================================

    fun browseFiles() {
        if (_isBusy.value == true) return
        _isBusy.value = true
        _mediaFiles.value = emptyList()

        viewModelScope.launch {
            try {
                // If no stored credentials, wake camera via BLE first
                if (storedWifiCredentials == null) {
                    _phase.value = Phase.BLE_SCAN
                    log("No WiFi credentials cached. Waking camera via Bluetooth…")
                    val ble = GoProBleManager(context).also { bleMgr = it }
                    val creds = ble.findAndEnableWifi(
                        knownAddress = lastKnownBleAddress ?: bleAddress,
                        onStatus = { log(it) }
                    )
                    lastKnownBleAddress = ble.lastFoundAddress ?: lastKnownBleAddress
                    ble.close()
                    bleMgr = null
                    if (creds == null) {
                        log("Cannot connect to camera. Wake the camera first and try again.")
                        return@launch
                    }
                    storedWifiCredentials = creds
                }

                _phase.value = Phase.WIFI_WAIT
                if (!connectWifi(storedWifiCredentials!!)) return@launch

                _phase.value = Phase.FETCHING_LIST
                val files = fetchAndShowList()
                if (files.isEmpty()) {
                    log("No media files found on the camera.")
                    return@launch
                }
                _phase.value = Phase.LIST_READY
                log("Select the files you want to transfer.")
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
                if (_phase.value == Phase.FETCHING_LIST) _phase.value = Phase.IDLE
            }
        }
    }

    // ===================================================================
    // TRANSFER (WiFi — download + optional transcode + optional delete)
    // ===================================================================

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

    fun deleteSelectedFiles() {
        val filesToDelete = _mediaFiles.value?.filter { it.selected } ?: emptyList()
        if (filesToDelete.isEmpty()) {
            log("No files selected.")
            return
        }
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.DELETING
                log("Deleting ${filesToDelete.size} file(s) from GoPro…")
                var deleted = 0
                var failed = 0
                for (file in filesToDelete) {
                    val ok = withContext(Dispatchers.IO) { goProApi.deleteFile(file) }
                    if (ok) {
                        deleted++
                        log("  Deleted ${file.name}")
                    } else {
                        failed++
                        log("  Could not delete ${file.name}")
                    }
                }
                log("─────────────────────────")
                log("Done! Deleted: $deleted  Errors: $failed")
                _phase.value = Phase.FETCHING_LIST
                val files = fetchAndShowList()
                _phase.value = if (files.isEmpty()) Phase.IDLE else Phase.LIST_READY
                if (files.isNotEmpty()) log("Select files to transfer, or delete more.")
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
        log("Files saved to Movies/GoProUnloader (visible in file browser)")
        _phase.value = Phase.DONE
    }

    private suspend fun connectWifi(creds: WifiCredentials): Boolean {
        log("Connecting to GoPro WiFi (${creds.ssid})…")
        val connector = WifiConnector(context).also { wifiConnector = it }
        val result = connector.connectToGoProWifi(creds.ssid, creds.password)

        when (result) {
            is WifiConnectResult.Connected -> {
                log("WiFi connected. Binding network…")
                // Explicitly bind HTTP clients to the GoPro WiFi network.
                // Without this, OkHttp continues using the phone's default
                // network (mobile data or home WiFi) and never reaches 10.5.5.9.
                goProApi.bindToNetwork(result.network)
                downloadMgr.bindToNetwork(result.network)
            }
            is WifiConnectResult.Failed -> {
                log("Auto-connect failed: ${result.reason}")
                log("Please connect to \"${creds.ssid}\" manually (password: ${creds.password}).")
                connector.disconnect()
                wifiConnector = null
            }
        }

        return waitForCameraConnection()
    }

    fun disconnectWifi() {
        wifiConnector?.disconnect()
        wifiConnector = null
        goProApi.unbindNetwork()
        downloadMgr.unbindNetwork()
    }

    private suspend fun waitForCameraConnection(): Boolean {
        var attempts = 0
        while (attempts < 30) {
            val reachable = withContext(Dispatchers.IO) { goProApi.isCameraReachable() }
            if (reachable) {
                log("GoPro connected over WiFi.")
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

    private fun formatMb(mb: Long): String = when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024.0)
        else -> "$mb MB"
    }
}
