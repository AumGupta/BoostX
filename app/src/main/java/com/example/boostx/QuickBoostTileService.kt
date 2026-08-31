package com.example.boostx

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickBoostTileService : TileService() {

    override fun onTileAdded() = refreshTile()
    override fun onStartListening() = refreshTile()

    override fun onClick() {
        val prefs = getSharedPreferences(AudioBoostService.PREFS, MODE_PRIVATE)
        val currentBoost = prefs.getFloat(AudioBoostService.KEY_BOOST, 0f)

        val nextBoost = if (currentBoost > 0f) {
            0f
        } else {
            // Half the ceiling is the sane default when the tile turns boost back on. Only a
            // floor is applied here — AudioController.applyBoost() does the real upper-bound
            // clamp, after first rescaling a legacy 0–100-scale value if one is still stored.
            prefs.getFloat("last_active_boost", AudioController.MAX_BOOST_PERCENT / 2f)
                .coerceAtLeast(1f)
        }

        startForegroundService(
            Intent(this, AudioBoostService::class.java).apply {
                putExtra(AudioBoostService.EXTRA_BOOST, nextBoost)
                putExtra(
                    AudioBoostService.EXTRA_VOLUME,
                    prefs.getFloat(AudioBoostService.KEY_VOLUME, 100f).toInt()
                )
            }
        )
        prefs.edit().putFloat(AudioBoostService.KEY_BOOST, nextBoost).apply()

        refreshTile()
    }

    private fun refreshTile() {
        val prefs = getSharedPreferences(AudioBoostService.PREFS, MODE_PRIVATE)
        val boost = prefs.getFloat(AudioBoostService.KEY_BOOST, 0f)
        val enhance = prefs.getBoolean(AudioBoostService.KEY_ENHANCE, false)
        qsTile?.apply {
            state = if (boost > 0f || enhance) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            // Tile subtitles arrived in Android 10; on Oreo and Pie the label is all there is.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = when {
                    boost > 0f -> getString(R.string.boost_value_format, boost)
                    enhance -> getString(R.string.tile_enhance_only)
                    else -> getString(R.string.tile_off)
                }
            }
            updateTile()
        }
    }
}
