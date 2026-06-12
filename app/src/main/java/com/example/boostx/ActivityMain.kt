package com.example.boostx

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var boostSlider: Slider
    private lateinit var volumeSlider: Slider
    private lateinit var gradualBoostSwitch: MaterialSwitch
    private lateinit var boostTextView: TextView
    private lateinit var volumeTextView: TextView
    private lateinit var outputDeviceTextView: TextView

    private lateinit var audioController: AudioController

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateOutputDeviceInfoUI()
            handler.postDelayed(this, 1800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_BoostX)
        setContentView(R.layout.activity_main)

        audioController = AudioController(this)

        boostSlider = findViewById(R.id.boostSlider)
        volumeSlider = findViewById(R.id.volumeSlider)
        gradualBoostSwitch = findViewById(R.id.gradualBoostSwitch)

        val originalBoostSliderProperties = mutableMapOf(
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
            handleGradualBoostSwitch(isChecked, originalBoostSliderProperties)
        }

        val prefs = getSharedPreferences("BoostXPrefs", Context.MODE_PRIVATE)
        val savedBoost = prefs.getFloat("boost_value", 0f)
        val savedVolume = prefs.getFloat("volume_value", 100f)
        val savedGradual = prefs.getBoolean("gradual_boost", false)

        gradualBoostSwitch.isChecked = savedGradual
        handleGradualBoostSwitch(savedGradual, originalBoostSliderProperties)

        volumeSlider.value = savedVolume

        boostTextView = findViewById(R.id.boostLevel)
        volumeTextView = findViewById(R.id.volumeLevel)
        outputDeviceTextView = findViewById(R.id.outputDeviceText)

        val currentStepSize = boostSlider.stepSize
        val validatedBoost = if (currentStepSize > 0) {
            (savedBoost / currentStepSize).roundToInt() * currentStepSize
        } else {
            savedBoost
        }.coerceIn(boostSlider.valueFrom, boostSlider.valueTo)

        boostSlider.value = validatedBoost

        applyBoost(validatedBoost.toInt())
        applyVolume(savedVolume.toInt())

        boostSlider.addOnChangeListener { _, value, _ -> applyBoost(value.toInt()) }
        volumeSlider.addOnChangeListener { _, value, _ -> applyVolume(value.toInt()) }

        findViewById<TextView>(R.id.infoIcon).setOnClickListener {
            showAppInfo()
        }
    }

    private fun showAppInfo(){
        val infoDialog = MaterialAlertDialogBuilder(this, R.style.CustomDialogTheme)

        val title = SpannableString(getString(R.string.app_info_title))
        title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        infoDialog.setTitle(title)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName

        val infoBuilder = SpannableStringBuilder()

        infoBuilder.append("${getString(R.string.version_label)}\t\t\t\t$versionName\n")
        infoBuilder.append("${getString(R.string.api_level_label)}\t${Build.VERSION.SDK_INT}\n")

        val devLabel = "${getString(R.string.developer_label)}\t"
        val devLink = SpannableString("Om Gupta")
        devLink.setSpan(URLSpan("https://github.com/AumGupta"), 0, devLink.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        infoBuilder.append(devLabel)
        infoBuilder.append(devLink)
        infoBuilder.append("\n")

        val sourceLabel = "${getString(R.string.source_label)}\t\t\t\t\t"
        val sourceLink = SpannableString("GitHub")
        sourceLink.setSpan(URLSpan("https://github.com/AumGupta/BoostX"), 0, sourceLink.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        infoBuilder.append(sourceLabel)
        infoBuilder.append(sourceLink)
        infoBuilder.append("\n\n")

        val noteText = SpannableString(
            "${getString(R.string.session_id_label)}\t\t${audioController.audioSessionID}\n" +
                    "${getString(R.string.package_label)}\t\t\t\t\t\t$packageName\n\n" +
                    getString(R.string.warning_text)
        )
        noteText.setSpan(ForegroundColorSpan(Color.GRAY), 0, noteText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        noteText.setSpan(RelativeSizeSpan(0.85f), 0, noteText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        infoBuilder.append(noteText)

        val textView = TextView(this)
        textView.text = infoBuilder
        textView.setBackgroundColor("#202020".toColorInt())
        textView.setTextColor(Color.WHITE)
        textView.textSize = 16f
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.setPadding(48, 48, 48, 48)

        val spacer = View(this)
        val spacerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            50
        )
        spacer.layoutParams = spacerParams

        val linearLayout = LinearLayout(this)
        linearLayout.orientation = LinearLayout.VERTICAL
        linearLayout.setBackgroundColor("#CCFF00".toColorInt())
        linearLayout.addView(spacer)
        linearLayout.addView(textView)

        val dialog = infoDialog.setView(linearLayout)
            .create()

        dialog.show()
    }

    private fun handleGradualBoostSwitch(isChecked:Boolean, originalBoostSliderProperties:MutableMap<String,Any>){
        if (isChecked) {
            gradualBoostSwitch.thumbTintList = ColorStateList.valueOf("#CCFF00".toColorInt())
            gradualBoostSwitch.trackTintList = ColorStateList.valueOf("#666600".toColorInt())
            gradualBoostSwitch.setTextColor(Color.WHITE)

            boostSlider.valueFrom = volumeSlider.valueFrom
            boostSlider.valueTo = volumeSlider.valueTo
            boostSlider.stepSize = volumeSlider.stepSize
            boostSlider.thumbRadius = volumeSlider.thumbRadius
            boostSlider.thumbHeight = volumeSlider.thumbHeight
            boostSlider.thumbWidth = volumeSlider.thumbWidth
            boostSlider.thumbTintList = volumeSlider.thumbTintList
            boostSlider.trackHeight = volumeSlider.trackHeight
            boostSlider.trackInsideCornerSize = volumeSlider.trackInsideCornerSize
            boostSlider.isTickVisible = volumeSlider.isTickVisible

        } else {
            gradualBoostSwitch.thumbTintList = ColorStateList.valueOf(Color.GRAY)
            gradualBoostSwitch.trackTintList = ColorStateList.valueOf(Color.DKGRAY)
            gradualBoostSwitch.setTextColor(Color.GRAY)

            boostSlider.value = (boostSlider.value / 10).roundToInt() * 10f

            boostSlider.valueFrom = originalBoostSliderProperties["valueFrom"] as Float
            boostSlider.valueTo = originalBoostSliderProperties["valueTo"] as Float
            boostSlider.stepSize = originalBoostSliderProperties["stepSize"] as Float
            boostSlider.thumbRadius = originalBoostSliderProperties["thumbRadius"] as Int
            boostSlider.thumbHeight = originalBoostSliderProperties["thumbHeight"] as Int
            boostSlider.thumbWidth = originalBoostSliderProperties["thumbWidth"] as Int
            boostSlider.thumbTintList = originalBoostSliderProperties["thumbTintList"] as ColorStateList
            boostSlider.trackHeight = originalBoostSliderProperties["trackHeight"] as Int
            boostSlider.trackInsideCornerSize = originalBoostSliderProperties["trackInsideCornerSize"] as Int
            boostSlider.isTickVisible = originalBoostSliderProperties["isTickVisible"] as Boolean
        }
    }

    private fun applyBoost(level: Int) {
        boostTextView.text = "$level%"
        boostTextView.setTextColor(if (level > 50) "#F92672".toColorInt() else Color.GRAY)
        audioController.applyBoost(level)
    }

    private fun applyVolume(level: Int) {
        volumeTextView.text = "$level%"
        audioController.applyVolume(level)
    }

    private fun updateOutputDeviceInfoUI() {
        val info = audioController.getOutputDeviceInfo()
        if (info != null) {
            outputDeviceTextView.text = info
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)

        val prefs = getSharedPreferences("BoostXPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("boost_value", boostSlider.value)
            putFloat("volume_value", volumeSlider.value)
            putBoolean("gradual_boost", gradualBoostSwitch.isChecked)
            apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioController.release()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacksAndMessages(null)
    }
}
