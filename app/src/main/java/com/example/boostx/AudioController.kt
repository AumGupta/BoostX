package com.example.boostx

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import androidx.annotation.RequiresApi
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AudioController(private val context: Context) {

    companion object {
        /**
         * The boost slider's scale. It is a percentage of *this device's* safe headroom,
         * not an absolute gain — 10 on a wired flagship is far more dB than 10 on the
         * built-in speaker of a mid-range phone. [HardwareProfile.gainCeilingDb] owns the
         * conversion; see [gainCeilingDb].
         */
        const val MAX_BOOST_PERCENT = 10f

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

        /**
         * Auto-loudness backs off when the material is already peaking. Perceived loudness
         * is an RMS question, but headroom is a peak question — a track with a 6 dB
         * crest factor has nothing left to give no matter how quiet its average is.
         */
        private const val PEAK_HEADROOM_FLOOR_DBFS = -3f

        /** Extra excursion margin on the built-in speaker: hold peaks further from the rails. */
        private const val SPEAKER_LIMITER_CEILING_DB = -2f

        private const val CALL_POLL_INTERVAL_MS = 2000L

        /**
         * How long to wait after building the chain before pushing the tone stage again.
         *
         * The AIDL DynamicsProcessing HAL — seen on a Snapdragon 8 Elite running Android 16 —
         * rejects the first pre-EQ parameter push issued immediately after the effect opens:
         *
         *     AHAL_DynamicsProcessingLibEffects: setParameterSpecific:
         *         mContext->setPreEqBand(...) != RetCode::SUCCESS
         *
         * The framework surfaces no error for it, so the band update is simply lost. Every
         * later push succeeds, which is why this only bites the one applyAll() that follows
         * setupEffects() — after a route change that is the *only* push, and the voicing
         * would stay missing until the user happened to move a control.
         */
        private const val SETUP_SETTLE_MS = 150L
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
                entries.firstOrNull { it.key == key } ?: BALANCED
        }
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Everything we know about the silicon and the transducer currently being driven. */
    val hardware = HardwareProfile(context)

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
    private var lastActiveDevice: AudioDeviceInfo? = null
    private var hasRestarted = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Plugging in headphones or connecting earbuds changes the gain budget outright, and it
     * has to be noticed by the service, not by whoever happens to have the UI open.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshRoute()
        }
    }

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


    /** What the current link appears to be carrying. Drives the top-band scaling. */
    var codecTier: HardwareProfile.CodecTier = HardwareProfile.CodecTier.UNKNOWN
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

    /** Which transducer the mix is currently going to. Decides the whole gain budget. */
    var route: HardwareProfile.Route = HardwareProfile.Route.SPEAKER
        private set

    private val onSpeaker: Boolean
        get() = route == HardwareProfile.Route.SPEAKER || route == HardwareProfile.Route.EARPIECE

    private val onHeadphones: Boolean
        get() = route == HardwareProfile.Route.WIRED ||
            route == HardwareProfile.Route.USB ||
            route == HardwareProfile.Route.BLUETOOTH ||
            route == HardwareProfile.Route.BLUETOOTH_LE

    // ── Hardware facts, surfaced for the UI ───────────────────────────────────

    val supportsAdvancedEngine: Boolean get() = hardware.hasAdvancedEngine
    val isSamsungDevice: Boolean get() = hardware.isSamsung
    val isSnapdragon: Boolean get() = hardware.isQualcomm
    val supportsSnapdragonSound: Boolean get() = hardware.supportsSnapdragonSound
    val isDolbyPresent: Boolean get() = hardware.isDolbyPresent

    /**
     * The slider always spans the full scale now — the *meaning* of the top of the scale is
     * what changes per device, and [gainCeilingDb] carries that. Kept as a property so the
     * UI still has one place to ask before sizing the control.
     */
    val maxSafeBoostLevel: Float get() = MAX_BOOST_PERCENT

    /**
     * True only when a real true-peak limiter sits in the chain. Without it every dB of
     * make-up gain is a dB of potential clipping, and the ceiling collapses accordingly.
     */
    private val limiterAvailable: Boolean
        get() = supportsAdvancedEngine && dynamicsProcessing != null

    /** Make-up gain available on the current route, in dB, after thermal and Dolby derating. */
    val gainCeilingDb: Float
        get() = HardwareProfile.gainCeilingDb(
            route = route,
            tier = hardware.tier,
            dolbyPresent = hardware.isDolbyPresent,
            limiterAvailable = limiterAvailable,
            thermalScale = hardware.thermalScale
        )

    /** Clean gain the boost slider is currently asking for, in dB. */
    val boostGainDb: Float
        get() = boostLevel / MAX_BOOST_PERCENT * gainCeilingDb

    /** True while the SoC is throttling hard enough that we have pulled the ceiling down. */
    val isThermallyLimited: Boolean get() = hardware.thermalScale < 1f

    // Callback for when audio output device changes (used by MainActivity for profiles)
    var onDeviceChanged: ((key: String, deviceName: String, isBluetooth: Boolean) -> Unit)? = null
    var onDeviceRemoved: (() -> Unit)? = null

    // Callbacks for the "effects engine unavailable" banner (used by MainActivity)
    var onEffectsFailed: (() -> Unit)? = null
    var onEffectsRecovered: (() -> Unit)? = null

    /** Fires when the headroom budget moves — route change or thermal throttling. */
    var onHeadroomChanged: (() -> Unit)? = null

    init {
        audioSessionID = audioManager.generateAudioSessionId()
        // Pick up whatever is already plugged in before the first sample goes out, then keep
        // following it for as long as the service lives. Finding a route already builds the
        // chain voiced for it, so only build blind when there was nothing to detect.
        if (refreshRoute() != RouteChange.CHANGED) setupEffects()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        // Throttling silently shrinks what the amplifier can do; follow it rather than
        // keep pushing a ceiling the hardware has already taken away.
        hardware.startThermalMonitoring {
            applyAll()
            onHeadroomChanged?.invoke()
        }
        // Call-state polling only starts once Call Boost is actually enabled — see setCallBoostEnabled.
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Effect setup
    //
    // The whole chain hangs off session 0 (the global output mix) so it applies
    // to every app. On API 28+ DynamicsProcessing carries the tone shaping,
    // the multiband compressor, the peak limiter *and the make-up gain*; older
    // devices fall back to LoudnessEnhancer + Equalizer + BassBoost. Samsung
    // One UI intercepts LoudnessEnhancer at session 0, which is why
    // DynamicsProcessing is built first there.
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

        // Re-send the tone stage once the freshly-opened effect has settled; the first push
        // can be silently dropped by the HAL. See [SETUP_SETTLE_MS].
        handler.removeCallbacks(resendToneStage)
        handler.postDelayed(resendToneStage, SETUP_SETTLE_MS)
    }

    /** Second attempt at the tone stage, for HALs that drop the first one. */
    private val resendToneStage = Runnable {
        applyDynamicsProcessing(buildEqCurve())
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
        // Checked against SDK_INT directly rather than through supportsAdvancedEngine so the
        // guard stays visible to lint — DynamicsProcessing is API 28 throughout.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, EQ_CUTOFFS.size,   // pre-EQ  — tone shaping (clarity / depth)
                true, MBC_BANDS,         // MBC     — density, perceived loudness
                false, 0,                // post-EQ — unused
                true                     // limiter — peak safety + make-up gain
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
     * Rough loudness estimate from the waveform tap, smoothed into a slow trim so a quiet
     * verse doesn't get chased mid-note. Silence (pauses, gaps between tracks) is ignored so
     * playback doesn't get slammed to full gain the instant a silent section resumes.
     *
     * Both RMS and peak are measured: RMS says how loud it *sounds*, peak says how much room
     * is actually left. Material that is already touching the ceiling gets no lift however
     * quiet its average is, which is what keeps auto-loudness from squashing dynamic music.
     */
    private fun onWaveformCaptured(waveform: ByteArray) {
        if (!normalizeEnabled || waveform.isEmpty()) return

        var sumSquares = 0.0
        var peak = 0
        for (b in waveform) {
            val centered = (b.toInt() and 0xFF) - 128
            sumSquares += (centered * centered).toDouble()
            val magnitude = abs(centered)
            if (magnitude > peak) peak = magnitude
        }
        val rms = sqrt(sumSquares / waveform.size)
        if (rms < 1.0) return

        val dbfs = (20 * log10(rms / 128.0)).toFloat()
        if (dbfs < SILENCE_FLOOR_DBFS) return

        val peakDbfs = if (peak > 0) (20 * log10(peak / 128.0)).toFloat() else -96f

        smoothedLoudnessDbfs += (dbfs - smoothedLoudnessDbfs) * NORMALIZE_SMOOTHING

        // How far the peak still is from the ceiling caps any upward correction.
        val availableHeadroomDb = (PEAK_HEADROOM_FLOOR_DBFS - peakDbfs).coerceAtLeast(0f)
        val trim = (TARGET_LOUDNESS_DBFS - smoothedLoudnessDbfs)
            .coerceIn(-NORMALIZE_RAW_TRIM_LIMIT_DB, NORMALIZE_RAW_TRIM_LIMIT_DB)
            .coerceAtMost(availableHeadroomDb)

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
        boostLevel = migrated.coerceIn(0f, MAX_BOOST_PERCENT)
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

    /**
     * Final pre-EQ curve, built in two layers:
     *
     *  1. the preset voicing plus the clarity/depth tilts, scaled back on the built-in
     *     speaker where low bass only buys distortion;
     *  2. a codec-aware trim of the top two bands, because lifting air over a lossy link
     *     amplifies the codec's own artefacts more than it reveals detail.
     */
    private fun buildEqCurve(): FloatArray {
        val gains = FloatArray(EQ_CUTOFFS.size)
        if (!enhanceEnabled) return gains

        if (enhanceEnabled) {
            val c = clarity / 100f
            val d = depth / 100f
            val subScale = if (onSpeaker) 0.35f else 1f
            val base = preset.curve

            gains[0] = base[0] * subScale + 5.0f * d * subScale   // sub      — depth
            gains[1] = base[1] + 0.8f * d - 2.5f * c              // low-mid  — de-mud for clarity
            gains[2] = base[2] - 0.5f * c                         // mid      — anchor
            gains[3] = base[3] + 2.0f * c                         // presence
            gains[4] = base[4] + 3.0f * c                         // detail
            gains[5] = base[5] + 2.0f * c                         // air
        }


        val airScale = HardwareProfile.airScaleFor(codecTier)
        if (airScale < 1f) {
            // Only pull *lifts* back — a cut in the top octave is never the codec's problem.
            for (i in 4..5) if (gains[i] > 0f) gains[i] *= airScale
        }

        return gains
    }

    /** Headroom pulled out of the input so EQ boosts cannot clip before the limiter. */
    private fun headroomFor(gains: FloatArray): Float =
        max(0f, gains.max()) * 0.7f

    /**
     * LoudnessEnhancer is a plain gain stage with nothing after it, so it only carries the
     * boost when there is no DynamicsProcessing limiter to catch the peaks. When the limiter
     * *is* present the make-up gain belongs inside it — running both would apply the
     * requested gain twice and hand the limiter a signal already 2× too hot.
     */
    private fun applyLoudnessEnhancer() {
        loudnessEnhancer?.apply {
            try {
                if (limiterAvailable) {
                    setTargetGain(0)
                    enabled = false
                    return@apply
                }
                // Unlimited path: gain is capped hard, and auto-loudness may only ever add
                // here — trimming a loud track down has no clipping risk to justify it.
                val extraTrim = normalizationTrimDb.coerceAtLeast(0f)
                val totalGainDb = (boostGainDb + extraTrim)
                    .coerceIn(0f, HardwareProfile.UNLIMITED_PATH_MAX_DB)
                setTargetGain((totalGainDb * 100).roundToInt())  // dB → mB
                enabled = totalGainDb > 0.01f && !bypassed
            } catch (_: Exception) {}
        }
    }

    private fun applyDynamicsProcessing(gains: FloatArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
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

            // Auto-loudness rides on top of the user's boost but never past the route's
            // ceiling; it can always cut a hot track down since a cut can never clip.
            val cappedGainDb = (boostGainDb + normalizationTrimDb)
                .coerceIn(-NORMALIZE_MAX_CUT_DB, gainCeilingDb)

            val basePostGain = cappedGainDb + headroom * 0.5f
            // Micro-speakers need the extra excursion margin more than the last dB.
            val ceiling = if (onSpeaker) SPEAKER_LIMITER_CEILING_DB
                          else HardwareProfile.LIMITER_CEILING_DB

            if (balance == 0) {
                // Common case: both channels are identical — one call instead of CHANNELS.
                val limiter = dp.getLimiterByChannelIndex(0)
                limiter.isEnabled = true
                limiter.attackTime = 1f
                limiter.releaseTime = 60f
                limiter.ratio = 10f
                limiter.threshold = ceiling
                limiter.postGain = basePostGain
                dp.setLimiterAllChannelsTo(limiter)
            } else {
                for (channel in 0 until CHANNELS) {
                    val limiter = dp.getLimiterByChannelIndex(channel)
                    limiter.isEnabled = true
                    limiter.attackTime = 1f
                    limiter.releaseTime = 60f
                    limiter.ratio = 10f
                    limiter.threshold = ceiling
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
    @RequiresApi(Build.VERSION_CODES.P)
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
     * Stereo depth / soundstage. Binaural mode is the good one and only applies to
     * headphones; on the speaker the transaural fallback stays gentle.
     *
     * When the platform spatializer is already widening this output we stand down to a
     * fraction of our own strength — two widening stages in series smear the centre image
     * rather than doubling the effect.
     */
    private fun applyVirtualizer() {
        val v = virtualizer ?: return
        try {
            val space = if (enhanceEnabled) preset.space else 0f
            val depthMix = (depth / 100f * 0.6f + space * 0.4f).coerceIn(0f, 1f)
            val routeScale = if (onHeadphones) 1f else 0.4f
            val spatialScale = if (hardware.isSpatializerActive) 0.3f else 1f
            val strength = (depthMix * 1000 * routeScale * spatialScale)
                .roundToInt().coerceIn(0, 1000)

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

    private enum class RouteChange { NONE, LOST, CHANGED }

    /**
     * Re-evaluates which transducer we are driving and re-voices the chain if it moved.
     *
     * Called both from the UI's polling loop and from the framework's own device callback,
     * because the route decides the entire gain budget and the service has to track it
     * whether or not anybody is looking at the app — a boot-started service that never
     * noticed the headphones would sit on the speaker's much smaller ceiling all day.
     */
    private fun refreshRoute(): RouteChange {
        val activeDevice = hardware.activeOutputDevice()

        if (activeDevice == null) {
            lastActiveDevice = null
            if (lastDeviceId == null) return RouteChange.NONE
            lastDeviceId = null
            onDeviceRemoved?.invoke()
            return RouteChange.LOST
        }

        if (activeDevice.id == lastDeviceId) return RouteChange.NONE
        lastDeviceId = activeDevice.id
        lastActiveDevice = activeDevice

        // Re-voice for the new output before the effects are rebuilt. The route decides the
        // entire gain budget and the codec decides the top-band trim, so both have to land
        // before applyAll().
        route = HardwareProfile.routeForDeviceType(activeDevice.type)
        codecTier = hardware.codecTier(activeDevice)

        setupEffects()
        hasRestarted = false
        applyAll()
        restartIfNeeded()
        onHeadroomChanged?.invoke()
        emitCurrentDevice()

        return RouteChange.CHANGED
    }

    fun getOutputDeviceInfo(): String? = when (refreshRoute()) {
        RouteChange.LOST -> context.getString(R.string.no_device_detected)
        RouteChange.CHANGED -> lastActiveDevice?.let { buildDeviceInfoString(it) }
        RouteChange.NONE -> null
    }

    /**
     * Re-announces the current output. The service detects the route at construction, long
     * before any UI binds — without this the activity would sit waiting for a change that
     * already happened, and never show the profile bar for headphones already connected.
     */
    fun emitCurrentDevice() {
        val device = lastActiveDevice ?: return
        val profileKey = DeviceProfileManager(context)
            .profileKey(device.type, device.productName)
        val isBt = route == HardwareProfile.Route.BLUETOOTH ||
            route == HardwareProfile.Route.BLUETOOTH_LE
        onDeviceChanged?.invoke(profileKey, device.productName.toString(), isBt)
    }

    /** Description of the output in use right now, without waiting for it to change. */
    fun describeCurrentDevice(): String? = lastActiveDevice?.let { buildDeviceInfoString(it) }

    private fun buildDeviceInfoString(dev: AudioDeviceInfo): String {
        val codecHint = getCodecHint()
        val linkHint = hardware.linkCapability(dev)
        val samsungHint = if (isSamsungDevice) " [One UI]" else ""
        val latency = hardware.outputBurstLatencyMs

        val rows = mutableListOf<Pair<String, String>>()
        rows += context.getString(R.string.device_name_label) to
            "${dev.productName ?: "N/A"}$samsungHint"
        rows += context.getString(R.string.device_type_label) to getDeviceType(dev.type)
        rows += context.getString(R.string.channels_label) to
            dev.channelCounts.joinToString().ifEmpty { "N/A" }
        if (codecHint.isNotEmpty()) {
            rows += context.getString(R.string.codec_label) to codecHint
        }
        if (linkHint.isNotEmpty()) {
            rows += context.getString(R.string.link_label) to linkHint
        }
        if (latency > 0f) {
            rows += context.getString(R.string.latency_label) to
                context.getString(R.string.latency_local_format, formatMs(latency))
        }
        rows += context.getString(R.string.headroom_label) to headroomSummary()

        // Pad the labels to a common width rather than guessing at tab counts — the old
        // layout drifted the moment a label changed length.
        val labelWidth = rows.maxOf { it.first.length }
        return rows.joinToString("\n") { (label, value) -> "${label.padEnd(labelWidth)}  $value" }
    }

    /** Why the ceiling is the number it is — limiter present, thermally cut, or uncapped path. */
    fun headroomQualifier(): String = when {
        !limiterAvailable -> context.getString(R.string.headroom_unlimited_path)
        isThermallyLimited -> context.getString(R.string.headroom_thermal)
        else -> context.getString(R.string.headroom_limited)
    }

    /** "+12.0 dB · peak-limited" — what the current route actually allows, and why. */
    fun headroomSummary(): String =
        context.getString(R.string.headroom_format, formatDb(gainCeilingDb), headroomQualifier())

    /** Signed so a gain of zero and a cut of zero do not look the same. */
    fun formatDb(value: Float): String = String.format(Locale.US, "%+.1f dB", value)

    private fun formatMs(value: Float): String = String.format(Locale.US, "%.1f ms", value)

    private fun getCodecHint(): String = when (codecTier) {
        HardwareProfile.CodecTier.LDAC_HIRES -> context.getString(R.string.codec_ldac_hires)
        HardwareProfile.CodecTier.LDAC -> context.getString(R.string.codec_ldac)
        HardwareProfile.CodecTier.APTX_ADAPTIVE -> context.getString(R.string.codec_aptx_adaptive)
        HardwareProfile.CodecTier.LE_LC3 -> context.getString(R.string.codec_le_lc3)
        HardwareProfile.CodecTier.HIRES_PCM -> context.getString(R.string.codec_hires_pcm)
        HardwareProfile.CodecTier.A2DP_STANDARD -> context.getString(R.string.codec_a2dp_standard)
        HardwareProfile.CodecTier.UNKNOWN -> ""
    }

    fun getDeviceSummary(): String {
        val parts = hardware.summaryParts().toMutableList()
        parts.add(context.getString(
            R.string.engine_label,
            if (limiterAvailable) "DynamicsProcessing" else "Equalizer"
        ))
        // Headroom deliberately omitted — the device table below has its own row for it,
        // and repeating it in the banner just made the card say the same thing twice.
        return parts.joinToString(" · ")
    }

    private fun getDeviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> context.getString(R.string.device_bluetooth)
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> context.getString(R.string.device_ble_audio)
        AudioDeviceInfo.TYPE_HEARING_AID -> context.getString(R.string.device_hearing_aid)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.device_wired_headphones)
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> context.getString(R.string.device_usb_audio)
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> context.getString(R.string.device_hdmi)
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK -> context.getString(R.string.device_line_out)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> context.getString(R.string.device_speaker)
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> context.getString(R.string.device_earpiece)
        else -> context.getString(R.string.device_unknown)
    }

    /**
     * The single most useful thing to tell the user about the current state, or null when
     * nothing needs saying. Ordered by how much it is costing them right now.
     */
    fun advisoryNote(): String? = when {
        isThermallyLimited -> context.getString(R.string.hardware_thermal_warning)
        codecTier == HardwareProfile.CodecTier.A2DP_STANDARD ->
            context.getString(R.string.codec_standard_note)
        hardware.hasMultipleBluetoothOutputs() ->
            context.getString(R.string.hardware_multipoint_note)
        hardware.isSpatializerActive -> context.getString(R.string.hardware_spatializer_note)
        else -> null
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {}
        hardware.stopThermalMonitoring()
        releaseEffects()
    }
}
