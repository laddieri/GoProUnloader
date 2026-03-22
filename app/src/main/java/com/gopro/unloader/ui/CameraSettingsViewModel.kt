package com.gopro.unloader.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gopro.unloader.ble.GoProBleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CameraSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _statusLog = MutableLiveData<String>()
    val statusLog: LiveData<String> = _statusLog

    private val _isBusy = MutableLiveData(false)
    val isBusy: LiveData<Boolean> = _isBusy

    private val _currentSettings = MutableLiveData<Map<Int, Int>>(emptyMap())
    val currentSettings: LiveData<Map<Int, Int>> = _currentSettings

    /** Populated by the launching Activity from MainViewModel's effective address. */
    var bleAddress: String? = null

    private val context: Context get() = getApplication()
    private var bleMgr: GoProBleManager? = null

    fun loadCurrentSettings(settingIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ble = GoProBleManager(context)
                val map = ble.querySettingValues(settingIds, knownAddress = bleAddress, onStatus = { log(it) })
                ble.close()
                _currentSettings.postValue(map)
            } catch (_: Exception) {
                // Non-fatal: UI just won't show current values
            }
        }
    }

    fun applySetting(settingId: Int, value: Byte, settingName: String) {
        if (_isBusy.value == true) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                log("Applying '$settingName'…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendSettingCommand(
                    settingId = settingId,
                    value = value,
                    knownAddress = bleAddress,
                    onStatus = { log(it) }
                )
                ble.close()
                bleMgr = null
                log(if (ok) "Done." else "Failed — camera may not support this setting.")
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    fun loadPresetGroup(cmd: ByteArray, name: String) {
        if (_isBusy.value == true) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                log("Switching to $name mode…")
                val ble = GoProBleManager(context).also { bleMgr = it }
                val ok = ble.sendBleCommand(
                    cmd,
                    knownAddress = bleAddress,
                    onStatus = { log(it) }
                )
                ble.close()
                bleMgr = null
                log(if (ok) "Switched to $name mode." else "Failed to switch mode.")
            } finally {
                _isBusy.value = false
                bleMgr?.close()
                bleMgr = null
            }
        }
    }

    private fun log(msg: String) {
        val current = _statusLog.value ?: ""
        _statusLog.postValue(if (current.isEmpty()) msg else "$current\n$msg")
    }

    override fun onCleared() {
        bleMgr?.close()
        bleMgr = null
    }
}
