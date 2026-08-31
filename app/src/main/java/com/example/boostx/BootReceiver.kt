package com.example.boostx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences(AudioBoostService.PREFS, Context.MODE_PRIVATE)
        val bootStart = prefs.getBoolean("boot_start", false)
        val boostLevel = prefs.getFloat(AudioBoostService.KEY_BOOST, 0f)
        val enhance = prefs.getBoolean(AudioBoostService.KEY_ENHANCE, false)
        val callBoost = prefs.getBoolean(AudioBoostService.KEY_CALL_BOOST, false)
        val normalize = prefs.getBoolean(AudioBoostService.KEY_NORMALIZE, false)

        // Enhancement, call-boost, or auto-loudness alone are worth starting for — all three
        // work at zero media boost.
        if (!bootStart || (boostLevel <= 0f && !enhance && !callBoost && !normalize)) return

        context.startForegroundService(
            Intent(context, AudioBoostService::class.java).apply {
                putExtra(AudioBoostService.EXTRA_BOOST, boostLevel)
                putExtra(
                    AudioBoostService.EXTRA_VOLUME,
                    prefs.getFloat(AudioBoostService.KEY_VOLUME, 100f).toInt()
                )
            }
        )
    }
}
