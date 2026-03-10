package com.gopro.unloader

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gopro.unloader.databinding.ActivityMainBinding
import com.gopro.unloader.service.GoProForegroundService
import com.gopro.unloader.ui.MainViewModel
import com.gopro.unloader.ui.MediaListAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = MediaListAdapter()

    // ----------------------------------------- Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, "Permissions required for BLE scan.", Toast.LENGTH_LONG).show()
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
            // Auto-scroll to bottom
            binding.scrollViewLog.post {
                binding.scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }
        }

        viewModel.mediaFiles.observe(this) { files ->
            adapter.submitList(files.toList())
            binding.tvFileCount.text = if (files.isEmpty()) "" else "${files.size} file(s)"
        }

        viewModel.wifiCredentials.observe(this) { creds ->
            if (creds != null) {
                binding.cardWifi.visibility = View.VISIBLE
                binding.tvWifiSsid.text = creds.ssid
                binding.tvWifiPassword.text = creds.password
                binding.btnCopyPassword.setOnClickListener {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GoPro WiFi", creds.password))
                    Toast.makeText(this, "Password copied!", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.cardWifi.visibility = View.GONE
            }
        }

        viewModel.isBusy.observe(this) { busy ->
            binding.btnStartOffload.isEnabled = !busy
            binding.btnListFiles.isEnabled = !busy
            binding.progressGlobal.visibility = if (busy) View.VISIBLE else View.GONE

            // Foreground service management
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

        viewModel.phase.observe(this) { phase ->
            binding.tvPhase.text = when (phase) {
                MainViewModel.Phase.BLE_SCAN -> "Scanning via Bluetooth LE…"
                MainViewModel.Phase.WIFI_WAIT -> "Waiting for WiFi connection…"
                MainViewModel.Phase.FETCHING_LIST -> "Fetching media list…"
                MainViewModel.Phase.DOWNLOADING -> "Downloading…"
                MainViewModel.Phase.TRANSCODING -> "Transcoding to 1080p…"
                MainViewModel.Phase.DONE -> "Complete!"
                MainViewModel.Phase.IDLE -> ""
            }
        }
    }

    // =========================================================== buttons

    private fun setupButtons() {
        binding.btnStartOffload.setOnClickListener {
            withPermissionsAndBluetooth { viewModel.startOffload() }
        }
        binding.btnListFiles.setOnClickListener {
            if (viewModel.skipBle) {
                viewModel.listFiles()
            } else {
                withPermissionsAndBluetooth { viewModel.listFiles() }
            }
        }
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

    // =========================================================== settings dialog

    private fun showSettingsDialog() {
        val items = arrayOf(
            "Skip BLE (WiFi already connected)",
            "Keep originals after transcode",
            "Don't delete files from camera",
            "Skip transcoding"
        )
        val checked = booleanArrayOf(
            viewModel.skipBle,
            viewModel.keepOriginals,
            viewModel.noDelete,
            viewModel.noTranscode
        )

        AlertDialog.Builder(this)
            .setTitle("Options")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                viewModel.skipBle = checked[0]
                viewModel.keepOriginals = checked[1]
                viewModel.noDelete = checked[2]
                viewModel.noTranscode = checked[3]
            }
            .setNeutralButton("Set BLE Address") { _, _ ->
                showBleAddressDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
}
