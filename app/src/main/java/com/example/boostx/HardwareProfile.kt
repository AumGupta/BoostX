package com.example.boostx

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Everything BoostX knows about the silicon and the transducers it is driving.
 *
 * The audio engine asks this class for its ceilings instead of hard-coding one number for
 * every phone: a Galaxy S25 Ultra pushing a pair of wired planars has an entirely different
 * safe headroom than a mid-range handset driving one 0.8 cc speaker, and neither of them
 * has the same headroom when the SoC is thermally throttling.
 *
 * Nothing here ever throws — every platform lookup is optional and falls back to a
 * conservative answer, because a wrong guess about hardware must never cost more than a
 * quieter boost.
 */
class HardwareProfile(private val context: Context) {

    /** Where the mix is physically leaving the device. Drives the gain ceiling. */
    enum class Route {
        SPEAKER, EARPIECE, WIRED, USB, BLUETOOTH, BLUETOOTH_LE, HDMI, LINE, OTHER
    }

    /** How much abuse the analogue path is built to take. */
    enum class Tier { ULTRA, FLAGSHIP, MAINSTREAM }

    /**
     * What the Bluetooth link is carrying. Android does not expose the negotiated A2DP codec
     * to ordinary apps, so this is inferred from the sample rates the link advertises: only
     * LDAC negotiates above 48 kHz, while AAC and SBC both sit at 44.1/48 kHz and cannot be
     * told apart from here. [A2DP_STANDARD] therefore means "AAC or SBC", not "SBC".
     */
    enum class CodecTier { LDAC_HIRES, LDAC, APTX_ADAPTIVE, LE_LC3, HIRES_PCM, A2DP_STANDARD, UNKNOWN }

    companion object {
        /** True-peak ceiling the limiter holds. Nothing may be louder than this, ever. */
        const val LIMITER_CEILING_DB = -1f

        /**
         * Hard cap when no true-peak limiter is in the chain (API < 28, or DynamicsProcessing
         * failed to attach). Without a limiter every dB of make-up gain is a dB of potential
         * clipping, so the legacy path stays at the old conservative headroom.
         */
        const val UNLIMITED_PATH_MAX_DB = 4f

        /** Dolby applies its own make-up gain after us — leave room for it. */
        private const val DOLBY_DERATE = 0.6f

        /**
         * Samsung's "Ultra" and Note bodies: big enclosures, stereo speakers with real
         * excursion budget, and the best DAC/amp Samsung ships that year.
         * SM-N9xx (Note), SM-S9x8 (S22/S23/S24/S25 Ultra), SM-F9xx (Fold).
         */
        private val ULTRA_MODEL = Regex("^SM-(N9\\d{2}|S9\\d8|F9\\d{2})", RegexOption.IGNORE_CASE)

        /**
         * Flagship-class boards. Keyed on the platform code name because [Build.SOC_MODEL]
         * only exists from API 31 and OEMs are inconsistent about what they put in it.
         */
        private val FLAGSHIP_BOARDS = mapOf(
            // Qualcomm
            "lahaina" to "Snapdragon 888",
            "taro" to "Snapdragon 8 Gen 1",
            "waipio" to "Snapdragon 8 Gen 1",
            "kalama" to "Snapdragon 8 Gen 2",
            "pineapple" to "Snapdragon 8 Gen 3",
            "sun" to "Snapdragon 8 Elite",
            "kaanapali" to "Snapdragon 8 Elite Gen 5",
            // Samsung Exynos
            "exynos2100" to "Exynos 2100",
            "s5e9925" to "Exynos 2200",
            "s5e9935" to "Exynos 2300",
            "s5e9945" to "Exynos 2400",
            "s5e9955" to "Exynos 2500",
            // Google Tensor
            "gs101" to "Tensor G1",
            "gs201" to "Tensor G2",
            "zuma" to "Tensor G3",
            "zumapro" to "Tensor G4",
            // MediaTek Dimensity flagships
            "mt6983" to "Dimensity 9000",
            "mt6985" to "Dimensity 9200",
            "mt6989" to "Dimensity 9300",
            "mt6991" to "Dimensity 9400"
        )

        /**
         * Make-up gain the route can absorb, in dB, before the limiter starts doing more
         * harm than good. This is the single number that decides how loud BoostX can get.
         *
         * Pure function on purpose — it is the part worth unit-testing, and it must stay
         * callable without a [Context].
         */
        fun gainCeilingDb(
            route: Route,
            tier: Tier,
            dolbyPresent: Boolean,
            limiterAvailable: Boolean,
            thermalScale: Float = 1f
        ): Float {
            val base = when (route) {
                Route.WIRED, Route.USB -> when (tier) {
                    Tier.ULTRA -> 14f
                    Tier.FLAGSHIP -> 12f
                    Tier.MAINSTREAM -> 10f
                }
                Route.BLUETOOTH, Route.BLUETOOTH_LE -> when (tier) {
                    Tier.ULTRA -> 12f
                    Tier.FLAGSHIP -> 11f
                    Tier.MAINSTREAM -> 9f
                }
                // Micro-speakers fail mechanically long before they fail audibly.
                Route.SPEAKER -> when (tier) {
                    Tier.ULTRA -> 7f
                    Tier.FLAGSHIP -> 6f
                    Tier.MAINSTREAM -> 4.5f
                }
                // Held against a head — loudness here is a hearing-safety question.
                Route.EARPIECE -> when (tier) {
                    Tier.ULTRA -> 4f
                    else -> 3f
                }
                // Feeding somebody else's amplifier; stay polite about what we hand it.
                Route.HDMI, Route.LINE -> when (tier) {
                    Tier.ULTRA -> 8f
                    Tier.FLAGSHIP -> 7f
                    Tier.MAINSTREAM -> 6f
                }
                Route.OTHER -> when (tier) {
                    Tier.ULTRA -> 6f
                    Tier.FLAGSHIP -> 5f
                    Tier.MAINSTREAM -> 4f
                }
            }

            val derated = if (dolbyPresent) base * DOLBY_DERATE else base
            val limited = if (limiterAvailable) derated else minOf(derated, UNLIMITED_PATH_MAX_DB)
            return (limited * thermalScale.coerceIn(0f, 1f)).coerceAtLeast(0f)
        }

        /**
         * How much of the ceiling survives the current thermal state. A hot SoC means the
         * amplifier is already near its limit and the DSP is being clocked down; leaning on
         * it harder buys distortion and a hotter phone, not loudness.
         *
         * Values are [PowerManager.THERMAL_STATUS_NONE] … [PowerManager.THERMAL_STATUS_SHUTDOWN].
         */
        fun thermalScaleFor(status: Int): Float = when {
            status <= PowerManager.THERMAL_STATUS_LIGHT -> 1f
            status == PowerManager.THERMAL_STATUS_MODERATE -> 0.8f
            status == PowerManager.THERMAL_STATUS_SEVERE -> 0.6f
            status == PowerManager.THERMAL_STATUS_CRITICAL -> 0.4f
            else -> 0.25f
        }

        /**
         * Lossy codecs spend their smallest bit allocation on the top octave, so lifting air
         * there amplifies quantisation noise and pre-echo more than it reveals detail. This
         * scales back the two highest bands on a standard A2DP link.
         */
        fun airScaleFor(codec: CodecTier): Float = when (codec) {
            CodecTier.A2DP_STANDARD -> 0.4f
            CodecTier.LDAC -> 0.85f
            else -> 1f
        }

        /** Maps a framework [AudioDeviceInfo] type onto the route whose ceiling applies. */
        fun routeForDeviceType(type: Int): Route = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> Route.SPEAKER

            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Route.EARPIECE

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> Route.WIRED

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> Route.USB

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID -> Route.BLUETOOTH

            // LE Audio — the S2x Ultra line and every Auracast earbud lands here.
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> Route.BLUETOOTH_LE

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> Route.HDMI

            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK -> Route.LINE

            else -> Route.OTHER
        }
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // ── Silicon ───────────────────────────────────────────────────────────────

    /** Platform code name the OEM built against, lower-cased. */
    private val board: String = Build.BOARD.lowercase()
    private val hardware: String = Build.HARDWARE.lowercase()

    val isSamsung: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Matched flagship board, or null on anything we do not have a name for. */
    private val knownBoard: Map.Entry<String, String>? =
        FLAGSHIP_BOARDS.entries.firstOrNull { board.contains(it.key) }

    val isQualcomm: Boolean =
        hardware.contains("qcom") || knownBoard?.value?.startsWith("Snapdragon") == true

    /**
     * Best available name for the chip. [Build.SOC_MODEL] is the authoritative source but
     * only exists from API 31, and plenty of OEMs leave it as the internal part number —
     * so a recognised board name wins over it.
     */
    val socLabel: String = knownBoard?.value
        ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
                ?: Build.BOARD
        } else {
            Build.BOARD
        }

    /**
     * Ultra-class bodies get the highest ceilings. A recognised flagship SoC is the floor
     * for that claim — a big chassis with a mid-range amplifier is still mid-range.
     */
    val tier: Tier = when {
        knownBoard != null && ULTRA_MODEL.containsMatchIn(Build.MODEL) -> Tier.ULTRA
        knownBoard != null && Build.MODEL.contains("Ultra", ignoreCase = true) -> Tier.ULTRA
        knownBoard != null -> Tier.FLAGSHIP
        else -> Tier.MAINSTREAM
    }

    /** Snapdragon Sound / aptX Adaptive needs an 888 or newer paired with Android 12+. */
    val supportsSnapdragonSound: Boolean =
        isQualcomm && knownBoard != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** DynamicsProcessing — the multiband compressor and the true-peak limiter live here. */
    val hasAdvancedEngine: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    val isDolbyPresent: Boolean by lazy {
        isSamsung && try {
            context.packageManager.getPackageInfo("com.samsung.android.dolby", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Audio HAL capabilities ────────────────────────────────────────────────

    val hasLowLatencyAudio: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)

    val hasProAudio: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)

    /** Native mixer rate in Hz — anything else is resampled by the HAL before it reaches us. */
    val nativeSampleRate: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

    /** Native burst size in frames. Small burst = short effect latency. */
    val nativeFramesPerBurst: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0

    // ── Spatial audio ─────────────────────────────────────────────────────────

    /**
     * True when the platform spatializer is actively widening the mix for the current
     * output. Our own Virtualizer must then get out of the way — two widening stages in
     * series smear the centre image instead of doubling the effect.
     */
    val isSpatializerActive: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                val s = audioManager.spatializer
                s.isAvailable && s.isEnabled
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

    // ── Thermals ──────────────────────────────────────────────────────────────

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** Latest known throttling level; [PowerManager.THERMAL_STATUS_NONE] until told otherwise. */
    var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
        private set

    val thermalScale: Float get() = thermalScaleFor(thermalStatus)

    /**
     * Starts tracking SoC throttling. [onChanged] fires on the main thread whenever the
     * level moves, so the engine can re-stage its gain against the new ceiling.
     */
    fun startThermalMonitoring(onChanged: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (thermalListener != null) return
        try {
            thermalStatus = powerManager.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                onChanged()
            }
            powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            thermalListener = listener
        } catch (_: Exception) {
            // Some ROMs refuse the listener; the static ceiling still applies.
        }
    }

    fun stopThermalMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        thermalListener?.let {
            try {
                powerManager.removeThermalStatusListener(it)
            } catch (_: Exception) {
            }
        }
        thermalListener = null
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    /**
     * The device the media mix is actually being played on.
     *
     * There is no public API that answers this directly — [AudioManager.isSpeakerphoneOn]
     * describes *call* routing and is false during ordinary playback, which is why simply
     * asking it gets the built-in speaker wrong whenever a headset is plugged in.
     *
     * So we reproduce the framework's own routing order instead: of everything connected,
     * media goes to the highest-priority device. A plugged-in USB DAC beats a wired headset
     * beats a connected pair of earbuds beats the speaker.
     */
    fun activeOutputDevice(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.isEmpty()) return null
        return outputs
            .filter { routingPriority(it) > 0 }
            .maxByOrNull { routingPriority(it) }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.first()
    }

    /**
     * Higher wins. Bluetooth sits below wired because a connected-but-idle headset is
     * common, and 0 means "connected, but never the media sink" (the earpiece, and the
     * telephony-only SCO link).
     */
    private fun routingPriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 70

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 60

        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 50

        AudioDeviceInfo.TYPE_HEARING_AID -> 40

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> 30

        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK -> 20

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 10

        else -> 0
    }

    /**
     * Highest sample rate and channel count the device will accept, read from the
     * framework's own profile list where available. This is the real capability of the
     * link (LDAC at 96 kHz, USB DAC at 384 kHz, …), not what the mixer happens to run at.
     */
    fun linkCapability(device: AudioDeviceInfo): String {
        val rates = mutableSetOf<Int>()
        var channels = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                for (profile in device.audioProfiles) {
                    profile.sampleRates.forEach { rates.add(it) }
                    profile.channelMasks.forEach { mask ->
                        channels = maxOf(channels, Integer.bitCount(mask))
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (rates.isEmpty()) device.sampleRates.forEach { rates.add(it) }
        if (channels == 0) channels = device.channelCounts.maxOrNull() ?: 0

        val topRate = rates.maxOrNull() ?: return ""
        val khz = topRate / 1000f
        val rateText = if (khz % 1f == 0f) "${khz.toInt()} kHz" else String.format("%.1f kHz", khz)
        return if (channels > 0) "$rateText · ${channels}ch" else rateText
    }

    /**
     * Best-effort read of what the link is carrying.
     *
     * The negotiated A2DP codec is not public API, so this leans on the one thing that *is*
     * observable: LDAC is the only A2DP codec that runs above 48 kHz, so a link advertising
     * 88.2/96 kHz is LDAC. AAC and SBC both sit at 44.1/48 kHz and are indistinguishable
     * from here — reported together rather than guessed at.
     */
    fun codecTier(device: AudioDeviceInfo): CodecTier {
        val route = routeForDeviceType(device.type)
        if (route == Route.BLUETOOTH_LE) return CodecTier.LE_LC3

        val topRate = maxSampleRate(device)
        val hasFloat = device.encodings.contains(android.media.AudioFormat.ENCODING_PCM_FLOAT)

        if (route == Route.BLUETOOTH) {
            return when {
                topRate >= 88200 -> CodecTier.LDAC_HIRES
                hasFloat && supportsSnapdragonSound -> CodecTier.APTX_ADAPTIVE
                hasFloat -> CodecTier.LDAC
                topRate > 0 -> CodecTier.A2DP_STANDARD
                else -> CodecTier.UNKNOWN
            }
        }

        return when {
            hasFloat -> CodecTier.HIRES_PCM
            else -> CodecTier.UNKNOWN
        }
    }

    private fun maxSampleRate(device: AudioDeviceInfo): Int {
        var top = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                for (profile in device.audioProfiles) {
                    profile.sampleRates.forEach { if (it > top) top = it }
                }
            } catch (_: Exception) {
            }
        }
        if (top == 0) device.sampleRates.forEach { if (it > top) top = it }
        return top
    }

    /**
     * Time represented by one output burst, in milliseconds. This is the local mixer's
     * contribution only — the Bluetooth link adds far more and is not measurable from here,
     * which is why the label says so rather than quoting a total the app cannot know.
     */
    val outputBurstLatencyMs: Float
        get() = if (nativeSampleRate > 0 && nativeFramesPerBurst > 0) {
            nativeFramesPerBurst * 1000f / nativeSampleRate
        } else {
            0f
        }

    /** True when more than one Bluetooth sink is connected — the buds' multipoint pairing. */
    fun hasMultipleBluetoothOutputs(): Boolean = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).count {
            val r = routeForDeviceType(it.type)
            r == Route.BLUETOOTH || r == Route.BLUETOOTH_LE
        } > 1
    } catch (_: Exception) {
        false
    }

    /**
     * The hardware facts worth showing, as separate parts.
     *
     * Returned unjoined because the caller has to isolate each one for bidirectional text
     * before assembling them — a Latin part and an Arabic part concatenated raw get
     * reordered against each other.
     */
    fun summaryParts(): List<String> {
        val parts = mutableListOf<String>()
        parts.add(Build.MODEL)
        parts.add(socLabel)
        if (tier == Tier.ULTRA) parts.add("Ultra-class")
        if (supportsSnapdragonSound) parts.add("Snapdragon Sound")
        if (isDolbyPresent) parts.add(context.getString(R.string.dolby_active))
        if (isSpatializerActive) parts.add(context.getString(R.string.spatializer_active))
        if (hasProAudio) parts.add("Pro Audio") else if (hasLowLatencyAudio) parts.add("Low Latency")
        if (nativeSampleRate > 0) parts.add("${nativeSampleRate / 1000} kHz mixer")
        return parts
    }
}
