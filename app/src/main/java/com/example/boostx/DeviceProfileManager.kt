package com.example.boostx

import android.content.Context
import android.media.AudioDeviceInfo

class DeviceProfileManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("BoostXDeviceProfiles", Context.MODE_PRIVATE)

    /** Every output device gets its own tuning — headphones and speakers want different curves. */
    data class Profile(
        val boostLevel: Float,
        val volumeLevel: Int,
        val clarity: Int,
        val depth: Int,
        val preset: AudioController.Preset,
        val enhance: Boolean
    )

    fun profileKey(deviceType: Int, productName: CharSequence?): String {
        val typeLabel = when (deviceType) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            else -> "other"
        }
        val name = (productName ?: "unknown").toString().replace(" ", "_").lowercase()
        return "${typeLabel}_${name}"
    }

    fun save(key: String, profile: Profile) {
        prefs.edit().apply {
            putFloat("${key}_boost", profile.boostLevel)
            putInt("${key}_volume", profile.volumeLevel)
            putInt("${key}_clarity", profile.clarity)
            putInt("${key}_depth", profile.depth)
            putString("${key}_preset", profile.preset.key)
            putBoolean("${key}_enhance", profile.enhance)
            apply()
        }
    }

    fun load(key: String): Profile? {
        if (!has(key)) return null
        return Profile(
            // Profiles saved by older builds stored boost as an Int on the 0–100 scale.
            boostLevel = legacySafeBoost(key),
            volumeLevel = prefs.getInt("${key}_volume", 100),
            clarity = prefs.getInt("${key}_clarity", 0),
            depth = prefs.getInt("${key}_depth", 0),
            preset = AudioController.Preset.fromKey(prefs.getString("${key}_preset", null)),
            enhance = prefs.getBoolean("${key}_enhance", false)
        )
    }

    fun has(key: String): Boolean = prefs.contains("${key}_boost")

    private fun legacySafeBoost(key: String): Float = try {
        prefs.getFloat("${key}_boost", 0f)
    } catch (_: ClassCastException) {
        // 0–100 scale rescaled onto the new 0–10 ceiling.
        prefs.getInt("${key}_boost", 0) / 10f
    }.coerceIn(0f, AudioController.MAX_BOOST_PERCENT)

    fun delete(key: String) {
        prefs.edit().apply {
            remove("${key}_boost")
            remove("${key}_volume")
            remove("${key}_clarity")
            remove("${key}_depth")
            remove("${key}_preset")
            remove("${key}_enhance")
            apply()
        }
    }
}
