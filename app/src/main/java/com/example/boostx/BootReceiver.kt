package com.example.boostx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("BoostXPrefs", Context.MODE_PRIVATE)
            val bootStart = prefs.getBoolean("boot_start", false)
            
            if (bootStart) {
                BoostService.start(context)
            }
        }
    }
}
