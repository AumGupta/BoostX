package com.example.boostx

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.view.KeyEvent

class AudioController(private val context: Context) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var loudnessEnhancer: LoudnessEnhancer? = null
    var audioSessionID: Int = 0
        private set

    private var lastDeviceId: Int? = null
    var isBoostEnabled = true

    init {
        loudnessEnhancer = LoudnessEnhancer(0)
        audioSessionID = audioManager.generateAudioSessionId()
    }

    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun setInitialVolume() {
        val maxVolume = getMaxVolume()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
    }

    fun applyBoost(level: Int) {
        loudnessEnhancer?.setTargetGain(level * 25)
        loudnessEnhancer?.enabled = true
        if (isBoostEnabled) restartAudioPlayback()
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

    fun getOutputDeviceInfo(): String? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val activeDevice = devices.firstOrNull { isActiveOutputDevice(it) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        if (activeDevice == null) {
            if (lastDeviceId != null) {
                lastDeviceId = null
                return "No Active Output Device Detected"
            }
            return null
        }

        if (activeDevice.id == lastDeviceId) return null
        lastDeviceId = activeDevice.id

        val sampleRates = activeDevice.sampleRates.joinToString()
        val deviceType = activeDevice.type
        return "Device Name:\t\t\t\t${activeDevice.productName ?: "N/A"}\n" +
                "Device Type:\t\t\t\t${getDeviceType(deviceType)} (${deviceType})\n" +
                "Device ID:\t\t\t\t\t\t${activeDevice.id}\n\n" +
                "Channels:\t\t\t\t\t\t\t\t${activeDevice.channelCounts.joinToString().ifEmpty { "N/A" }}\n" +
                "Encodings:\t\t\t\t\t\t${getEncodingFormat(activeDevice.encodings).ifEmpty { "N/A" }}\n\n" +
                "Sample Rates: ${if (sampleRates.isEmpty()) "\tN/A" else "\n" + sampleRates + "Hz"}\n"
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

    fun release() {
        loudnessEnhancer?.release()
    }
}
