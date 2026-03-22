package com.gopro.unloader.ui

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gopro.unloader.ble.GoProBleManager
import com.gopro.unloader.databinding.ActivityCameraSettingsBinding

class CameraSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BLE_ADDRESS = "ble_address"
    }

    private lateinit var binding: ActivityCameraSettingsBinding
    private val viewModel: CameraSettingsViewModel by viewModels()

    // ------------------------------------------------------------------ setting data

    private data class SettingOption(val label: String, val value: Byte)
    private data class CameraSetting(val id: Int, val name: String, val options: List<SettingOption>)
    private data class PresetOption(val label: String, val cmd: ByteArray)

    private val recordingModePresets = listOf(
        PresetOption("Video",      GoProBleManager.PRESET_GROUP_VIDEO),
        PresetOption("Photo",      GoProBleManager.PRESET_GROUP_PHOTO),
        PresetOption("Timelapse",  GoProBleManager.PRESET_GROUP_TIMELAPSE)
    )

    private val resolutionSetting = CameraSetting(2, "Resolution", listOf(
        SettingOption("1080p", 9),
        SettingOption("2.7K", 4),
        SettingOption("4K", 1),
        SettingOption("4K 4:3", 18),
        SettingOption("5.3K (Hero 11+)", 25)
    ))

    private val frameRateSetting = CameraSetting(3, "Frame Rate", listOf(
        SettingOption("24 fps", 10),
        SettingOption("25 fps", 9),
        SettingOption("30 fps", 8),
        SettingOption("50 fps", 6),
        SettingOption("60 fps", 5),
        SettingOption("100 fps", 2),
        SettingOption("120 fps", 1),
        SettingOption("240 fps", 0)
    ))

    private val hypersmoothSetting = CameraSetting(121, "Hypersmooth", listOf(
        SettingOption("Off", 0),
        SettingOption("On", 1),
        SettingOption("High", 2),
        SettingOption("Boost", 3),
        SettingOption("AutoBoost (Hero 11+)", 4)
    ))

    private val horizonLockSetting = CameraSetting(122, "Horizon Lock", listOf(
        SettingOption("Off", 0),
        SettingOption("Locked (Hero 11+)", 2)
    ))

    private val autoPowerOffSetting = CameraSetting(59, "Auto Power Off", listOf(
        SettingOption("Never", 0),
        SettingOption("1 minute", 1),
        SettingOption("5 minutes", 2),
        SettingOption("15 minutes", 3),
        SettingOption("30 minutes", 4)
    ))

    private val beepsSetting = CameraSetting(87, "Beeps / Volume", listOf(
        SettingOption("Mute", 0),
        SettingOption("Low (40%)", 40),
        SettingOption("Medium (70%)", 70),
        SettingOption("High (100%)", 100.toByte())
    ))

    private val ledSetting = CameraSetting(91, "LEDs", listOf(
        SettingOption("All Off", 0),
        SettingOption("Front Only", 2),
        SettingOption("All On", 3)
    ))

    private val antiFlickerSetting = CameraSetting(134, "Anti-Flicker", listOf(
        SettingOption("60 Hz (NTSC)", 0),
        SettingOption("50 Hz (PAL)", 1)
    ))

    private val gpsSetting = CameraSetting(83, "GPS", listOf(
        SettingOption("Off", 0),
        SettingOption("On", 1)
    ))

    private val quickCaptureSetting = CameraSetting(24, "Quick Capture", listOf(
        SettingOption("Off", 0),
        SettingOption("On", 1)
    ))

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        viewModel.bleAddress = intent.getStringExtra(EXTRA_BLE_ADDRESS)

        observeViewModel()
        setupRows()
    }

    private fun observeViewModel() {
        viewModel.statusLog.observe(this) { log ->
            binding.tvStatusLog.text = log
            binding.scrollViewLog.post { binding.scrollViewLog.fullScroll(View.FOCUS_DOWN) }
        }
        viewModel.isBusy.observe(this) { busy ->
            binding.progressSettings.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }

    private fun setupRows() {
        binding.rowRecordingMode.setOnClickListener { showPresetDialog() }
        binding.rowResolution.setOnClickListener    { showOptionsDialog(resolutionSetting) }
        binding.rowFrameRate.setOnClickListener     { showOptionsDialog(frameRateSetting) }
        binding.rowHypersmooth.setOnClickListener   { showOptionsDialog(hypersmoothSetting) }
        binding.rowHorizonLock.setOnClickListener   { showOptionsDialog(horizonLockSetting) }
        binding.rowAutoPowerOff.setOnClickListener  { showOptionsDialog(autoPowerOffSetting) }
        binding.rowBeeps.setOnClickListener         { showOptionsDialog(beepsSetting) }
        binding.rowLeds.setOnClickListener          { showOptionsDialog(ledSetting) }
        binding.rowAntiFlicker.setOnClickListener   { showOptionsDialog(antiFlickerSetting) }
        binding.rowGps.setOnClickListener           { showOptionsDialog(gpsSetting) }
        binding.rowQuickCapture.setOnClickListener  { showOptionsDialog(quickCaptureSetting) }
    }

    private fun showPresetDialog() {
        val labels = recordingModePresets.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recording Mode")
            .setItems(labels) { _, which ->
                val preset = recordingModePresets[which]
                viewModel.loadPresetGroup(preset.cmd, preset.label)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOptionsDialog(setting: CameraSetting) {
        val labels = setting.options.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(setting.name)
            .setItems(labels) { _, which ->
                val opt = setting.options[which]
                viewModel.applySetting(setting.id, opt.value, setting.name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
