package com.gopro.unloader.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private val _wifiCredentials = MutableLiveData<WifiCredentials?>()
    val wifiCredentials: LiveData<WifiCredentials?> = _wifiCredentials

    /** Signal for the Activity to open WiFi settings. Incremented each time. */
    private val _openWifiSettings = MutableLiveData(0)
    val openWifiSettings: LiveData<Int> = _openWifiSettings

    /** Signal for the Activity to show post-transfer cleanup dialog. */
    data class PostTransferPrompt(
        val hasGoproFiles: Boolean,
        val hasLocalOriginals: Boolean,
        val id: Int = 0
    )
    data class PostTransferChoice(
        val deleteFromGoPro: Boolean,
        val deleteLocalOriginals: Boolean
    )
    private val _postTransferPrompt = MutableLiveData<PostTransferPrompt?>()
    val postTransferPrompt: LiveData<PostTransferPrompt?> = _postTransferPrompt
    private var postTransferDeferred: CompletableDeferred<PostTransferChoice>? = null

    fun submitPostTransferChoice(choice: PostTransferChoice) {
        postTransferDeferred?.complete(choice)
    }

    // ----------------------------------------------------------- settings
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
        STARTING_RECORDING, STOPPING_RECORDING, DELETING, SLEEPING, APPLYING_SETTING, LOADING_PRESET
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
    // SLEEP CAMERA (BLE only)
    // ===================================================================

    fun sleepCamera() {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.SLEEPING
                log("Putting camera to sleep via Bluetooth…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendBleCommand(
                    GoProBleManager.SLEEP_CMD,
                    knownAddress = lastKnownBleAddress ?: bleAddress,
                    onStatus = { log(it) }
                )
                ble.close()
                bleMgr = null
                if (ok) {
                    log("Camera is sleeping.")
                    _isConnected.postValue(false)
                    storedWifiCredentials = null
                } else {
                    log("Sleep command failed. Camera may already be off or out of range.")
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
    // CAMERA SETTINGS (BLE only)
    // ===================================================================

    fun applySetting(settingId: Int, value: Byte, settingName: String) {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.APPLYING_SETTING
                log("Applying setting "$settingName" via Bluetooth…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendSettingCommand(
                    settingId = settingId,
                    value = value,
                    knownAddress = lastKnownBleAddress ?: bleAddress,
                    onStatus = { log(it) }
                )
                ble.close()
                bleMgr = null
                log(if (ok) "Setting applied." else "Failed to apply setting.")
                _phase.value = Phase.IDLE
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    // ===================================================================
    // RECORDING MODE (preset group switch via BLE command)
    // ===================================================================

    fun loadPresetGroup(cmd: ByteArray, name: String) {
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                _phase.value = Phase.LOADING_PRESET
                log("Switching to $name mode…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendBleCommand(
                    cmd,
                    knownAddress = lastKnownBleAddress ?: bleAddress,
                    onStatus = { log(it) }
                )
                ble.close()
                bleMgr = null
                log(if (ok) "Switched to $name mode." else "Failed to switch to $name mode.")
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

    fun startTransfer(transcode: Boolean) {
        val filesToTransfer = _mediaFiles.value?.filter { it.selected } ?: emptyList()
        if (filesToTransfer.isEmpty()) {
            log("No files selected.")
            return
        }
        if (_isBusy.value == true) return
        _isBusy.value = true

        viewModelScope.launch {
            try {
                runTransfer(filesToTransfer, transcode)
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
        transcode: Boolean
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

        val transcodedFiles = mutableListOf<Pair<MediaFile, File>>()

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
                        transcodedFiles.add(file to out)
                        // Original full-size → Movies/GoProUnloader/
                        MediaStoreHelper.addVideoToGallery(context, src)
                        // Transcoded copy → Movies/GoProUnloader/transcoded/
                        MediaStoreHelper.addVideoToGallery(context, out, "GoProUnloader/transcoded")
                    } else {
                        file.transcodeStatus = TranscodeStatus.SKIPPED
                        // Transcode was skipped (already ≤1080p) — publish the original
                        MediaStoreHelper.addVideoToGallery(context, src)
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
        log("Transferred: $dlCount  Errors: $errCount")

        // Ask user about cleanup after transfer/transcode
        val hasGoproFiles = downloaded.isNotEmpty()
        val hasLocalOriginals = transcode && transcodedFiles.isNotEmpty()

        if (hasGoproFiles || hasLocalOriginals) {
            val deferred = CompletableDeferred<PostTransferChoice>()
            postTransferDeferred = deferred
            _postTransferPrompt.postValue(PostTransferPrompt(
                hasGoproFiles = hasGoproFiles,
                hasLocalOriginals = hasLocalOriginals,
                id = System.currentTimeMillis().toInt()
            ))
            val choice = deferred.await()
            _postTransferPrompt.postValue(null)
            postTransferDeferred = null

            if (choice.deleteFromGoPro && hasGoproFiles) {
                log("Deleting ${downloaded.size} file(s) from camera…")
                for ((file, _) in downloaded) {
                    val ok = withContext(Dispatchers.IO) { goProApi.deleteFile(file) }
                    log(if (ok) "  Deleted ${file.name} from GoPro." else "  Could not delete ${file.name}.")
                }
            }

            if (choice.deleteLocalOriginals && hasLocalOriginals) {
                log("Removing full-size originals from phone…")
                for ((file, _) in transcodedFiles) {
                    val localFile = file.localPath?.let { File(it) }
                    if (localFile != null && localFile.exists()) {
                        localFile.delete()
                        log("  Removed ${localFile.name}")
                    }
                }
            }
        }

        log("Done! Files saved to Movies/GoProUnloader")
        _phase.value = Phase.DONE
    }

    private suspend fun connectWifi(creds: WifiCredentials): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Legacy API 26-28: programmatic WiFi switching actually works
            log("Connecting to GoPro WiFi (${creds.ssid})…")
            val connector = WifiConnector(context).also { wifiConnector = it }
            val result = connector.connectToGoProWifi(creds.ssid, creds.password)
            when (result) {
                is WifiConnectResult.Connected -> {
                    log("WiFi connected.")
                    goProApi.bindToNetwork(result.network)
                    downloadMgr.bindToNetwork(result.network)
                }
                is WifiConnectResult.Failed -> {
                    log("Auto-connect failed: ${result.reason}")
                    connector.disconnect()
                    wifiConnector = null
                }
            }
            return waitForCameraConnection()
        }

        // Android 10+: Can't programmatically switch WiFi. Show credentials
        // and open WiFi settings so the user can connect manually.
        _wifiCredentials.postValue(creds)
        _openWifiSettings.postValue((_openWifiSettings.value ?: 0) + 1)
        log("Switch your WiFi to the GoPro network:")
        log("  Network: ${creds.ssid}")
        log("  Password: ${creds.password}")
        log("Waiting for connection…")

        // Re-bind to WiFi before each ping so Android doesn't route through
        // mobile data (GoPro WiFi has no internet — Android won't use it by
        // default, especially on subsequent connections to the same SSID).
        val connected = waitForCameraConnection { bindToActiveWifiNetwork() }
        if (connected) {
            _wifiCredentials.postValue(null) // hide the credentials card
        }
        return connected
    }

    /**
     * Find the currently active WiFi network and bind HTTP clients to it.
     * On Android 10+, the system may prefer mobile data over a WiFi network
     * that has no internet (like GoPro). Explicit binding ensures our traffic
     * goes through the WiFi interface.
     */
    private fun bindToActiveWifiNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Walk all networks to find WiFi
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                goProApi.bindToNetwork(network)
                downloadMgr.bindToNetwork(network)
                log("Bound to WiFi network.")
                return
            }
        }
        // Fallback: use active network (might be WiFi if user just connected)
        cm.activeNetwork?.let { network ->
            goProApi.bindToNetwork(network)
            downloadMgr.bindToNetwork(network)
        }
    }

    fun disconnectWifi() {
        wifiConnector?.disconnect()
        wifiConnector = null
        goProApi.unbindNetwork()
        downloadMgr.unbindNetwork()
        _wifiCredentials.postValue(null)
    }

    private suspend fun waitForCameraConnection(
        beforeEachAttempt: () -> Unit = {}
    ): Boolean {
        var attempts = 0
        while (attempts < 30) {
            beforeEachAttempt()
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
        val files = withContext(Dispatchers.IO) {
            val list = goProApi.getMediaList()
            list.filter { it.name.uppercase().endsWith(".MP4") }
                .map { file -> async { file.duration = goProApi.getMediaInfo(file.directory, file.name) } }
                .awaitAll()
            list
        }
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
