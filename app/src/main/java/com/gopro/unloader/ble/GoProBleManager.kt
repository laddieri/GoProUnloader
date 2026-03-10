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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "GoProBleManager"

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

        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val RESPONSE_TIMEOUT_MS = 5_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

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
                        val ssidChar = gatt.getCharacteristic(WIFI_AP_SSID_UUID)
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
                                val passChar = gatt.getCharacteristic(WIFI_AP_PASSWORD_UUID)
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
                            val cmdChar = gatt.getCharacteristic(CMD_REQ_UUID) ?: return
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
        val cmdRsp = gatt.getCharacteristic(CMD_RSP_UUID) ?: run {
            Log.e(TAG, "CMD_RSP characteristic not found"); return
        }
        gatt.setCharacteristicNotification(cmdRsp, true)
        val descriptor = cmdRsp.getDescriptor(NOTIFY_DESCRIPTOR_UUID) ?: run {
            // No descriptor — just send the command directly
            val cmdChar = gatt.getCharacteristic(CMD_REQ_UUID) ?: return
            cmdChar.value = ENABLE_WIFI_CMD
            cmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(cmdChar)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
