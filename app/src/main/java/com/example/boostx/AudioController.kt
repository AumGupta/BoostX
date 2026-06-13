package com.example.boostx

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

class AudioController(private val context: Context) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var loudnessEnhancer: LoudnessEnhancer? = null
    var audioSessionID: Int = 0
        private set

    private var lastDeviceId: Int? = null
    private var hasRestarted = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            loudnessEnhancer = LoudnessEnhancer(0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioSessionID = audioManager.generateAudioSessionId()
    }

    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun setInitialVolume() {
        val maxVolume = getMaxVolume()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
    }

    fun applyBoost(level: Int) {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.setTargetGain(level * 30)
            loudnessEnhancer?.enabled = level > 0

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
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> audioManager.isBluetoothA2dpOn
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> true
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> audioManager.isSpeakerphoneOn
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
    }
}
