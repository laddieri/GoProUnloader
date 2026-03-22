package com.gopro.unloader.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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

    // Hero 11 Mini Black is video-only; no Photo mode
    private val recordingModePresets = listOf(
        PresetOption("Video",      GoProBleManager.PRESET_GROUP_VIDEO),
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

    private val fovSetting = CameraSetting(43, "Field of View", listOf(
        SettingOption("Wide", 0),
        SettingOption("Linear", 2),
        SettingOption("HyperView", 9),
        SettingOption("Linear + Horizon Leveling", 10)
    ))

    private val videoPerformanceSetting = CameraSetting(168, "Video Performance Mode", listOf(
        SettingOption("Maximum Quality", 0),
        SettingOption("Extended Battery", 1),
        SettingOption("Tripod / Stationary", 2)
    ))

    private val windNoiseSetting = CameraSetting(162, "Wind Noise Reduction", listOf(
        SettingOption("Off", 0),
        SettingOption("Auto", 1)
    ))

    private val bitDepthSetting = CameraSetting(172, "Bit Depth", listOf(
        SettingOption("8-bit", 0),
        SettingOption("10-bit", 2)
    ))

    private val colorSetting = CameraSetting(44, "Color", listOf(
        SettingOption("GoPro (Vivid)", 0),
        SettingOption("Flat", 1),
        SettingOption("Natural", 2)
    ))

    private val rawAudioSetting = CameraSetting(149, "RAW Audio", listOf(
        SettingOption("Off", 0),
        SettingOption("On", 1)
    ))

    private val timelapseSetting = CameraSetting(5, "Timelapse Interval", listOf(
        SettingOption("0.5 seconds", 0),
        SettingOption("1 second", 1),
        SettingOption("2 seconds", 2),
        SettingOption("5 seconds", 3),
        SettingOption("10 seconds", 4),
        SettingOption("30 seconds", 5),
        SettingOption("60 seconds", 6)
    ))

    private val videoFormatSetting = CameraSetting(57, "Video Format", listOf(
        SettingOption("NTSC", 0),
        SettingOption("PAL", 1)
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

    // Note: Hero 11 Mini Black has no GPS hardware; GPS setting omitted

    private val quickCaptureSetting = CameraSetting(24, "Quick Capture", listOf(
        SettingOption("Off", 0),
        SettingOption("On", 1)
    ))

    /** All queryable settings paired with their row view (set in setupRows). */
    private val settingRows = mutableListOf<Pair<CameraSetting, LinearLayout>>()

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

        // Kick off background query for current setting values
        val ids = settingRows.map { it.first.id }
        viewModel.loadCurrentSettings(ids)
    }

    private fun observeViewModel() {
        viewModel.statusLog.observe(this) { log ->
            binding.tvStatusLog.text = log
            binding.scrollViewLog.post { binding.scrollViewLog.fullScroll(View.FOCUS_DOWN) }
        }
        viewModel.isBusy.observe(this) { busy ->
            binding.progressSettings.visibility = if (busy) View.VISIBLE else View.GONE
        }
        viewModel.currentSettings.observe(this) { settings ->
            for ((cameraSetting, row) in settingRows) {
                val rawValue = settings[cameraSetting.id] ?: continue
                val label = cameraSetting.options
                    .firstOrNull { (it.value.toInt() and 0xFF) == rawValue }
                    ?.label ?: continue
                subtitle(row).text = label
            }
        }
    }

    private fun setupRows() {
        binding.rowRecordingMode.setOnClickListener      { showPresetDialog() }

        fun reg(setting: CameraSetting, row: LinearLayout) {
            settingRows += setting to row
            row.setOnClickListener { showOptionsDialog(setting) }
        }

        reg(resolutionSetting,       binding.rowResolution)
        reg(frameRateSetting,        binding.rowFrameRate)
        reg(hypersmoothSetting,      binding.rowHypersmooth)
        reg(horizonLockSetting,      binding.rowHorizonLock)
        reg(fovSetting,              binding.rowFov)
        reg(videoPerformanceSetting, binding.rowVideoPerformance)
        reg(windNoiseSetting,        binding.rowWindNoise)
        reg(bitDepthSetting,         binding.rowBitDepth)
        reg(colorSetting,            binding.rowColor)
        reg(rawAudioSetting,         binding.rowRawAudio)
        reg(timelapseSetting,        binding.rowTimelapseInterval)
        reg(autoPowerOffSetting,     binding.rowAutoPowerOff)
        reg(beepsSetting,            binding.rowBeeps)
        reg(ledSetting,              binding.rowLeds)
        reg(antiFlickerSetting,      binding.rowAntiFlicker)
        reg(quickCaptureSetting,     binding.rowQuickCapture)
        reg(videoFormatSetting,      binding.rowVideoFormat)
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
        val currentRaw = viewModel.currentSettings.value?.get(setting.id)
        val currentIdx = if (currentRaw != null) {
            setting.options.indexOfFirst { (it.value.toInt() and 0xFF) == currentRaw }.takeIf { it >= 0 }
        } else null

        AlertDialog.Builder(this)
            .setTitle(setting.name)
            .setSingleChoiceItems(labels, currentIdx ?: -1) { dialog, which ->
                val opt = setting.options[which]
                viewModel.applySetting(setting.id, opt.value, setting.name)
                // Optimistically update the subtitle
                val row = settingRows.firstOrNull { it.first.id == setting.id }?.second
                row?.let { subtitle(it).text = opt.label }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Returns the subtitle TextView (child index 1) of a settings row. */
    private fun subtitle(row: LinearLayout): TextView = row.getChildAt(1) as TextView
}
