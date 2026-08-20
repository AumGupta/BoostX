package com.example.boostx

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AudioController(private val context: Context) {

    companion object {
        /**
         * Boost ceiling expressed as a percentage of the full scale.
         * The slider never goes past this — everything above it is loudness for
         * loudness' sake and is where clipping and speaker damage start.
         */
        const val MAX_BOOST_PERCENT = 10f

        /** Gain that 100 % of the scale would represent. 10 % of it = 3 dB of clean gain. */
        private const val FULL_SCALE_GAIN_DB = 30f

        /** True-peak ceiling kept by the limiter so the boost can never clip the mix. */
        private const val PEAK_CEILING_DB = -1f

        private const val CHANNELS = 2
        private const val MBC_BANDS = 3

        /** Upper cutoff of each pre-EQ band, ascending (sub · low-mid · mid · presence · detail · air). */
        private val EQ_CUTOFFS = floatArrayOf(120f, 320f, 1000f, 3200f, 8000f, 20000f)
        private val MBC_CUTOFFS = floatArrayOf(200f, 2000f, 20000f)

        /** Balance at ±100 (hard left/right) attenuates the far channel by this many dB. */
        private const val BALANCE_MAX_CUT_DB = 30f

        /** Noise gate tuned for spoken content — low enough to spare quiet room tone, not hiss. */
        private const val NOISE_GATE_THRESHOLD_DB = -50f
        private const val NOISE_GATE_EXPANDER_RATIO = 2f

        /** Auto-loudness target and bounds — see [onWaveformCaptured]. */
        private const val TARGET_LOUDNESS_DBFS = -20f
        private const val SILENCE_FLOOR_DBFS = -50f
        private const val NORMALIZE_RAW_TRIM_LIMIT_DB = 10f
        private const val NORMALIZE_MAX_CUT_DB = 12f
        private const val NORMALIZE_SMOOTHING = 0.06f
        private const val NORMALIZE_REPUSH_THRESHOLD_DB = 0.2f

        private const val CALL_POLL_INTERVAL_MS = 2000L
    }

    /**
     * Voicing presets. `curve` is the base pre-EQ shape in dB (one entry per [EQ_CUTOFFS] band),
     * `density` drives the multiband compressor (perceived loudness without peaks) and
     * `space` drives the virtualizer (stereo depth / soundstage).
     */
    enum class Preset(
        val key: String,
        val labelRes: Int,
        val curve: FloatArray,
        val density: Float,
        val space: Float
    ) {
        BALANCED("balanced", R.string.preset_balanced,
            floatArrayOf(0.5f, -0.5f, 0f, 0.5f, 0.5f, 0.5f), 0.25f, 0.30f),
        VOCAL("vocal", R.string.preset_vocal,
            floatArrayOf(-1.0f, -1.5f, 0.5f, 2.0f, 1.5f, 0.5f), 0.35f, 0.15f),
        BASS("bass", R.string.preset_bass,
            floatArrayOf(3.0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f), 0.30f, 0.35f),
        MOVIE("movie", R.string.preset_movie,
            floatArrayOf(2.0f, -1.0f, 0f, 1.0f, 1.5f, 1.5f), 0.45f, 0.80f),
        NIGHT("night", R.string.preset_night,
            floatArrayOf(-0.5f, -1.0f, 0.5f, 1.5f, 1.0f, 0f), 0.90f, 0.20f);

        companion object {
            fun fromKey(key: String?): Preset =
                values().firstOrNull { it.key == key } ?: BALANCED
        }
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null

    // Legacy chain — used on API < 28 where DynamicsProcessing does not exist.
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    var audioSessionID: Int = 0
        private set

    private var lastDeviceId: Int? = null
    private var hasRestarted = false
    private val handler = Handler(Looper.getMainLooper())

    // Tracked per engine — one engine repeatedly succeeding must not reset the
    // other's failure count, or a persistently-failing engine can retry forever
    // without ever tripping effectsFailed (see onSetupSucceeded/scheduleRetryOrFallback).
    private var loudnessRetryAttempt = 0
    private var dpRetryAttempt = 0

    private val callStatePoller = object : Runnable {
        override fun run() {
            checkCallState()
            handler.postDelayed(this, CALL_POLL_INTERVAL_MS)
        }
    }

    // ── Live state ────────────────────────────────────────────────────────────
    var boostLevel: Float = 0f
        private set
    var clarity: Int = 0
        private set
    var depth: Int = 0
        private set
    var preset: Preset = Preset.BALANCED
        private set
    var enhanceEnabled: Boolean = false
        private set

    /** -100 (hard left) .. 0 (center) .. 100 (hard right). Per-channel gain trim, DynamicsProcessing only. */
    var balance: Int = 0
        private set
    var noiseGateEnabled: Boolean = false
        private set
    var normalizeEnabled: Boolean = false
        private set
    var callBoostEnabled: Boolean = false
        private set

    /** True while the A/B "hold to compare" control is pressed — mutes every effect without touching state. */
    var bypassed: Boolean = false
        private set

    /** True once automatic retries are exhausted and the effects engine never attached. */
    var effectsFailed: Boolean = false
        private set

    /** Slow-moving auto-loudness correction, in dB — see [onWaveformCaptured]. */
    private var normalizationTrimDb: Float = 0f
    private var smoothedLoudnessDbfs: Float = TARGET_LOUDNESS_DBFS
    private var wasInCallMode = false

    /** Built-in speaker is a tiny driver — sub-bass and virtualization get scaled back on it. */
    private var onSpeaker = false
    private var onHeadphones = false

    val supportsAdvancedEngine: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    // Samsung S21 / One UI detection
    val isSamsungDevice: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    // Snapdragon 888 detection — board name "lahaina", hardware "qcom"
    // S21 Snapdragon variant uses Qualcomm audio HAL (different from Exynos HAL)
    val isSnapdragon: Boolean = Build.BOARD.lowercase().let { board ->
        board.contains("lahaina") ||          // Snapdragon 888 (S21)
        board.contains("waipio") ||           // Snapdragon 8 Gen 1
        board.contains("kalama") ||           // Snapdragon 8 Gen 3
        board.contains("sun")                 // Snapdragon 8 Elite (S25 / S25 Ultra)
    } || Build.HARDWARE.lowercase().contains("qcom")

    // Human-readable chip generation for the device summary — mirrors the board list above.
    private val snapdragonChipLabel: String get() = Build.BOARD.lowercase().let { board ->
        when {
            board.contains("lahaina") -> "888"
            board.contains("waipio") -> "8 Gen 1"
            board.contains("kalama") -> "8 Gen 3"
            board.contains("sun") -> "8 Elite"
            else -> "DSP"
        }
    }

    // Snapdragon Sound / aptX Adaptive requires Snapdragon 888+
    val supportsSnapdragonSound: Boolean = isSnapdragon && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Dolby Atmos active on this device (Samsung S21 has Dolby)
    val isDolbyPresent: Boolean by lazy {
        isSamsungDevice && try {
            context.packageManager.getPackageInfo("com.samsung.android.dolby", 0)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Max boost on the 0–[MAX_BOOST_PERCENT] scale. Dolby already applies its own
     * make-up gain, so the ceiling drops further when it is in the chain.
     */
    val maxSafeBoostLevel: Float get() = when {
        isDolbyPresent && isSnapdragon -> 7.5f
        isDolbyPresent -> 7f
        else -> MAX_BOOST_PERCENT
    }

    // Callback for when audio output device changes (used by MainActivity for profiles)
    var onDeviceChanged: ((key: String, deviceName: String, isBluetooth: Boolean) -> Unit)? = null
    var onDeviceRemoved: (() -> Unit)? = null

    // Callbacks for the "effects engine unavailable" banner (used by MainActivity)
    var onEffectsFailed: (() -> Unit)? = null
    var onEffectsRecovered: (() -> Unit)? = null

    init {
        audioSessionID = audioManager.generateAudioSessionId()
        setupEffects()
        // Call-state polling only starts once Call Boost is actually enabled — see setCallBoostEnabled.
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Effect setup
    //
    // The whole chain hangs off session 0 (the global output mix) so it applies
    // to every app. On API 28+ DynamicsProcessing carries the tone shaping,
    // the multiband compressor and the peak limiter; older devices fall back to
    // Equalizer + BassBoost. Samsung One UI intercepts LoudnessEnhancer at
    // session 0, which is why DynamicsProcessing is built first there.
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupEffects() {
        releaseEffects()

        if (isSamsungDevice && supportsAdvancedEngine) {
            setupDynamicsProcessing()
            setupLoudnessEnhancer()
        } else {
            setupLoudnessEnhancer()
            if (supportsAdvancedEngine) setupDynamicsProcessing() else setupLegacyChain()
        }

        // Virtualizer is the only stereo-depth effect available without root and
        // it exists all the way back to API 9 — keep it on both paths.
        setupVirtualizer()
        attachVisualizer()
    }

    private fun setupLoudnessEnhancer() {
        try {
            loudnessEnhancer = LoudnessEnhancer(0)
            loudnessRetryAttempt = 0
            onSetupSucceeded()
        } catch (e: Exception) {
            scheduleRetryOrFallback(e, isLoudnessEnhancer = true)
        }
    }

    private fun setupDynamicsProcessing() {
        if (!supportsAdvancedEngine) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, EQ_CUTOFFS.size,   // pre-EQ  — tone shaping (clarity / depth)
                true, MBC_BANDS,         // MBC     — density, perceived loudness
                false, 0,                // post-EQ — unused
                true                     // limiter — peak safety
            ).build()
            dynamicsProcessing = DynamicsProcessing(0, 0, config)
            dpRetryAttempt = 0
            onSetupSucceeded()
        } catch (e: Exception) {
            scheduleRetryOrFallback(e, isLoudnessEnhancer = false)
        }
    }

    private fun onSetupSucceeded() {
        if (effectsFailed) {
            effectsFailed = false
            onEffectsRecovered?.invoke()
        }
    }

    private fun setupLegacyChain() {
        try {
            equalizer = Equalizer(0, 0)
        } catch (_: Exception) {}
        try {
            bassBoost = BassBoost(0, 0)
        } catch (_: Exception) {}
    }

    private fun setupVirtualizer() {
        try {
            virtualizer = Virtualizer(0, 0)
        } catch (_: Exception) {}
    }

    private fun scheduleRetryOrFallback(cause: Exception, isLoudnessEnhancer: Boolean) {
        val attempt = if (isLoudnessEnhancer) ++loudnessRetryAttempt else ++dpRetryAttempt
        if (attempt < 3) {
            // Samsung audio daemon may not be ready immediately after boot
            handler.postDelayed({
                setupEffects()
                applyAll()
            }, 600L * attempt)
        } else {
            cause.printStackTrace()
            if (!effectsFailed) {
                effectsFailed = true
                onEffectsFailed?.invoke()
            }
        }
    }

    /** Manual retry entry point for the UI's "Retry" button once [effectsFailed] is true. */
    fun retrySetup() {
        loudnessRetryAttempt = 0
        dpRetryAttempt = 0
        setupEffects()
        applyAll()
    }

    private fun attachVisualizer() {
        try {
            visualizer?.release()
            visualizer = Visualizer(0)
            visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, s: Int) {
                    if (w != null) onWaveformCaptured(w)
                }
                override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, s: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            visualizer?.enabled = false
        } catch (_: Exception) {}
    }

    /**
     * Rough RMS-based loudness estimate from the waveform tap, smoothed into a slow trim so a
     * quiet verse doesn't get chased mid-note. Silence (pauses, gaps between tracks) is ignored
     * so playback doesn't get slammed to full gain the instant a silent section resumes.
     */
    private fun onWaveformCaptured(waveform: ByteArray) {
        if (!normalizeEnabled || waveform.isEmpty()) return

        var sumSquares = 0.0
        for (b in waveform) {
            val centered = (b.toInt() and 0xFF) - 128
            sumSquares += (centered * centered).toDouble()
        }
        val rms = sqrt(sumSquares / waveform.size)
        if (rms < 1.0) return

        val dbfs = (20 * log10(rms / 128.0)).toFloat()
        if (dbfs < SILENCE_FLOOR_DBFS) return

        smoothedLoudnessDbfs += (dbfs - smoothedLoudnessDbfs) * NORMALIZE_SMOOTHING
        val trim = (TARGET_LOUDNESS_DBFS - smoothedLoudnessDbfs)
            .coerceIn(-NORMALIZE_RAW_TRIM_LIMIT_DB, NORMALIZE_RAW_TRIM_LIMIT_DB)
        if (abs(trim - normalizationTrimDb) < NORMALIZE_REPUSH_THRESHOLD_DB) return

        handler.post {
            normalizationTrimDb = trim
            applyLoudnessEnhancer()
            applyDynamicsProcessing(buildEqCurve())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Call detection — no READ_PHONE_STATE needed, MODE_IN_CALL / MODE_IN_COMMUNICATION
    // covers both cellular and VoIP calls (WhatsApp, Meet, ...) alike.
    // ──────────────────────────────────────────────────────────────────────────

    private fun checkCallState() {
        val inCall = try {
            audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }

        // Edge-triggered: max the call volume once when a call starts, then leave the user
        // alone — re-forcing it every poll would fight anyone who turns it back down.
        if (inCall && !wasInCallMode && callBoostEnabled) {
            try {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0
                )
            } catch (_: Exception) {}
        }
        wasInCallMode = inCall
    }

    private fun releaseEffects() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        dynamicsProcessing?.release()
        dynamicsProcessing = null
        equalizer?.release()
        equalizer = null
        bassBoost?.release()
        bassBoost = null
        virtualizer?.release()
        virtualizer = null
        visualizer?.release()
        visualizer = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    fun getMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun setInitialVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, getMaxVolume(), 0)
    }

    fun applyBoost(level: Float) {
        // Builds before the [MAX_BOOST_PERCENT] ceiling stored boost on a 0–100 scale — rescale
        // it once, centrally, so every caller (UI, service restore, boot, quick tile) is covered
        // regardless of whether it happens to pass a legacy-scale value straight through.
        val migrated = if (level > MAX_BOOST_PERCENT) level / 10f else level
        boostLevel = migrated.coerceIn(0f, maxSafeBoostLevel)
        applyAll()
        restartIfNeeded()
    }

    /** One-time pause/play kick so already-playing audio re-attaches to a freshly rebuilt effect chain. */
    private fun restartIfNeeded() {
        if (boostLevel > 0f && !hasRestarted) {
            restartAudioPlayback()
            hasRestarted = true
        }
    }

    /** Clarity 0–100: presence and air lift with a matching cut in the mud region. */
    fun applyClarity(value: Int) {
        clarity = value.coerceIn(0, 100)
        applyAll()
    }

    /** Depth 0–100: sub-bass extension plus stereo-depth virtualization. */
    fun applyDepth(value: Int) {
        depth = value.coerceIn(0, 100)
        applyAll()
    }

    fun applyPreset(newPreset: Preset) {
        preset = newPreset
        applyAll()
    }

    fun setEnhanceEnabled(enabled: Boolean) {
        enhanceEnabled = enabled
        applyAll()
    }

    /** -100 (hard left) .. 100 (hard right). DynamicsProcessing only — no-op on the legacy chain. */
    fun applyBalance(value: Int) {
        balance = value.coerceIn(-100, 100)
        applyAll()
    }

    fun setNoiseGateEnabled(enabled: Boolean) {
        noiseGateEnabled = enabled
        applyAll()
    }

    fun setNormalizeEnabled(enabled: Boolean) {
        normalizeEnabled = enabled
        if (!enabled) normalizationTrimDb = 0f
        applyAll()
    }

    fun setCallBoostEnabled(enabled: Boolean) {
        callBoostEnabled = enabled
        // Always clear first — onStartCommand calls this on every service (re)start, not just
        // on a real toggle, so posting without removing would stack up duplicate poll chains.
        handler.removeCallbacks(callStatePoller)
        if (enabled) {
            handler.post(callStatePoller)
        }
    }

    /** Press-and-hold "A/B compare" — mutes every effect without disturbing any saved parameter. */
    fun setBypassed(enabled: Boolean) {
        if (bypassed == enabled) return
        bypassed = enabled
        applyAll()
    }

    /** Re-applies boost + enhancement to whatever effects currently exist. */
    fun applyAll() {
        try {
            if (loudnessEnhancer == null && dynamicsProcessing == null) setupEffects()

            val gains = buildEqCurve()
            applyLoudnessEnhancer()
            applyDynamicsProcessing(gains)
            applyLegacyChain(gains)
            applyVirtualizer()
            tickVisualizer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tone engine
    // ──────────────────────────────────────────────────────────────────────────

    /** Clean gain the boost slider asks for, in dB (0–3 dB at the 10 % ceiling). */
    private val boostGainDb: Float get() = boostLevel / 100f * FULL_SCALE_GAIN_DB

    /** dB equivalent of [maxSafeBoostLevel] — auto-loudness can use this headroom but never exceed it. */
    private val gainCeilingDb: Float get() = maxSafeBoostLevel / 100f * FULL_SCALE_GAIN_DB

    /**
     * Final pre-EQ curve: preset voicing + clarity tilt + depth extension,
     * scaled back on the built-in speaker where low bass only buys distortion.
     */
    private fun buildEqCurve(): FloatArray {
        if (!enhanceEnabled) return FloatArray(EQ_CUTOFFS.size)

        val c = clarity / 100f
        val d = depth / 100f
        val subScale = if (onSpeaker) 0.35f else 1f

        val base = preset.curve
        return floatArrayOf(
            base[0] * subScale + 5.0f * d * subScale,   // sub        — depth
            base[1] + 0.8f * d - 2.5f * c,              // low-mid    — de-mud for clarity
            base[2] - 0.5f * c,                         // mid        — anchor
            base[3] + 2.0f * c,                         // presence
            base[4] + 3.0f * c,                         // detail
            base[5] + 2.0f * c                          // air
        )
    }

    /** Headroom pulled out of the input so EQ boosts cannot clip before the limiter. */
    private fun headroomFor(gains: FloatArray): Float =
        max(0f, gains.max()) * 0.7f

    private fun applyLoudnessEnhancer() {
        // The multiband compressor does the heavy lifting; LoudnessEnhancer only
        // carries the plain slider gain, capped at 10 % of the full scale.
        // Auto-loudness may only ever add here — trimming a loud track down is the
        // limiter's job on the DynamicsProcessing path, never a negative target gain.
        loudnessEnhancer?.apply {
            try {
                val extraTrim = normalizationTrimDb.coerceAtLeast(0f)
                val totalGainDb = (boostGainDb + extraTrim).coerceIn(0f, gainCeilingDb)
                setTargetGain((totalGainDb * 100).roundToInt())  // dB → mB
                enabled = (boostLevel > 0f || extraTrim > 0.01f) && !bypassed
            } catch (_: Exception) {}
        }
    }

    private fun applyDynamicsProcessing(gains: FloatArray) {
        if (!supportsAdvancedEngine) return
        val dp = dynamicsProcessing ?: return
        try {
            val headroom = headroomFor(gains)
            dp.setInputGainAllChannelsTo(-headroom)

            // Push the whole stage in one shot — band-by-band updates can transiently
            // break the framework's ascending-cutoff rule and get rejected.
            val eq = dp.getPreEqByChannelIndex(0)
            eq.isEnabled = enhanceEnabled
            gains.forEachIndexed { i, gain ->
                val band = eq.getBand(i)
                band.isEnabled = true
                band.cutoffFrequency = EQ_CUTOFFS[i]
                band.gain = gain
                eq.setBand(i, band)
            }
            dp.setPreEqAllChannelsTo(eq)

            applyMbc(dp)

            // Auto-loudness rides on top of the user's boost but never past the safety
            // ceiling; it can always cut a hot track down since a cut can never clip.
            val cappedGainDb = (boostGainDb + normalizationTrimDb)
                .coerceIn(-NORMALIZE_MAX_CUT_DB, gainCeilingDb)

            val basePostGain = cappedGainDb + headroom * 0.5f
            if (balance == 0) {
                // Common case: both channels are identical — one call instead of CHANNELS.
                val limiter = dp.getLimiterByChannelIndex(0)
                limiter.isEnabled = true
                limiter.attackTime = 1f
                limiter.releaseTime = 60f
                limiter.ratio = 10f
                limiter.threshold = PEAK_CEILING_DB
                limiter.postGain = basePostGain
                dp.setLimiterAllChannelsTo(limiter)
            } else {
                for (channel in 0 until CHANNELS) {
                    val limiter = dp.getLimiterByChannelIndex(channel)
                    limiter.isEnabled = true
                    limiter.attackTime = 1f
                    limiter.releaseTime = 60f
                    limiter.ratio = 10f
                    limiter.threshold = PEAK_CEILING_DB
                    // Give back the headroom plus the requested boost — the limiter holds the peak.
                    limiter.postGain = basePostGain + balanceTrimDb(channel)
                    dp.setLimiterByChannelIndex(channel, limiter)
                }
            }

            dp.enabled = (boostLevel > 0f || enhanceEnabled || balance != 0 ||
                abs(normalizationTrimDb) > 0.01f) && !bypassed
        } catch (_: Exception) {}
    }

    /** Per-channel attenuation for the balance control. Channel 0 = left, 1 = right. */
    private fun balanceTrimDb(channel: Int): Float {
        if (balance == 0) return 0f
        val cutDb = abs(balance) / 100f * BALANCE_MAX_CUT_DB
        val isLeftChannel = channel == 0
        val cutThisChannel = if (balance < 0) !isLeftChannel else isLeftChannel
        return if (cutThisChannel) -cutDb else 0f
    }

    /**
     * Multiband compression is what makes quiet detail audible without turning
     * peaks into clipping — the "density" half of clarity, and the whole point
     * of the Night preset.
     */
    private fun applyMbc(dp: DynamicsProcessing) {
        val density = if (enhanceEnabled) preset.density else 0f
        val mbc = dp.getMbcByChannelIndex(0)
        mbc.isEnabled = density > 0f
        for (i in 0 until MBC_BANDS) {
            val band = mbc.getBand(i)
            band.isEnabled = density > 0f
            band.cutoffFrequency = MBC_CUTOFFS[i]
            band.attackTime = if (i == 0) 8f else 4f
            band.releaseTime = if (i == 0) 120f else 80f
            band.ratio = 1f + density * 3f
            band.threshold = -12f - density * 18f
            band.kneeWidth = 6f
            // Off by default (-100 = never chew up reverb tails); the Noise Gate switch
            // raises it for spoken content where background hiss is worth cutting.
            band.noiseGateThreshold = if (noiseGateEnabled) NOISE_GATE_THRESHOLD_DB else -100f
            band.expanderRatio = if (noiseGateEnabled) NOISE_GATE_EXPANDER_RATIO else 1f
            band.preGain = 0f
            band.postGain = density * 2f
            mbc.setBand(i, band)
        }
        dp.setMbcAllChannelsTo(mbc)
    }

    /** API < 28 path: same intent, coarser tools. */
    private fun applyLegacyChain(gains: FloatArray) {
        equalizer?.let { eq ->
            try {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange           // [min, max] in mB
                for (b in 0 until bands) {
                    val centerHz = eq.getCenterFreq(b.toShort()) / 1000f   // mHz → Hz
                    val gainMb = (gainAtFrequency(gains, centerHz) * 100).roundToInt()
                    eq.setBandLevel(
                        b.toShort(),
                        gainMb.coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    )
                }
                eq.enabled = enhanceEnabled && !bypassed
            } catch (_: Exception) {}
        }

        bassBoost?.let { bb ->
            try {
                if (bb.strengthSupported) {
                    val scale = if (onSpeaker) 0.35f else 1f
                    bb.setStrength((depth * 8 * scale).roundToInt().coerceIn(0, 1000).toShort())
                }
                bb.enabled = enhanceEnabled && depth > 0 && !bypassed
            } catch (_: Exception) {}
        }
    }

    /** Nearest-band lookup so the legacy Equalizer follows the same curve. */
    private fun gainAtFrequency(gains: FloatArray, hz: Float): Float {
        val index = EQ_CUTOFFS.indexOfFirst { hz <= it }
        return gains[if (index < 0) gains.lastIndex else index]
    }

    /**
     * Stereo depth / soundstage. Binaural mode is the good one and only applies
     * to headphones; on the speaker the transaural fallback stays gentle.
     */
    private fun applyVirtualizer() {
        val v = virtualizer ?: return
        try {
            val space = if (enhanceEnabled) preset.space else 0f
            val depthMix = (depth / 100f * 0.6f + space * 0.4f).coerceIn(0f, 1f)
            val scale = if (onHeadphones) 1f else 0.4f
            val strength = (depthMix * 1000 * scale).roundToInt().coerceIn(0, 1000)

            if (onHeadphones) {
                try {
                    v.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL)
                } catch (_: Exception) {}
            }
            if (v.strengthSupported) v.setStrength(strength.toShort())
            v.enabled = enhanceEnabled && strength > 0 && !bypassed
        } catch (_: Exception) {}
    }

    private fun tickVisualizer() {
        try {
            visualizer?.enabled = false
            if (boostLevel > 0f || enhanceEnabled || normalizeEnabled) visualizer?.enabled = true
        } catch (_: Exception) {}
    }

    fun applyVolume(level: Int) {
        val max = getMaxVolume()
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            ((level.toFloat() / 100) * max).toInt(),
            0
        )
    }

    fun restartAudioPlayback() {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        handler.postDelayed({
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }, 100)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Output device info — called on a timer from MainActivity
    // ──────────────────────────────────────────────────────────────────────────

    fun getOutputDeviceInfo(): String? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val activeDevice = devices.firstOrNull { isActiveOutputDevice(it) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        if (activeDevice == null) {
            if (lastDeviceId != null) {
                lastDeviceId = null
                onDeviceRemoved?.invoke()
                return context.getString(R.string.no_device_detected)
            }
            return null
        }

        if (activeDevice.id == lastDeviceId) return null
        lastDeviceId = activeDevice.id

        // Re-voice for the new output before the effects are rebuilt.
        onSpeaker = activeDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                activeDevice.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        onHeadphones = activeDevice.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                activeDevice.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                activeDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                activeDevice.type == AudioDeviceInfo.TYPE_USB_HEADSET

        setupEffects()
        hasRestarted = false
        applyAll()
        restartIfNeeded()

        // Fire device-change callback for profile loading
        val profileKey = DeviceProfileManager(context)
            .profileKey(activeDevice.type, activeDevice.productName)
        val isBt = activeDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                activeDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        onDeviceChanged?.invoke(profileKey, activeDevice.productName.toString(), isBt)

        return buildDeviceInfoString(activeDevice)
    }

    private fun buildDeviceInfoString(dev: AudioDeviceInfo): String {
        val sampleRates = dev.sampleRates.joinToString()
        val codecHint = getCodecHint(dev)
        val samsungHint = if (isSamsungDevice) " [One UI]" else ""

        return "${context.getString(R.string.device_name_label)}\t\t\t\t${dev.productName ?: "N/A"}$samsungHint\n" +
            "${context.getString(R.string.device_type_label)}\t\t\t\t${getDeviceType(dev.type)} (${dev.type})\n" +
            "${context.getString(R.string.device_id_label)}\t\t\t\t\t\t${dev.id}\n\n" +
            "${context.getString(R.string.channels_label)}\t\t\t\t\t\t\t\t${dev.channelCounts.joinToString().ifEmpty { "N/A" }}\n" +
            "${context.getString(R.string.encodings_label)}\t\t\t\t\t\t${getEncodingFormat(dev.encodings).ifEmpty { "N/A" }}\n" +
            (if (codecHint.isNotEmpty()) "${context.getString(R.string.codec_label)}\t\t\t\t\t\t\t\t$codecHint\n" else "") +
            "\n${context.getString(R.string.sample_rates_label)} ${if (sampleRates.isEmpty()) "\tN/A" else "\n${sampleRates}Hz"}\n"
    }

    // Detect codec quality from encoding formats
    private fun getCodecHint(device: AudioDeviceInfo): String {
        val hasFloat = device.encodings.contains(AudioFormat.ENCODING_PCM_FLOAT)
        val isBt = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        return when {
            hasFloat && isBt && supportsSnapdragonSound -> "Snapdragon Sound (aptX Adaptive)"
            hasFloat && isBt -> "Hi-Res BT (LDAC / aptX)"
            hasFloat -> "PCM Float · Hi-Res"
            isBt -> "Standard BT Codec"
            else -> ""
        }
    }

    fun getDeviceSummary(): String {
        val parts = mutableListOf<String>()
        if (isSamsungDevice) parts.add("Samsung ${Build.MODEL}")
        if (isSnapdragon) parts.add("Snapdragon $snapdragonChipLabel")
        if (supportsSnapdragonSound) parts.add("Snapdragon Sound ✓")
        if (isDolbyPresent) parts.add(context.getString(R.string.dolby_active))
        parts.add(context.getString(R.string.engine_label,
            if (supportsAdvancedEngine) "DynamicsProcessing" else "Equalizer"))
        parts.add(context.getString(R.string.max_boost_label, maxSafeBoostLevel))
        return parts.joinToString(" · ")
    }

    private fun isActiveOutputDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> audioManager.isBluetoothA2dpOn
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> true
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> audioManager.isSpeakerphoneOn
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }

    private fun getDeviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> context.getString(R.string.device_bluetooth)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.device_wired_headphones)
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(R.string.device_usb_audio)
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC -> context.getString(R.string.device_hdmi)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.device_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.device_earpiece)
        else -> context.getString(R.string.device_unknown)
    }

    private fun getEncodingFormat(formats: IntArray): String =
        formats.joinToString { encodingMap[it] ?: context.getString(R.string.format_unknown) }

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
        handler.removeCallbacksAndMessages(null)
        releaseEffects()
    }
}
