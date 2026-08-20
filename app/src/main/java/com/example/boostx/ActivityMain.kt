package com.example.boostx

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        /** Half-step resolution on the 0–10 boost scale ≈ 0.15 dB per notch. */
        private const val GRADUAL_STEP = 0.5f
    }

    private lateinit var boostSlider: Slider
    private lateinit var volumeSlider: Slider
    private lateinit var claritySlider: Slider
    private lateinit var depthSlider: Slider
    private lateinit var balanceSlider: Slider
    private lateinit var gradualBoostSwitch: MaterialSwitch
    private lateinit var bootStartSwitch: MaterialSwitch
    private lateinit var enhanceSwitch: MaterialSwitch
    private lateinit var noiseGateSwitch: MaterialSwitch
    private lateinit var normalizeSwitch: MaterialSwitch
    private lateinit var callBoostSwitch: MaterialSwitch
    private lateinit var noiseGateText: TextView
    private lateinit var normalizeText: TextView
    private lateinit var callBoostText: TextView
    private lateinit var bootStartText: TextView
    private lateinit var boostTextView: TextView
    private lateinit var volumeTextView: TextView
    private lateinit var clarityTextView: TextView
    private lateinit var depthTextView: TextView
    private lateinit var balanceLevelTextView: TextView
    private lateinit var enhanceTitle: TextView
    private lateinit var enhancePanel: LinearLayout
    private lateinit var presetGroup: MaterialButtonToggleGroup
    private lateinit var outputDeviceTextView: TextView
    private lateinit var serviceStatusView: TextView
    private lateinit var deviceProfileBar: LinearLayout
    private lateinit var deviceProfileText: TextView
    private lateinit var saveProfileButton: MaterialButton
    private lateinit var compareButton: MaterialButton
    private lateinit var effectsFailedBanner: LinearLayout
    private lateinit var retryEffectsButton: MaterialButton

    /** Voicing presets in the same order as the buttons in the toggle group. */
    private val presetButtons by lazy {
        listOf(
            R.id.presetBalanced to AudioController.Preset.BALANCED,
            R.id.presetVocal to AudioController.Preset.VOCAL,
            R.id.presetBass to AudioController.Preset.BASS,
            R.id.presetMovie to AudioController.Preset.MOVIE,
            R.id.presetNight to AudioController.Preset.NIGHT
        )
    }

    private var audioService: AudioBoostService? = null
    private var isBound = false

    private val profileManager by lazy { DeviceProfileManager(this) }
    private var currentProfileKey: String? = null
    private var currentDeviceName: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val deviceInfoRunnable = object : Runnable {
        override fun run() {
            updateOutputDeviceInfoUI()
            handler.postDelayed(this, 1800)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioBoostService.LocalBinder
            audioService = binder.getService()
            isBound = true
            serviceStatusView.text = getString(R.string.service_running)

            // Wire device-change callback into the running AudioController
            audioService?.audioController?.onDeviceChanged = { key, deviceName, isBt ->
                currentProfileKey = key
                currentDeviceName = deviceName
                runOnUiThread { showDeviceProfileBar(key, deviceName, isBt) }
            }
            audioService?.audioController?.onDeviceRemoved = {
                currentProfileKey = null
                runOnUiThread { deviceProfileBar.visibility = View.GONE }
            }

            // Wire the effects-failure banner — and reflect state that may have already
            // changed before the UI attached (e.g. service already retried and gave up).
            audioService?.audioController?.onEffectsFailed = {
                runOnUiThread { showEffectsFailedBanner(true) }
            }
            audioService?.audioController?.onEffectsRecovered = {
                runOnUiThread { showEffectsFailedBanner(false) }
            }
            showEffectsFailedBanner(audioService?.audioController?.effectsFailed == true)

            audioService?.audioController?.let { syncBoostCeiling(it) }
            showEngineHint()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
            serviceStatusView.text = getString(R.string.service_stopped)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_BoostX)
        setContentView(R.layout.activity_main)

        checkPermissions()
        bindViews()
        setupSliders()
        loadSavedState()
        checkBatteryOptimizations()
    }

    private fun bindViews() {
        boostSlider = findViewById(R.id.boostSlider)
        volumeSlider = findViewById(R.id.volumeSlider)
        claritySlider = findViewById(R.id.claritySlider)
        depthSlider = findViewById(R.id.depthSlider)
        balanceSlider = findViewById(R.id.balanceSlider)
        gradualBoostSwitch = findViewById(R.id.gradualBoostSwitch)
        bootStartSwitch = findViewById(R.id.bootStartSwitch)
        enhanceSwitch = findViewById(R.id.enhanceSwitch)
        noiseGateSwitch = findViewById(R.id.noiseGateSwitch)
        normalizeSwitch = findViewById(R.id.normalizeSwitch)
        callBoostSwitch = findViewById(R.id.callBoostSwitch)
        noiseGateText = findViewById(R.id.noiseGateText)
        normalizeText = findViewById(R.id.normalizeText)
        callBoostText = findViewById(R.id.callBoostText)
        bootStartText = findViewById(R.id.bootStartText)
        boostTextView = findViewById(R.id.boostLevel)
        volumeTextView = findViewById(R.id.volumeLevel)
        clarityTextView = findViewById(R.id.clarityLevel)
        depthTextView = findViewById(R.id.depthLevel)
        balanceLevelTextView = findViewById(R.id.balanceLevel)
        enhanceTitle = findViewById(R.id.enhanceTitle)
        enhancePanel = findViewById(R.id.enhancePanel)
        presetGroup = findViewById(R.id.presetGroup)
        outputDeviceTextView = findViewById(R.id.outputDeviceText)
        serviceStatusView = findViewById(R.id.serviceStatus)
        deviceProfileBar = findViewById(R.id.deviceProfileBar)
        deviceProfileText = findViewById(R.id.deviceProfileText)
        saveProfileButton = findViewById(R.id.saveProfileButton)
        compareButton = findViewById(R.id.compareButton)
        effectsFailedBanner = findViewById(R.id.effectsFailedBanner)
        retryEffectsButton = findViewById(R.id.retryEffectsButton)

        deviceProfileBar.visibility = View.GONE
        effectsFailedBanner.visibility = View.GONE

        saveProfileButton.setOnClickListener { saveCurrentProfile() }
        findViewById<TextView>(R.id.infoIcon).setOnClickListener { showAppInfo() }
        retryEffectsButton.setOnClickListener { audioService?.audioController?.retrySetup() }

        compareButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    audioService?.audioController?.setBypassed(true)
                    compareButton.text = getString(R.string.compare_active_label)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    audioService?.audioController?.setBypassed(false)
                    compareButton.text = getString(R.string.compare_button_label)
                }
            }
            false
        }
    }

    private fun setupSliders() {
        val originalProps = mapOf(
            "valueFrom" to boostSlider.valueFrom,
            "valueTo" to boostSlider.valueTo,
            "stepSize" to boostSlider.stepSize,
            "thumbRadius" to boostSlider.thumbRadius,
            "thumbHeight" to boostSlider.thumbHeight,
            "thumbWidth" to boostSlider.thumbWidth,
            "thumbTintList" to boostSlider.thumbTintList,
            "trackHeight" to boostSlider.trackHeight,
            "trackInsideCornerSize" to boostSlider.trackInsideCornerSize,
            "isTickVisible" to boostSlider.isTickVisible
        )

        gradualBoostSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleGradualBoostSwitch(isChecked, originalProps)
            saveBoolean("gradual_boost", isChecked)
        }

        bootStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleBootStartSwitch(isChecked)
            saveBoolean("boot_start", isChecked)
        }

        enhanceSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleEnhanceSwitch(isChecked)
            audioService?.setEnhanceEnabled(isChecked)
        }

        noiseGateSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleAccentSwitch(noiseGateSwitch, noiseGateText, isChecked)
            audioService?.setNoiseGateEnabled(isChecked)
        }

        normalizeSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleAccentSwitch(normalizeSwitch, normalizeText, isChecked)
            audioService?.setNormalizeEnabled(isChecked)
        }

        callBoostSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleAccentSwitch(callBoostSwitch, callBoostText, isChecked)
            audioService?.setCallBoostEnabled(isChecked)
        }

        presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            presetButtons.firstOrNull { it.first == checkedId }?.let { (_, preset) ->
                audioService?.applyPreset(preset)
            }
        }

        boostSlider.addOnChangeListener { _, value, _ -> applyBoost(value) }
        volumeSlider.addOnChangeListener { _, value, _ -> applyVolume(value.toInt()) }
        claritySlider.addOnChangeListener { _, value, _ -> applyClarity(value.toInt()) }
        depthSlider.addOnChangeListener { _, value, _ -> applyDepth(value.toInt()) }
        balanceSlider.addOnChangeListener { _, value, _ -> applyBalance(value.toInt()) }
    }

    private fun loadSavedState() {
        val prefs = getSharedPreferences(AudioBoostService.PREFS, Context.MODE_PRIVATE)
        val savedBoost = migrateBoostScale(prefs.getFloat(AudioBoostService.KEY_BOOST, 0f))
        val savedVolume = prefs.getFloat(AudioBoostService.KEY_VOLUME, 100f)
        val savedGradual = prefs.getBoolean("gradual_boost", false)
        val savedBootStart = prefs.getBoolean("boot_start", false)
        val savedClarity = prefs.getInt(AudioBoostService.KEY_CLARITY, 0)
        val savedDepth = prefs.getInt(AudioBoostService.KEY_DEPTH, 0)
        val savedBalance = prefs.getInt(AudioBoostService.KEY_BALANCE, 0)
        val savedEnhance = prefs.getBoolean(AudioBoostService.KEY_ENHANCE, false)
        val savedNoiseGate = prefs.getBoolean(AudioBoostService.KEY_NOISE_GATE, false)
        val savedNormalize = prefs.getBoolean(AudioBoostService.KEY_NORMALIZE, false)
        val savedCallBoost = prefs.getBoolean(AudioBoostService.KEY_CALL_BOOST, false)
        val savedPreset = AudioController.Preset.fromKey(
            prefs.getString(AudioBoostService.KEY_PRESET, null)
        )

        gradualBoostSwitch.isChecked = savedGradual
        handleGradualBoostSwitch(savedGradual, emptyMap())

        bootStartSwitch.isChecked = savedBootStart
        handleBootStartSwitch(savedBootStart)

        enhanceSwitch.isChecked = savedEnhance
        handleEnhanceSwitch(savedEnhance)

        // Self-heal a stale "noise gate on, enhance off" combo — the gate has no effect
        // without Enhance, so don't restore it as checked in a state where it's inert.
        val restoredNoiseGate = savedNoiseGate && savedEnhance
        noiseGateSwitch.isChecked = restoredNoiseGate
        handleAccentSwitch(noiseGateSwitch, noiseGateText, restoredNoiseGate)

        normalizeSwitch.isChecked = savedNormalize
        handleAccentSwitch(normalizeSwitch, normalizeText, savedNormalize)

        callBoostSwitch.isChecked = savedCallBoost
        handleAccentSwitch(callBoostSwitch, callBoostText, savedCallBoost)

        presetGroup.check(
            presetButtons.firstOrNull { it.second == savedPreset }?.first ?: R.id.presetBalanced
        )

        boostSlider.value = snapToStep(savedBoost, boostSlider)
        volumeSlider.value = savedVolume.coerceIn(volumeSlider.valueFrom, volumeSlider.valueTo)
        claritySlider.value = snapToStep(savedClarity.toFloat(), claritySlider)
        depthSlider.value = snapToStep(savedDepth.toFloat(), depthSlider)
        balanceSlider.value = snapToStep(savedBalance.toFloat(), balanceSlider)

        updateBoostUI(boostSlider.value)
        updateVolumeUI(savedVolume.toInt())
        updateClarityUI(savedClarity)
        updateDepthUI(savedDepth)
        updateBalanceUI(savedBalance)
    }

    /** Builds before the 10 % ceiling stored boost on a 0–100 scale — rescale it once. */
    private fun migrateBoostScale(saved: Float): Float =
        if (saved > AudioController.MAX_BOOST_PERCENT) saved / 10f else saved

    private fun snapToStep(value: Float, slider: Slider): Float {
        val step = slider.stepSize
        val snapped = if (step > 0) (value / step).roundToInt() * step else value
        return snapped.coerceIn(slider.valueFrom, slider.valueTo)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioBoostService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(deviceInfoRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(deviceInfoRunnable)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio actions — all routed through the bound service
    // ──────────────────────────────────────────────────────────────────────────

    private fun applyBoost(level: Float) {
        updateBoostUI(level)
        // AudioBoostService.savePrefs() is the single owner of last_active_boost now too.
        if (isBound) audioService?.applyBoost(level)
    }

    private fun applyVolume(level: Int) {
        updateVolumeUI(level)
        audioService?.applyVolume(level)
    }

    private fun applyClarity(level: Int) {
        updateClarityUI(level)
        audioService?.applyClarity(level)
    }

    private fun applyDepth(level: Int) {
        updateDepthUI(level)
        audioService?.applyDepth(level)
    }

    private fun applyBalance(level: Int) {
        updateBalanceUI(level)
        audioService?.applyBalance(level)
    }

    private fun updateBoostUI(level: Float) {
        boostTextView.text = getString(R.string.boost_value_format, level)
        // Thresholds sit on the 0–10 scale: past 7 is where a busy mix starts working the limiter.
        boostTextView.setTextColor(
            when {
                level > 7f -> "#F92672".toColorInt()
                level > 4f -> "#FFA500".toColorInt()
                else -> Color.GRAY
            }
        )
    }

    private fun updateVolumeUI(level: Int) {
        volumeTextView.text = "$level%"
    }

    private fun updateClarityUI(level: Int) {
        clarityTextView.text = "$level%"
    }

    private fun updateDepthUI(level: Int) {
        depthTextView.text = "$level%"
    }

    private fun updateBalanceUI(level: Int) {
        balanceLevelTextView.text = when {
            level == 0 -> getString(R.string.balance_center)
            level < 0 -> getString(R.string.balance_left_format, -level)
            else -> getString(R.string.balance_right_format, level)
        }
    }

    private fun handleEnhanceSwitch(isChecked: Boolean) {
        val active = "#CCFF00".toColorInt()
        enhanceSwitch.thumbTintList = ColorStateList.valueOf(if (isChecked) active else Color.GRAY)
        enhanceSwitch.trackTintList =
            ColorStateList.valueOf(if (isChecked) "#666600".toColorInt() else Color.DKGRAY)
        enhanceTitle.setTextColor(if (isChecked) Color.WHITE else Color.GRAY)

        // Controls stay visible when off so the panel does not jump around — just inert.
        enhancePanel.alpha = if (isChecked) 1f else 0.35f
        setPanelEnabled(enhancePanel, isChecked)

        // Noise Gate lives inside the MBC stage, which Enhance also gates off — keep the
        // switch honest instead of leaving it checked while it silently does nothing.
        if (!isChecked && noiseGateSwitch.isChecked) {
            noiseGateSwitch.isChecked = false
        }
    }

    /** Shared on/off styling for the simple accent switches (noise gate, auto-loudness, call boost). */
    private fun handleAccentSwitch(switch: MaterialSwitch, label: TextView, isChecked: Boolean) {
        val active = "#CCFF00".toColorInt()
        switch.thumbTintList = ColorStateList.valueOf(if (isChecked) active else Color.GRAY)
        switch.trackTintList =
            ColorStateList.valueOf(if (isChecked) "#666600".toColorInt() else Color.DKGRAY)
        label.setTextColor(if (isChecked) Color.WHITE else Color.GRAY)
    }

    /** Shows or hides the "audio effects unavailable" banner without disturbing other UI. */
    private fun showEffectsFailedBanner(show: Boolean) {
        effectsFailedBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * The slider's range must match the device's real safety ceiling (lower on Dolby devices),
     * or the displayed percentage and its danger-color coding describe a level that was never
     * actually applied — the engine silently clamps past this without telling the UI.
     */
    private fun syncBoostCeiling(ctrl: AudioController) {
        val ceiling = ctrl.maxSafeBoostLevel
        if (boostSlider.valueTo == ceiling) return
        if (boostSlider.value > ceiling) {
            boostSlider.value = ceiling
            applyBoost(ceiling)
        }
        boostSlider.valueTo = ceiling
    }

    /**
     * Dolby Atmos (One UI) sits after us in the chain and re-EQs everything we do,
     * so on a Samsung device the hint points at turning it off for a clean result.
     */
    private fun showEngineHint() {
        val ctrl = audioService?.audioController ?: return
        val hint = findViewById<TextView>(R.id.enhanceHint)
        hint.text = when {
            ctrl.isDolbyPresent -> getString(R.string.enhance_hint_dolby)
            !ctrl.supportsAdvancedEngine -> getString(R.string.enhance_hint_legacy)
            else -> getString(R.string.enhance_hint)
        }
    }

    private fun setPanelEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setPanelEnabled(view.getChildAt(i), enabled)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Device profiles
    // ──────────────────────────────────────────────────────────────────────────

    private fun showDeviceProfileBar(key: String, deviceName: String, isBt: Boolean) {
        if (!isBt) { deviceProfileBar.visibility = View.GONE; return }
        deviceProfileBar.visibility = View.VISIBLE
        val hasSaved = profileManager.has(key)
        deviceProfileText.text = if (hasSaved)
            getString(R.string.profile_loaded, deviceName)
        else
            getString(R.string.profile_none, deviceName)

        if (hasSaved) {
            val profile = profileManager.load(key)!!

            val boost = snapToStep(profile.boostLevel, boostSlider)
            if (boostSlider.value != boost) {
                boostSlider.value = boost
                applyBoost(boost)
            }

            // Voicing is per-device too — headphones and speakers want different curves.
            enhanceSwitch.isChecked = profile.enhance
            presetGroup.check(
                presetButtons.firstOrNull { it.second == profile.preset }?.first
                    ?: R.id.presetBalanced
            )
            claritySlider.value = snapToStep(profile.clarity.toFloat(), claritySlider)
            depthSlider.value = snapToStep(profile.depth.toFloat(), depthSlider)
        }
    }

    private fun saveCurrentProfile() {
        val key = currentProfileKey ?: return
        val preset = presetButtons.firstOrNull { it.first == presetGroup.checkedButtonId }?.second
            ?: AudioController.Preset.BALANCED
        profileManager.save(
            key,
            DeviceProfileManager.Profile(
                boostLevel = boostSlider.value,
                volumeLevel = volumeSlider.value.toInt(),
                clarity = claritySlider.value.toInt(),
                depth = depthSlider.value.toInt(),
                preset = preset,
                enhance = enhanceSwitch.isChecked
            )
        )
        deviceProfileText.text = getString(R.string.profile_saved, currentDeviceName)
        Toast.makeText(this, getString(R.string.profile_saved, currentDeviceName), Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Gradual / Boot switches
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Gradual mode gives half-step resolution on the boost slider. The range itself
     * stays pinned to the 10 % ceiling — only the step size and the thumb change.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleGradualBoostSwitch(isChecked: Boolean, original: Map<String, Any>) {
        if (isChecked) {
            gradualBoostSwitch.thumbTintList = ColorStateList.valueOf("#CCFF00".toColorInt())
            gradualBoostSwitch.trackTintList = ColorStateList.valueOf("#666600".toColorInt())
            gradualBoostSwitch.setTextColor(Color.WHITE)

            boostSlider.stepSize = GRADUAL_STEP
            boostSlider.thumbRadius = volumeSlider.thumbRadius
            boostSlider.thumbHeight = volumeSlider.thumbHeight
            boostSlider.thumbWidth = volumeSlider.thumbWidth
            boostSlider.thumbTintList = volumeSlider.thumbTintList
            boostSlider.trackHeight = volumeSlider.trackHeight
            boostSlider.trackInsideCornerSize = volumeSlider.trackInsideCornerSize
            boostSlider.isTickVisible = false
        } else {
            gradualBoostSwitch.thumbTintList = ColorStateList.valueOf(Color.GRAY)
            gradualBoostSwitch.trackTintList = ColorStateList.valueOf(Color.DKGRAY)
            gradualBoostSwitch.setTextColor(Color.GRAY)

            if (original.isNotEmpty()) {
                // Land on a whole step before tightening the step size, or the slider throws.
                boostSlider.value = boostSlider.value.roundToInt().toFloat()
                    .coerceIn(boostSlider.valueFrom, boostSlider.valueTo)
                boostSlider.stepSize = original["stepSize"] as Float
                boostSlider.thumbRadius = original["thumbRadius"] as Int
                boostSlider.thumbHeight = original["thumbHeight"] as Int
                boostSlider.thumbWidth = original["thumbWidth"] as Int
                boostSlider.thumbTintList = original["thumbTintList"] as ColorStateList
                boostSlider.trackHeight = original["trackHeight"] as Int
                boostSlider.trackInsideCornerSize = original["trackInsideCornerSize"] as Int
                boostSlider.isTickVisible = original["isTickVisible"] as Boolean
            }
        }
    }

    private fun handleBootStartSwitch(isChecked: Boolean) {
        val active = "#CCFF00".toColorInt()
        bootStartSwitch.thumbTintList = ColorStateList.valueOf(if (isChecked) active else Color.GRAY)
        bootStartSwitch.trackTintList = ColorStateList.valueOf(if (isChecked) "#666600".toColorInt() else Color.DKGRAY)
        bootStartText.setTextColor(if (isChecked) Color.WHITE else Color.GRAY)
    }

    private fun updateOutputDeviceInfoUI() {
        val info = audioService?.audioController?.getOutputDeviceInfo() ?: return
        outputDeviceTextView.text = info
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Permissions / battery
    // ──────────────────────────────────────────────────────────────────────────

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                MaterialAlertDialogBuilder(this, R.style.CustomDialogTheme)
                    .setTitle(getString(R.string.battery_opt_title))
                    .setMessage(getString(R.string.battery_opt_message))
                    .setPositiveButton(getString(R.string.settings_label)) { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton(getString(R.string.cancel_label), null)
                    .show()
            }
        }
    }

    private fun showAppInfo() {
        val dialog = MaterialAlertDialogBuilder(this, R.style.CustomDialogTheme)
        val title = SpannableString(getString(R.string.app_info_title))
        title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        dialog.setTitle(title)

        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        val builder = SpannableStringBuilder()
        builder.append("${getString(R.string.version_label)}\t\t\t\t$ver\n")
        builder.append("${getString(R.string.api_level_label)}\t${Build.VERSION.SDK_INT}\n")

        // Snapdragon / Samsung info
        audioService?.audioController?.let { ctrl ->
            val summary = ctrl.getDeviceSummary()
            if (summary.isNotEmpty()) {
                builder.append("Device\t\t\t\t\t$summary\n")
            }
        }

        val devLink = SpannableString("Om Gupta")
        devLink.setSpan(URLSpan("https://github.com/AumGupta"), 0, devLink.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append("${getString(R.string.developer_label)}\t")
        builder.append(devLink)
        builder.append("\n")

        val srcLink = SpannableString("GitHub")
        srcLink.setSpan(URLSpan("https://github.com/AumGupta/BoostX"), 0, srcLink.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append("${getString(R.string.source_label)}\t\t\t\t\t")
        builder.append(srcLink)
        builder.append("\n\n")

        val note = SpannableString(
            "${getString(R.string.session_id_label)}\t\t${audioService?.audioController?.audioSessionID ?: "—"}\n" +
            "${getString(R.string.package_label)}\t\t\t\t\t\t$packageName\n\n" +
            getString(R.string.warning_text)
        )
        note.setSpan(ForegroundColorSpan(Color.GRAY), 0, note.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        note.setSpan(RelativeSizeSpan(0.85f), 0, note.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(note)

        val tv = TextView(this).apply {
            text = builder
            setBackgroundColor("#202020".toColorInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(48, 48, 48, 48)
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#CCFF00".toColorInt())
            addView(spacer)
            addView(tv)
        }

        dialog.setView(layout).create().show()
    }

    private fun saveBoolean(key: String, value: Boolean) {
        getSharedPreferences("BoostXPrefs", MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }
}
