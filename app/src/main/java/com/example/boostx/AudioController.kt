package com.example.boostx

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

class AudioController private constructor(private val context: Context) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null
    
    var audioSessionID: Int = 0
        private set

    private var lastDeviceId: Int? = null
    private var hasRestarted = false
    private val handler = Handler(Looper.getMainLooper())

    private var currentBoostLevel: Int = 0

    companion object {
        @Volatile
        private var instance: AudioController? = null

        fun getInstance(context: Context): AudioController {
            return instance ?: synchronized(this) {
                instance ?: AudioController(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        setupEffects()
        audioSessionID = audioManager.generateAudioSessionId()
    }

    private fun setupEffects() {
        try {
            loudnessEnhancer?.release()
            // Priority 0, Session 0 (Global)
            loudnessEnhancer = LoudnessEnhancer(0)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.release()
                // DynamicsProcessing is often more reliable on Android 9+ / MediaTek devices
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    false, 0,
                    false, 0,
                    false, 0,
                    true
                ).build()
                dynamicsProcessing = DynamicsProcessing(0, 0, config)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            visualizer?.release()
            // Visualizer(0) trick to keep global session active on some devices
            visualizer = Visualizer(0)
            // Adding a listener (even empty) can help keep the session active on some devices
            visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, s: Int) {}
                override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, s: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            visualizer?.enabled = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun setInitialVolume() {
        val maxVolume = getMaxVolume()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
    }

    fun applyBoost(level: Int) {
        currentBoostLevel = level
        try {
            val gainMB = level * 30

            // Ensure effects are initialized
            if (loudnessEnhancer == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || dynamicsProcessing == null)) {
                setupEffects()
            }

            // Apply LoudnessEnhancer boost
            loudnessEnhancer?.apply {
                enabled = false
                setTargetGain(gainMB)
                enabled = level > 0
            }

            // Apply DynamicsProcessing boost (more robust on newer devices)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    dynamicsProcessing?.apply {
                        val gainDB = level / 5f // 0 to 20 dB boost
                        val limiter = getLimiterByChannelIndex(0)
                        limiter.isEnabled = level > 0
                        limiter.postGain = gainDB
                        limiter.attackTime = 1f
                        limiter.releaseTime = 60f
                        limiter.ratio = 10f
                        limiter.threshold = -1f
                        
                        setLimiterAllChannelsTo(limiter)
                        enabled = level > 0
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Aggressive toggle for visualizer to "wake up" the session
            // Especially needed for Ikko/MediaTek/Android 14+ devices
            try {
                visualizer?.enabled = false
                if (level > 0) {
                    visualizer?.enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (level > 0 && !hasRestarted) {
                restartAudioPlayback()
                hasRestarted = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun applyVolume(level: Int) {
        val maxVolume = getMaxVolume()
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            ((level.toFloat() / 100) * maxVolume).toInt(),
            0
        )
    }

    fun restartAudioPlayback() {
        val pauseIntent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        audioManager.dispatchMediaKeyEvent(pauseIntent)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        
        handler.postDelayed({
            val playIntent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            audioManager.dispatchMediaKeyEvent(playIntent)
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }, 100)
    }

    fun getOutputDeviceInfo(): String? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val activeDevice = devices.firstOrNull { isActiveOutputDevice(it) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        if (activeDevice == null) {
            if (lastDeviceId != null) {
                lastDeviceId = null
                return context.getString(R.string.no_device_detected)
            }
            return null
        }

        if (activeDevice.id == lastDeviceId) return null
        lastDeviceId = activeDevice.id

        // Re-initialize effects on device change to ensure they attach to the new output path
        setupEffects()
        hasRestarted = false // Allow one-time restart for the new device
        applyBoost(currentBoostLevel)

        val sampleRates = activeDevice.sampleRates.joinToString()
        val deviceType = activeDevice.type
        return "${context.getString(R.string.device_name_label)}\t\t\t\t${activeDevice.productName ?: "N/A"}\n" +
                "${context.getString(R.string.device_type_label)}\t\t\t\t${getDeviceType(deviceType)} (${deviceType})\n" +
                "${context.getString(R.string.device_id_label)}\t\t\t\t\t\t${activeDevice.id}\n\n" +
                "${context.getString(R.string.channels_label)}\t\t\t\t\t\t\t\t${activeDevice.channelCounts.joinToString().ifEmpty { "N/A" }}\n" +
                "${context.getString(R.string.encodings_label)}\t\t\t\t\t\t${getEncodingFormat(activeDevice.encodings).ifEmpty { "N/A" }}\n\n" +
                "${context.getString(R.string.sample_rates_label)} ${if (sampleRates.isEmpty()) "\tN/A" else "\n" + sampleRates + "Hz"}\n"
    }

    private fun isActiveOutputDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> audioManager.isBluetoothA2dpOn
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> true
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> audioManager.isSpeakerphoneOn
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }

    private fun getDeviceType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> context.getString(R.string.device_bluetooth)
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.device_wired_headphones)
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(R.string.device_usb_audio)
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> context.getString(R.string.device_hdmi)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.device_speaker)
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.device_earpiece)
            else -> context.getString(R.string.device_unknown)
        }
    }

    private fun getEncodingFormat(formats: IntArray): String {
        return formats.joinToString { encodingMap[it] ?: context.getString(R.string.format_unknown) }
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

    fun release() {
        loudnessEnhancer?.release()
        dynamicsProcessing?.release()
        visualizer?.release()
        loudnessEnhancer = null
        dynamicsProcessing = null
        visualizer = null
        instance = null
    }
}
