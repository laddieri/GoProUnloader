package com.gopro.unloader

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.gopro.unloader.databinding.ActivityMainBinding
import com.gopro.unloader.service.GoProForegroundService
import com.gopro.unloader.ui.MainViewModel
import com.gopro.unloader.ui.MediaListAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: MediaListAdapter

    // ----------------------------------------- Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required.", Toast.LENGTH_LONG).show()
        }
        pendingAction = null
    }
    private var pendingAction: (() -> Unit)? = null

    // ----------------------------------------- Bluetooth enable launcher
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) pendingAction?.invoke()
        pendingAction = null
    }

    // =========================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = MediaListAdapter(viewModel.thumbnailLoader) { directory, name, selected ->
            viewModel.setSelected(directory, name, selected)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        observeViewModel()
        setupButtons()
    }

    // =========================================================== menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // =========================================================== observe

    private fun observeViewModel() {
        viewModel.statusLog.observe(this) { log ->
            binding.tvStatusLog.text = log
            binding.scrollViewLog.post {
                binding.scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }
        }

        viewModel.rows.observe(this) { rows ->
            adapter.submitList(rows)
            binding.tvFileCount.text =
                if (rows.isEmpty()) "" else "${rows.size} file(s)"
            updateSelectionCount()
        }

        viewModel.isRecording.observe(this) { recording ->
            binding.tvRecordingStatus.text = when (recording) {
                true -> getString(R.string.recording_status_active)
                false -> getString(R.string.recording_status_stopped)
                null -> getString(R.string.recording_status_unknown)
            }
            binding.btnStartRecording.visibility = if (recording == true) View.GONE else View.VISIBLE
            binding.btnStopRecording.visibility = if (recording == true) View.VISIBLE else View.GONE
        }

        viewModel.isConnected.observe(this) { connected ->
            binding.layoutConnected.visibility = if (connected) View.VISIBLE else View.GONE
            binding.btnSleepCamera.visibility = if (connected) View.VISIBLE else View.GONE
        }

        viewModel.wifiCredentials.observe(this) { creds ->
            if (creds != null) {
                binding.cardWifi.visibility = View.VISIBLE
                binding.tvWifiSsid.text = creds.ssid
                binding.tvWifiPassword.text = creds.password
            } else {
                binding.cardWifi.visibility = View.GONE
            }
        }

        var lastWifiSettingsEvent = 0
        viewModel.openWifiSettings.observe(this) { event ->
            if (event > lastWifiSettingsEvent) {
                lastWifiSettingsEvent = event
                openWifiSettings()
            }
        }

        viewModel.isBusy.observe(this) { busy ->
            binding.btnWakeCamera.isEnabled = !busy
            binding.btnSleepCamera.isEnabled = !busy
            binding.btnBrowseFiles.isEnabled = !busy
            binding.btnStartRecording.isEnabled = !busy
            binding.btnStopRecording.isEnabled = !busy
            binding.btnCameraSettings.isEnabled = !busy
            binding.btnDeleteFromGopro.isEnabled = !busy
            binding.progressGlobal.visibility = if (busy) View.VISIBLE else View.GONE

            val intent = Intent(this, GoProForegroundService::class.java)
            if (busy) {
                intent.putExtra("message", "Offloading GoPro footage…")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                stopService(intent)
            }
        }

        viewModel.cameraInfo.observe(this) { info ->
            if (info == null) return@observe

            // Battery
            if (info.batteryPercent >= 0) {
                binding.tvBatteryLevel.text = "${info.batteryPercent}%"
                val battMin = info.estimatedBatteryVideoSec / 60
                binding.tvBatteryEstimate.text =
                    "\u2248 $battMin min of recording remaining"
            } else {
                binding.tvBatteryLevel.text = "Unknown"
                binding.tvBatteryEstimate.text = ""
            }

            // Storage
            if (info.remainingSpaceMb >= 0) {
                binding.tvStorageFree.text = formatMb(info.remainingSpaceMb)
                val storMin = info.storageVideoSec / 60
                val source = if (info.remainingVideoSec >= 0) "camera-reported" else "estimated"
                binding.tvStorageEstimate.text = "\u2248 $storMin min of video ($source)"
            } else {
                binding.tvStorageFree.text = "Unknown"
                binding.tvStorageEstimate.text = ""
            }
        }

        viewModel.phase.observe(this) { phase ->
            binding.tvPhase.text = when (phase) {
                MainViewModel.Phase.BLE_SCAN -> "Connecting via Bluetooth\u2026"
                MainViewModel.Phase.WIFI_WAIT -> "Connecting to GoPro WiFi\u2026"
                MainViewModel.Phase.FETCHING_LIST -> "Fetching media list\u2026"
                MainViewModel.Phase.LIST_READY -> "Ready \u2014 select files to transfer"
                MainViewModel.Phase.DOWNLOADING -> "Downloading\u2026"
                MainViewModel.Phase.TRANSCODING -> "Transcoding to 1080p\u2026"
                MainViewModel.Phase.DONE -> "Complete!"
                MainViewModel.Phase.STARTING_RECORDING -> "Starting recording\u2026"
                MainViewModel.Phase.STOPPING_RECORDING -> "Stopping recording\u2026"
                MainViewModel.Phase.DELETING -> "Deleting from GoPro\u2026"
                MainViewModel.Phase.SLEEPING -> "Putting camera to sleep\u2026"
                MainViewModel.Phase.APPLYING_SETTING -> "Applying setting\u2026"
                MainViewModel.Phase.LOADING_PRESET -> "Switching recording mode\u2026"
                MainViewModel.Phase.IDLE -> ""
            }

            val showControls = phase == MainViewModel.Phase.LIST_READY
            binding.layoutTransferControls.visibility = if (showControls) View.VISIBLE else View.GONE

            val busy = phase == MainViewModel.Phase.DOWNLOADING ||
                phase == MainViewModel.Phase.TRANSCODING ||
                phase == MainViewModel.Phase.DELETING
            binding.btnTransferSelected.isEnabled = !busy
            binding.btnDeleteFromGopro.isEnabled = !busy
        }
    }

    // =========================================================== buttons

    private fun setupButtons() {
        binding.btnWakeCamera.setOnClickListener {
            withPermissionsAndBluetooth { viewModel.wakeCamera() }
        }

        binding.btnSleepCamera.setOnClickListener {
            withPermissionsAndBluetooth { viewModel.sleepCamera() }
        }

        binding.btnCameraSettings.setOnClickListener {
            val intent = android.content.Intent(this, com.gopro.unloader.ui.CameraSettingsActivity::class.java).apply {
                putExtra(com.gopro.unloader.ui.CameraSettingsActivity.EXTRA_BLE_ADDRESS, viewModel.effectiveBleAddress)
            }
            startActivity(intent)
        }

        binding.btnStartRecording.setOnClickListener {
            withPermissionsAndBluetooth { viewModel.startRecording() }
        }

        binding.btnStopRecording.setOnClickListener {
            withPermissionsAndBluetooth { viewModel.stopRecording() }
        }

        binding.btnBrowseFiles.setOnClickListener {
            withPermissionsAndBluetooth { viewModel.browseFiles() }
        }

        binding.btnOpenWifiSettings.setOnClickListener {
            openWifiSettings()
        }

        binding.btnSelectAll.setOnClickListener {
            viewModel.selectAll(true)
        }

        binding.btnDeselectAll.setOnClickListener {
            viewModel.selectAll(false)
        }

        binding.btnTransferSelected.setOnClickListener {
            val selected = viewModel.selectedCount
            if (selected == 0) {
                Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showTransferConfirmDialog(selected)
        }

        binding.btnDeleteFromGopro.setOnClickListener {
            val selected = viewModel.selectedCount
            if (selected == 0) {
                Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showDeleteConfirmDialog(selected)
        }
    }

    private fun updateSelectionCount() {
        val total = viewModel.fileCount
        val selected = viewModel.selectedCount
        binding.tvSelectedCount.text = "$selected of $total selected"
        binding.btnTransferSelected.isEnabled =
            selected > 0 && viewModel.phase.value == MainViewModel.Phase.LIST_READY
    }

    // =========================================================== transfer confirm dialog

    private fun showTransferConfirmDialog(selectedCount: Int) {
        val options = viewModel.settings.toTransferOptions()

        val summary = buildString {
            appendLine(if (options.transcode) {
                if (options.keepOriginals) {
                    "• Transcode to 1080p, keeping the full-size original"
                } else {
                    "• Transcode to 1080p, replacing the full-size original"
                }
            } else {
                "• Copy across as-is, no transcoding"
            })
            appendLine(
                if (options.deleteFromCamera) "• DELETE from the camera once copied"
                else "• Leave the files on the camera"
            )
            append(
                if (options.skipExisting) "• Skip anything already downloaded"
                else "• Re-download everything"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Transfer $selectedCount file(s)")
            .setMessage("$summary\n\nChange these under Options.")
            .setPositiveButton("Transfer") { _, _ -> viewModel.startTransfer() }
            .setNeutralButton("Options…") { _, _ -> showSettingsDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmDialog(selectedCount: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete $selectedCount file(s) from GoPro?")
            .setMessage("This will permanently delete the selected files from the camera's SD card without transferring them. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteSelectedFiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================== permissions

    private fun withPermissionsAndBluetooth(action: () -> Unit) {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            pendingAction = { withBluetooth(action) }
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        withBluetooth(action)
    }

    private fun withBluetooth(action: () -> Unit) {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter != null && !btAdapter.isEnabled) {
            pendingAction = action
            @Suppress("DEPRECATION")
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        action()
    }

    // =========================================================== wifi settings

    private fun openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: inline WiFi panel (quick settings overlay)
                startActivity(Intent(Settings.Panel.ACTION_WIFI))
            } else {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (e: Exception) {
            // Fallback if Panel intent not supported
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Open WiFi settings manually.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================== settings dialog

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_options, null)
        val cbTranscode: CheckBox = view.findViewById(R.id.cb_transcode)
        val cbKeepOriginals: CheckBox = view.findViewById(R.id.cb_keep_originals)
        val cbDeleteFromCamera: CheckBox = view.findViewById(R.id.cb_delete_from_camera)
        val cbSkipExisting: CheckBox = view.findViewById(R.id.cb_skip_existing)
        val btnBleAddress: MaterialButton = view.findViewById(R.id.btn_ble_address)

        val settings = viewModel.settings
        cbTranscode.isChecked = settings.transcode
        cbKeepOriginals.isChecked = settings.keepOriginals
        cbDeleteFromCamera.isChecked = settings.deleteFromCamera
        cbSkipExisting.isChecked = settings.skipExisting

        // Keeping the original only means anything when there is a 1080p copy
        // for it to sit beside.
        fun syncKeepOriginals() {
            cbKeepOriginals.isEnabled = cbTranscode.isChecked
        }
        syncKeepOriginals()
        cbTranscode.setOnCheckedChangeListener { _, _ -> syncKeepOriginals() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.options_title)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                settings.transcode = cbTranscode.isChecked
                settings.keepOriginals = cbKeepOriginals.isChecked
                settings.deleteFromCamera = cbDeleteFromCamera.isChecked
                settings.skipExisting = cbSkipExisting.isChecked
            }
            .setNegativeButton("Cancel", null)
            .create()

        btnBleAddress.setOnClickListener {
            dialog.dismiss()
            showBleAddressDialog()
        }
        dialog.show()
    }

    private fun formatMb(mb: Long): String = when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024.0)
        else -> "$mb MB"
    }

    private fun showBleAddressDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            setText(viewModel.bleAddress ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("GoPro BLE Address")
            .setMessage("Leave blank to auto-scan")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val addr = input.text.toString().trim()
                viewModel.bleAddress = addr.ifEmpty { null }
            }
            .setNegativeButton("Clear") { _, _ -> viewModel.bleAddress = null }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The RecyclerView holds bitmaps; drop them with the Activity.
        binding.recyclerView.adapter = null
    }
}
