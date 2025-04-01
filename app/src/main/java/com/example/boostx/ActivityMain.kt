package com.example.boostx

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
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
import android.view.KeyEvent
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

    private lateinit var audioManager: AudioManager
    private var audioSessionID = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastDeviceId: Int? = null
    private var isBoostEnabled = true

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateOutputDeviceInfo()
            handler.postDelayed(this, 1800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_BoostX) // Apply dark theme
        setContentView(R.layout.activity_main)


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


        loudnessEnhancer = LoudnessEnhancer(0)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioSessionID = audioManager.generateAudioSessionId()

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        volumeSlider.value = 100f

        boostTextView = findViewById(R.id.boostLevel)
        volumeTextView = findViewById(R.id.volumeLevel)
        outputDeviceTextView = findViewById(R.id.outputDeviceText)

        boostSlider.addOnChangeListener { _, value, _ -> applyBoost(value.toInt()) }
        volumeSlider.addOnChangeListener { _, value, _ -> applyVolume(value.toInt(), maxVolume) }

        findViewById<TextView>(R.id.infoIcon).setOnClickListener {
            showAppInfo()
        }

    }

    private fun showAppInfo(){
        val infoDialog = MaterialAlertDialogBuilder(this, R.style.CustomDialogTheme)

        val title = SpannableString("App Info")
        title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        infoDialog.setTitle(title)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName

        val infoBuilder = SpannableStringBuilder()

        infoBuilder.append("Version:\t\t\t\t$versionName\n")
        infoBuilder.append("API Level:\t${Build.VERSION.SDK_INT}\n")

        val devLabel = "Developer:\t"
        val devLink = SpannableString("Om Gupta")
        devLink.setSpan(URLSpan("https://github.com/AumGupta"), 0, devLink.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        infoBuilder.append(devLabel)
        infoBuilder.append(devLink)
        infoBuilder.append("\n")

        val sourceLabel = "Source:\t\t\t\t\t"
        val sourceLink = SpannableString("GitHub")
        sourceLink.setSpan(URLSpan("https://github.com/AumGupta/BoostX"), 0, sourceLink.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        infoBuilder.append(sourceLabel)
        infoBuilder.append(sourceLink)
        infoBuilder.append("\n\n")

        val noteText = SpannableString(
            "Session ID:\t\t$audioSessionID\n" +
                    "Package:\t\t\t\t\t\t$packageName\n\n" +
                    "Warning: Excessive boost may distort audio or harm speakers."
        )
        noteText.setSpan(ForegroundColorSpan(Color.GRAY), 0, noteText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        noteText.setSpan(RelativeSizeSpan(0.85f), 0, noteText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        infoBuilder.append(noteText)

        // Creating TextView for Dialog
        val textView = TextView(this)
        textView.text = infoBuilder
        textView.setBackgroundColor("#202020".toColorInt())
        textView.setTextColor(Color.WHITE) // Ensuring text is visible on dark background
        textView.textSize = 16f
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.setPadding(48, 48, 48, 48)

        val spacer = View(this)
        val spacerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, // Match width
            50 // Height of spacer in pixels (adjust as needed)
        )
        spacer.layoutParams = spacerParams

        val linearLayout = LinearLayout(this)
        linearLayout.orientation = LinearLayout.VERTICAL
        linearLayout.setBackgroundColor("#CCFF00".toColorInt()) // Neon background
        linearLayout.addView(spacer) // Add Spacer on Top
        linearLayout.addView(textView) // Add TextView Below

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
    private fun restartAudioPlayback() {
        isBoostEnabled = false
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }


    private fun applyBoost(level: Int) {
        boostTextView.text = "$level%"
        boostTextView.setTextColor(if (level > 50) "#F92672".toColorInt() else Color.GRAY)

        loudnessEnhancer?.setTargetGain(level * 25)
        loudnessEnhancer?.enabled = true

        if (isBoostEnabled) restartAudioPlayback()
    }

    private fun applyVolume(level: Int, maxVolume: Int) {
        volumeTextView.text = "$level%"
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
            ((level.toFloat() / 100) * maxVolume).toInt(),
            0)
    }

    private fun updateOutputDeviceInfo() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Prioritize Bluetooth and Wired devices first, then fallback to Speaker
        val activeDevice = devices.firstOrNull { isActiveOutputDevice(it) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        // If no active device found
        if (activeDevice == null) {
            if (lastDeviceId != null) { // Only update UI if previously there was a device
                lastDeviceId = null
                runOnUiThread {
                    outputDeviceTextView.text = "No Active Output Device Detected"
                }
            }
            return
        }

        // Avoid unnecessary UI updates if the same device is still active
        if (activeDevice.id == lastDeviceId) return
        lastDeviceId = activeDevice.id

        val sampleRates = activeDevice.sampleRates.joinToString()
        val deviceType = activeDevice.type
        val info = "Device Name:\t\t\t\t${activeDevice.productName ?: "N/A"}\n" +
                "Device Type:\t\t\t\t${getDeviceType(deviceType)} (${deviceType})\n" +
                "Device ID:\t\t\t\t\t\t${activeDevice.id}\n\n"+
                "Channels:\t\t\t\t\t\t\t\t${activeDevice.channelCounts.joinToString().ifEmpty { "N/A" }}\n" +
                "Encodings:\t\t\t\t\t\t${getEncodingFormat(activeDevice.encodings).ifEmpty { "N/A" }}\n\n"+
                "Sample Rates: ${if (sampleRates.isEmpty()) "\tN/A" else "\n"+sampleRates+"Hz"}\n"

        runOnUiThread {
            outputDeviceTextView.text = info
        }
    }


    private fun isActiveOutputDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> audioManager.isBluetoothA2dpOn
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> true
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> audioManager.isSpeakerphoneOn
            else -> false
        }
    }

    private fun getDeviceType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI Output"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Device Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            else -> "Unknown Device"
        }
    }

    private fun getEncodingFormat(formats: IntArray): String {
        return formats.joinToString { encodingMap[it] ?: "Unknown Format" }
    }

    private val encodingMap = mapOf(
        AudioFormat.ENCODING_PCM_16BIT to "PCM 16-bit",
        AudioFormat.ENCODING_PCM_8BIT to "PCM 8-bit",
        AudioFormat.ENCODING_PCM_FLOAT to "PCM Float",
        AudioFormat.ENCODING_AC3 to "Dolby AC3",
        AudioFormat.ENCODING_E_AC3 to "Dolby Digital+",
        AudioFormat.ENCODING_DTS to "DTS",
        AudioFormat.ENCODING_DTS_HD to "DTS-HD",
        AudioFormat.ENCODING_AAC_ELD to "AAC ELD",
        AudioFormat.ENCODING_AAC_HE_V1 to "AAC HE v1",
        AudioFormat.ENCODING_AAC_HE_V2 to "AAC HE v2"
    )

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable) // Start updates when app is active
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }
    override fun onDestroy() {
        super.onDestroy()
        loudnessEnhancer?.release()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacksAndMessages(null)
    }
}