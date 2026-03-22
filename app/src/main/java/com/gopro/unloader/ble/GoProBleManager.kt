package com.gopro.unloader.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.gopro.unloader.model.CameraInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "GoProBleManager"

private fun BluetoothGatt.findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
    services?.flatMap { it.characteristics }?.find { it.uuid == uuid }

data class WifiCredentials(val ssid: String, val password: String)

@SuppressLint("MissingPermission")
class GoProBleManager(private val context: Context) {

    companion object {
        val CMD_REQ_UUID: UUID = UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b")
        val CMD_RSP_UUID: UUID = UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b")
        val WIFI_AP_SSID_UUID: UUID = UUID.fromString("b5f90002-aa8d-11e3-9046-0002a5d5c51b")
        val WIFI_AP_PASSWORD_UUID: UUID = UUID.fromString("b5f90003-aa8d-11e3-9046-0002a5d5c51b")
        val NOTIFY_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val ENABLE_WIFI_CMD = byteArrayOf(0x03, 0x17, 0x01, 0x01)

        val QUERY_REQ_UUID: UUID = UUID.fromString("b5f90076-aa8d-11e3-9046-0002a5d5c51b")
        val QUERY_RSP_UUID: UUID = UUID.fromString("b5f90077-aa8d-11e3-9046-0002a5d5c51b")
        // 0x13 = Get Status Value; 0x46 (70) = battery %, 0x36 (54) = SD remaining KB
        val STATUS_QUERY_CMD = byteArrayOf(0x03, 0x13, 0x46, 0x36.toByte())

        val SHUTTER_START_CMD = byteArrayOf(0x03, 0x01, 0x01, 0x01)
        val SHUTTER_STOP_CMD = byteArrayOf(0x03, 0x01, 0x01, 0x00)
        // 0x05 = sleep/power-down command
        val SLEEP_CMD = byteArrayOf(0x01, 0x05)

        val SETTINGS_REQ_UUID: UUID = UUID.fromString("b5f90074-aa8d-11e3-9046-0002a5d5c51b")
        val SETTINGS_RSP_UUID: UUID = UUID.fromString("b5f90075-aa8d-11e3-9046-0002a5d5c51b")

        // Load Preset Group: [len=4, cmd=0x40, param_len=2, group_high, group_low]
        val PRESET_GROUP_VIDEO      = byteArrayOf(0x04, 0x40, 0x02, 0x03, 0xE8.toByte()) // 1000
        val PRESET_GROUP_PHOTO      = byteArrayOf(0x04, 0x40, 0x02, 0x03, 0xE9.toByte()) // 1001
        val PRESET_GROUP_TIMELAPSE  = byteArrayOf(0x04, 0x40, 0x02, 0x03, 0xEA.toByte()) // 1002

        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val RESPONSE_TIMEOUT_MS = 5_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

    /** Address of the last GoPro found during a BLE scan. Persists after close(). */
    var lastFoundAddress: String? = null
        private set

    /** Scan for a GoPro, connect, read WiFi credentials, and enable the WiFi AP. */
    suspend fun findAndEnableWifi(
        knownAddress: String? = null,
        onStatus: (String) -> Unit = {}
    ): WifiCredentials? = withContext(Dispatchers.IO) {

        val device = if (knownAddress != null) {
            onStatus("Connecting to known address $knownAddress…")
            bluetoothAdapter.getRemoteDevice(knownAddress)
        } else {
            onStatus("Scanning for GoPro via Bluetooth LE…")
            scanForGoPro(onStatus)
        }

        if (device == null) {
            onStatus("No GoPro found via BLE. Make sure the camera is within range.")
            return@withContext null
        }

        onStatus("Found ${device.name ?: "GoPro"} (${device.address}). Connecting…")
        val creds = connectAndReadCredentials(device, onStatus)
        if (creds == null) {
            onStatus("Failed to read WiFi credentials from GoPro.")
        }
        creds
    }

    private suspend fun scanForGoPro(onStatus: (String) -> Unit): BluetoothDevice? =
        withContext(Dispatchers.IO) {
            val scanner = bluetoothAdapter.bluetoothLeScanner ?: return@withContext null
            var foundDevice: BluetoothDevice? = null

            val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val name = result.device.name ?: return
                            if (name.startsWith("GoPro") && cont.isActive) {
                                scanner.stopScan(this)
                                foundDevice = result.device
                                lastFoundAddress = result.device.address
                                cont.resume(result.device)
                            }
                        }

                        override fun onScanFailed(errorCode: Int) {
                            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    cont.invokeOnCancellation { scanner.stopScan(callback) }
                    scanner.startScan(callback)
                }
            }

            result ?: foundDevice
        }

    private suspend fun connectAndReadCredentials(
        device: BluetoothDevice,
        onStatus: (String) -> Unit
    ): WifiCredentials? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var ssid: String? = null
                var password: String? = null
                var wifiCmdSent = false

                val gattCallback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt, status: Int, newState: Int
                    ) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                onStatus("Connected. Discovering services…")
                                // Short delay to let the connection stabilise
                                gatt.discoverServices()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                Log.d(TAG, "Disconnected. status=$status")
                                if (!cont.isCompleted) cont.resume(null)
                            }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "Service discovery failed: status=$status")
                            if (!cont.isCompleted) cont.resume(null)
                            return
                        }
                        onStatus("Services discovered. Reading WiFi credentials…")
                        val ssidChar = gatt.findCharacteristic(WIFI_AP_SSID_UUID)
                        if (ssidChar == null) {
                            Log.e(TAG, "SSID characteristic not found")
                            if (!cont.isCompleted) cont.resume(null)
                            return
                        }
                        gatt.readCharacteristic(ssidChar)
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) return
                        when (characteristic.uuid) {
                            WIFI_AP_SSID_UUID -> {
                                ssid = characteristic.value?.toString(Charsets.UTF_8)
                                Log.d(TAG, "SSID: $ssid")
                                val passChar = gatt.findCharacteristic(WIFI_AP_PASSWORD_UUID)
                                    ?: run {
                                        if (!cont.isCompleted) cont.resume(null); return
                                    }
                                gatt.readCharacteristic(passChar)
                            }
                            WIFI_AP_PASSWORD_UUID -> {
                                password = characteristic.value?.toString(Charsets.UTF_8)
                                Log.d(TAG, "Password read.")
                                onStatus("Credentials read. Enabling WiFi AP…")
                                enableWifiNotificationsAndSend(gatt)
                            }
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int
                    ) {
                        if (descriptor.uuid == NOTIFY_DESCRIPTOR_UUID && !wifiCmdSent) {
                            wifiCmdSent = true
                            val cmdChar = gatt.findCharacteristic(CMD_REQ_UUID) ?: return
                            @Suppress("DEPRECATION")
                            cmdChar.value = ENABLE_WIFI_CMD
                            cmdChar.writeType =
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            gatt.writeCharacteristic(cmdChar)
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (characteristic.uuid == CMD_REQ_UUID) {
                            Log.d(TAG, "WiFi enable command sent. Awaiting response…")
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        if (characteristic.uuid == CMD_RSP_UUID) {
                            val data = characteristic.value ?: return
                            if (data.size >= 3 && data[1] == 0x17.toByte()) {
                                val statusByte = data[2]
                                if (statusByte == 0x00.toByte()) {
                                    onStatus("WiFi AP enabled successfully.")
                                } else {
                                    onStatus("WiFi enable response: 0x${statusByte.toString(16)}")
                                }
                                val s = ssid
                                val p = password
                                gatt.disconnect()
                                if (!cont.isCompleted) {
                                    cont.resume(
                                        if (s != null && p != null) WifiCredentials(s, p) else null
                                    )
                                }
                            }
                        }
                    }
                }

                val g = device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
                this@GoProBleManager.gatt = g
                cont.invokeOnCancellation {
                    g.disconnect()
                    g.close()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableWifiNotificationsAndSend(gatt: BluetoothGatt) {
        val cmdRsp = gatt.findCharacteristic(CMD_RSP_UUID) ?: run {
            Log.e(TAG, "CMD_RSP characteristic not found"); return
        }
        gatt.setCharacteristicNotification(cmdRsp, true)
        val descriptor = cmdRsp.getDescriptor(NOTIFY_DESCRIPTOR_UUID) ?: run {
            // No descriptor — just send the command directly
            val cmdChar = gatt.findCharacteristic(CMD_REQ_UUID) ?: return
            cmdChar.value = ENABLE_WIFI_CMD
            cmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(cmdChar)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    /**
     * Query battery % and SD card remaining space directly over BLE — no WiFi required.
     * Uses the GoPro BLE Query API (GP-0076/GP-0077) with command 0x13 (Get Status Value).
     */
    suspend fun queryCameraInfo(
        knownAddress: String? = null,
        onStatus: (String) -> Unit = {}
    ): CameraInfo? = withContext(Dispatchers.IO) {
        val device = if (knownAddress != null) {
            onStatus("Connecting to known address $knownAddress…")
            bluetoothAdapter.getRemoteDevice(knownAddress)
        } else {
            onStatus("Scanning for GoPro via Bluetooth LE…")
            scanForGoPro(onStatus)
        }

        if (device == null) {
            onStatus("No GoPro found via BLE. Make sure the camera is within range.")
            return@withContext null
        }

        onStatus("Found ${device.name ?: "GoPro"} (${device.address}). Querying camera status…")
        connectAndQueryStatus(device, onStatus)
    }

    private suspend fun connectAndQueryStatus(
        device: BluetoothDevice,
        onStatus: (String) -> Unit
    ): CameraInfo? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val gattCallback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt, status: Int, newState: Int
                    ) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                onStatus("Connected. Discovering services…")
                                gatt.discoverServices()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (!cont.isCompleted) cont.resume(null)
                            }
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "Service discovery failed: status=$status")
                            if (!cont.isCompleted) cont.resume(null)
                            return
                        }
                        val queryRsp = gatt.findCharacteristic(QUERY_RSP_UUID) ?: run {
                            Log.e(TAG, "Query response characteristic not found")
                            if (!cont.isCompleted) cont.resume(null)
                            return
                        }
                        gatt.setCharacteristicNotification(queryRsp, true)
                        val descriptor = queryRsp.getDescriptor(NOTIFY_DESCRIPTOR_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } else {
                            sendStatusQueryCommand(gatt)
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int
                    ) {
                        if (descriptor.uuid == NOTIFY_DESCRIPTOR_UUID) {
                            sendStatusQueryCommand(gatt)
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        if (characteristic.uuid == QUERY_RSP_UUID) {
                            val data = characteristic.value ?: return
                            Log.d(TAG, "Query response: ${data.joinToString { "0x%02x".format(it) }}")
                            val info = parseStatusQueryResponse(data)
                            gatt.disconnect()
                            if (!cont.isCompleted) cont.resume(info)
                        }
                    }
                }

                val g = device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
                this@GoProBleManager.gatt = g
                cont.invokeOnCancellation {
                    g.disconnect()
                    g.close()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendStatusQueryCommand(gatt: BluetoothGatt) {
        val queryReq = gatt.findCharacteristic(QUERY_REQ_UUID) ?: run {
            Log.e(TAG, "Query request characteristic not found"); return
        }
        queryReq.value = STATUS_QUERY_CMD
        queryReq.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(queryReq)
    }

    /**
     * Parses a BLE status query response (command 0x13).
     * Format: [length, 0x13, error_code, (id, len, value_bytes)...]
     */
    private fun parseStatusQueryResponse(data: ByteArray): CameraInfo? {
        if (data.size < 3 || data[1] != 0x13.toByte() || data[2] != 0x00.toByte()) {
            Log.e(TAG, "Unexpected query response: ${data.joinToString { "0x%02x".format(it) }}")
            return null
        }
        var i = 3
        var battery = -1
        var spaceKb = -1L
        while (i + 1 < data.size) {
            val id = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > data.size) break
            when (id) {
                0x46 -> if (len == 1) battery = data[i].toInt() and 0xFF
                0x36 -> if (len in 1..8) {
                    var kb = 0L
                    for (j in 0 until len) kb = (kb shl 8) or (data[i + j].toLong() and 0xFF)
                    spaceKb = kb
                }
            }
            i += len
        }
        return CameraInfo(
            batteryPercent = battery,
            remainingSpaceMb = if (spaceKb >= 0) spaceKb / 1024L else -1L,
            remainingVideoSec = -1L
        )
    }

    /**
     * Sends an arbitrary command to the GoPro via BLE CMD_REQ and waits for the
     * response on CMD_RSP. Returns true if the camera acknowledges with success (0x00).
     * Useful for shutter start/stop and other BLE commands.
     */
    suspend fun sendBleCommand(
        cmd: ByteArray,
        knownAddress: String? = null,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val device = if (knownAddress != null) {
            onStatus("Connecting to $knownAddress…")
            bluetoothAdapter.getRemoteDevice(knownAddress)
        } else {
            onStatus("Scanning for GoPro…")
            scanForGoPro(onStatus)
        }
        if (device == null) {
            onStatus("No GoPro found.")
            return@withContext false
        }
        onStatus("Sending command to ${device.name ?: "GoPro"}…")
        connectAndSendBleCommand(device, cmd, onStatus)
    }

    @Suppress("DEPRECATION")
    private suspend fun connectAndSendBleCommand(
        device: BluetoothDevice,
        cmd: ByteArray,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var cmdSent = false

                val gattCallback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt, status: Int, newState: Int
                    ) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (!cont.isCompleted) cont.resume(false)
                            }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            if (!cont.isCompleted) cont.resume(false)
                            return
                        }
                        val cmdRsp = gatt.findCharacteristic(CMD_RSP_UUID) ?: run {
                            if (!cont.isCompleted) cont.resume(false); return
                        }
                        gatt.setCharacteristicNotification(cmdRsp, true)
                        val descriptor = cmdRsp.getDescriptor(NOTIFY_DESCRIPTOR_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } else {
                            cmdSent = true
                            writeCmdReq(gatt, cmd)
                        }
                    }

                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
                    ) {
                        if (descriptor.uuid == NOTIFY_DESCRIPTOR_UUID && !cmdSent) {
                            cmdSent = true
                            writeCmdReq(gatt, cmd)
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
                    ) {
                        if (characteristic.uuid == CMD_RSP_UUID) {
                            val data = characteristic.value ?: return
                            Log.d(TAG, "Cmd response: ${data.joinToString { "0x%02x".format(it) }}")
                            val success = data.size >= 3 && data[2] == 0x00.toByte()
                            gatt.disconnect()
                            if (!cont.isCompleted) cont.resume(success)
                        }
                    }
                }

                val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                this@GoProBleManager.gatt = g
                cont.invokeOnCancellation {
                    g.disconnect()
                    g.close()
                }
            }
        } ?: false
    }

    @Suppress("DEPRECATION")
    private fun writeCmdReq(gatt: BluetoothGatt, cmd: ByteArray) {
        val cmdChar = gatt.findCharacteristic(CMD_REQ_UUID) ?: return
        cmdChar.value = cmd
        cmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(cmdChar)
    }

    /**
     * Applies a single camera setting via BLE. Uses the Settings Request/Response
     * characteristics (GP-0074/GP-0075).
     * Format sent: [0x03, setting_id, 0x01, value]
     * Returns true if the camera acknowledges with status 0x00.
     */
    suspend fun sendSettingCommand(
        settingId: Int,
        value: Byte,
        knownAddress: String? = null,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val device = if (knownAddress != null) {
            bluetoothAdapter.getRemoteDevice(knownAddress)
        } else {
            onStatus("Scanning for GoPro…")
            scanForGoPro(onStatus)
        }
        if (device == null) {
            onStatus("No GoPro found.")
            return@withContext false
        }
        onStatus("Applying setting to ${device.name ?: "GoPro"}…")
        connectAndSendSettingCommand(device, settingId, value, onStatus)
    }

    @Suppress("DEPRECATION")
    private suspend fun connectAndSendSettingCommand(
        device: BluetoothDevice,
        settingId: Int,
        value: Byte,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val cmd = byteArrayOf(0x03, settingId.toByte(), 0x01, value)
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var cmdSent = false

                val gattCallback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt, status: Int, newState: Int
                    ) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (!cont.isCompleted) cont.resume(false)
                            }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            if (!cont.isCompleted) cont.resume(false); return
                        }
                        val rsp = gatt.findCharacteristic(SETTINGS_RSP_UUID) ?: run {
                            if (!cont.isCompleted) cont.resume(false); return
                        }
                        gatt.setCharacteristicNotification(rsp, true)
                        val descriptor = rsp.getDescriptor(NOTIFY_DESCRIPTOR_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        } else {
                            cmdSent = true
                            writeSettingReq(gatt, cmd)
                        }
                    }

                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
                    ) {
                        if (descriptor.uuid == NOTIFY_DESCRIPTOR_UUID && !cmdSent) {
                            cmdSent = true
                            writeSettingReq(gatt, cmd)
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
                    ) {
                        if (characteristic.uuid == SETTINGS_RSP_UUID) {
                            val data = characteristic.value ?: return
                            Log.d(TAG, "Setting response: ${data.joinToString { "0x%02x".format(it) }}")
                            // Response: [len, setting_id, status] — status 0x00 = success
                            val success = data.size >= 3 && data[2] == 0x00.toByte()
                            if (!success) onStatus("Setting response: non-zero status 0x${data.getOrElse(2){0}.toString(16)}")
                            gatt.disconnect()
                            if (!cont.isCompleted) cont.resume(success)
                        }
                    }
                }

                val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                this@GoProBleManager.gatt = g
                cont.invokeOnCancellation { g.disconnect(); g.close() }
            }
        } ?: false
    }

    @Suppress("DEPRECATION")
    private fun writeSettingReq(gatt: BluetoothGatt, cmd: ByteArray) {
        val req = gatt.findCharacteristic(SETTINGS_REQ_UUID) ?: return
        req.value = cmd
        req.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(req)
    }

    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
