package com.example.boostx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class AudioBoostService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): AudioBoostService = this@AudioBoostService
    }

    private val binder = LocalBinder()
    lateinit var audioController: AudioController
        private set

    var boostLevel: Float = 0f
        private set
    var volumeLevel: Int = 100
        private set

    companion object {
        const val CHANNEL_ID = "boostx_service"
        const val NOTIFICATION_ID = 1
        const val EXTRA_BOOST = "boost_level"
        const val EXTRA_VOLUME = "volume_level"
        const val ACTION_STOP = "com.example.boostx.STOP"

        const val PREFS = "BoostXPrefs"
        const val KEY_BOOST = "boost_value"
        const val KEY_VOLUME = "volume_value"
        const val KEY_CLARITY = "clarity_value"
        const val KEY_DEPTH = "depth_value"
        const val KEY_PRESET = "preset_key"
        const val KEY_ENHANCE = "enhance_enabled"
        const val KEY_BALANCE = "balance_value"
        const val KEY_NOISE_GATE = "noise_gate_enabled"
        const val KEY_NORMALIZE = "normalize_enabled"
        const val KEY_CALL_BOOST = "call_boost_enabled"

        /** Debounce window for UI-driven writes (notification refresh, slider-tick prefs). */
        private const val PERSIST_DEBOUNCE_MS = 400L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingEditor: SharedPreferences.Editor? = null
    private val flushPendingEditsRunnable = Runnable {
        pendingEditor?.apply()
        pendingEditor = null
    }
    private val notificationUpdateRunnable = Runnable { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        audioController = AudioController(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val savedPrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        boostLevel = intent?.getFloatExtra(EXTRA_BOOST, savedPrefs.getFloat(KEY_BOOST, 0f)) ?: 0f
        volumeLevel = intent?.getIntExtra(EXTRA_VOLUME, savedPrefs.getFloat(KEY_VOLUME, 100f).toInt()) ?: 100

        startForeground(NOTIFICATION_ID, buildNotification())

        // Restore the saved voicing before the boost so the first frames are already shaped.
        audioController.setEnhanceEnabled(savedPrefs.getBoolean(KEY_ENHANCE, false))
        audioController.applyPreset(
            AudioController.Preset.fromKey(savedPrefs.getString(KEY_PRESET, null))
        )
        audioController.applyClarity(savedPrefs.getInt(KEY_CLARITY, 0))
        audioController.applyDepth(savedPrefs.getInt(KEY_DEPTH, 0))
        audioController.applyBalance(savedPrefs.getInt(KEY_BALANCE, 0))
        audioController.setNoiseGateEnabled(savedPrefs.getBoolean(KEY_NOISE_GATE, false))
        audioController.setNormalizeEnabled(savedPrefs.getBoolean(KEY_NORMALIZE, false))
        audioController.setCallBoostEnabled(savedPrefs.getBoolean(KEY_CALL_BOOST, false))
        audioController.applyBoost(boostLevel)
        // applyBoost() migrates the legacy 0–100 scale and clamps to the safety ceiling —
        // read the real value back so the very first notification shows what's actually applied.
        boostLevel = audioController.boostLevel
        audioController.applyVolume(volumeLevel)
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun applyBoost(level: Float) {
        audioController.applyBoost(level)
        boostLevel = audioController.boostLevel
        scheduleNotificationUpdate()
        savePrefs()
    }

    fun applyVolume(level: Int) {
        volumeLevel = level
        audioController.applyVolume(level)
        savePrefs()
    }

    fun applyClarity(value: Int) {
        audioController.applyClarity(value)
        putDebounced { putInt(KEY_CLARITY, value) }
    }

    fun applyDepth(value: Int) {
        audioController.applyDepth(value)
        putDebounced { putInt(KEY_DEPTH, value) }
    }

    fun applyPreset(preset: AudioController.Preset) {
        audioController.applyPreset(preset)
        prefs().edit().putString(KEY_PRESET, preset.key).apply()
        updateNotification()
    }

    fun setEnhanceEnabled(enabled: Boolean) {
        audioController.setEnhanceEnabled(enabled)
        prefs().edit().putBoolean(KEY_ENHANCE, enabled).apply()
        updateNotification()
    }

    fun applyBalance(value: Int) {
        audioController.applyBalance(value)
        putDebounced { putInt(KEY_BALANCE, value) }
    }

    fun setNoiseGateEnabled(enabled: Boolean) {
        audioController.setNoiseGateEnabled(enabled)
        prefs().edit().putBoolean(KEY_NOISE_GATE, enabled).apply()
    }

    fun setNormalizeEnabled(enabled: Boolean) {
        audioController.setNormalizeEnabled(enabled)
        prefs().edit().putBoolean(KEY_NORMALIZE, enabled).apply()
    }

    fun setCallBoostEnabled(enabled: Boolean) {
        audioController.setCallBoostEnabled(enabled)
        prefs().edit().putBoolean(KEY_CALL_BOOST, enabled).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun savePrefs() {
        prefs().edit().apply {
            putFloat(KEY_BOOST, boostLevel)
            putFloat(KEY_VOLUME, volumeLevel.toFloat())
            // Single owner for this key now — QuickBoostTileService's "last non-zero boost"
            // memory used to also be written separately from MainActivity on every slider tick.
            if (boostLevel > 0f) putFloat("last_active_boost", boostLevel)
            apply()
        }
    }

    /** Batches rapid slider-driven pref writes (clarity/depth/balance) into one delayed commit. */
    private fun putDebounced(edit: SharedPreferences.Editor.() -> Unit) {
        val editor = pendingEditor ?: prefs().edit().also { pendingEditor = it }
        editor.edit()
        handler.removeCallbacks(flushPendingEditsRunnable)
        handler.postDelayed(flushPendingEditsRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun scheduleNotificationUpdate() {
        handler.removeCallbacks(notificationUpdateRunnable)
        handler.postDelayed(notificationUpdateRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioBoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val boostText = when {
            boostLevel > 0f && audioController.enhanceEnabled -> getString(
                R.string.service_boost_and_enhance,
                boostLevel,
                getString(audioController.preset.labelRes)
            )
            boostLevel > 0f -> getString(R.string.service_boost_active, boostLevel)
            audioController.enhanceEnabled -> getString(
                R.string.service_enhance_only,
                getString(audioController.preset.labelRes)
            )
            else -> getString(R.string.service_boost_off)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(boostText)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.service_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // Flush anything still debounced rather than lose the last slider position.
        handler.removeCallbacksAndMessages(null)
        pendingEditor?.apply()
        pendingEditor = null
        audioController.release()
        super.onDestroy()
    }
}
