package com.example.boostx

import android.media.AudioDeviceInfo
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Covers the gain-budget maths — the part of the hardware layer that decides how loud
 * BoostX is allowed to get. It is deliberately free of any [android.content.Context] so it
 * runs on the JVM, because getting these numbers wrong is a speaker-damage bug, not a
 * cosmetic one.
 */
class HardwareProfileTest {

    private fun ceiling(
        route: HardwareProfile.Route,
        tier: HardwareProfile.Tier = HardwareProfile.Tier.FLAGSHIP,
        dolby: Boolean = false,
        limiter: Boolean = true,
        thermal: Float = 1f
    ) = HardwareProfile.gainCeilingDb(route, tier, dolby, limiter, thermal)

    @Test
    fun `headphone routes get more headroom than the built-in speaker`() {
        val wired = ceiling(HardwareProfile.Route.WIRED)
        val speaker = ceiling(HardwareProfile.Route.SPEAKER)
        assertTrue("wired ($wired) must exceed speaker ($speaker)", wired > speaker)
    }

    @Test
    fun `earpiece is the most conservative route of all`() {
        val earpiece = ceiling(HardwareProfile.Route.EARPIECE, HardwareProfile.Tier.ULTRA)
        val everythingElse = HardwareProfile.Route.entries
            .filter { it != HardwareProfile.Route.EARPIECE }
            .map { ceiling(it, HardwareProfile.Tier.ULTRA) }
        assertTrue(everythingElse.all { it >= earpiece })
    }

    @Test
    fun `ultra tier is never quieter than mainstream on the same route`() {
        for (route in HardwareProfile.Route.entries) {
            val ultra = ceiling(route, HardwareProfile.Tier.ULTRA)
            val mainstream = ceiling(route, HardwareProfile.Tier.MAINSTREAM)
            assertTrue("$route: ultra $ultra < mainstream $mainstream", ultra >= mainstream)
        }
    }

    @Test
    fun `missing limiter caps every route at the unlimited-path maximum`() {
        for (route in HardwareProfile.Route.entries) {
            val unlimited = ceiling(route, HardwareProfile.Tier.ULTRA, limiter = false)
            assertTrue(
                "$route exceeded the no-limiter cap: $unlimited",
                unlimited <= HardwareProfile.UNLIMITED_PATH_MAX_DB
            )
        }
    }

    @Test
    fun `dolby derating lowers the ceiling`() {
        val plain = ceiling(HardwareProfile.Route.WIRED)
        val withDolby = ceiling(HardwareProfile.Route.WIRED, dolby = true)
        assertTrue("dolby $withDolby should be below plain $plain", withDolby < plain)
    }

    @Test
    fun `thermal scaling shrinks the ceiling and never goes negative`() {
        val cool = ceiling(HardwareProfile.Route.WIRED, thermal = 1f)
        val hot = ceiling(HardwareProfile.Route.WIRED, thermal = 0.4f)
        assertTrue(hot < cool)
        assertEquals(0f, ceiling(HardwareProfile.Route.WIRED, thermal = 0f), 0.001f)
        assertTrue(ceiling(HardwareProfile.Route.WIRED, thermal = -5f) >= 0f)
    }

    @Test
    fun `thermal scale is monotonically non-increasing as the device heats up`() {
        val statuses = listOf(
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN
        )
        val scales = statuses.map { HardwareProfile.thermalScaleFor(it) }
        assertEquals(1f, scales.first(), 0.001f)
        scales.zipWithNext { hotter, hottest ->
            assertTrue("scale rose while heating: $hotter -> $hottest", hottest <= hotter)
        }
    }

    @Test
    fun `LE audio device types route to the bluetooth-LE budget`() {
        assertEquals(
            HardwareProfile.Route.BLUETOOTH_LE,
            HardwareProfile.routeForDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET)
        )
        assertEquals(
            HardwareProfile.Route.BLUETOOTH_LE,
            HardwareProfile.routeForDeviceType(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        )
    }

    @Test
    fun `unknown device types fall back to the conservative OTHER route`() {
        assertEquals(HardwareProfile.Route.OTHER, HardwareProfile.routeForDeviceType(-1))
        assertEquals(HardwareProfile.Route.OTHER, HardwareProfile.routeForDeviceType(9999))
    }

    @Test
    fun `speaker-safe is treated exactly like the built-in speaker`() {
        assertEquals(
            HardwareProfile.routeForDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            HardwareProfile.routeForDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
        )
    }

    // ── Codec-aware top-band scaling ──────────────────────────────────────────

    @Test
    fun `a standard A2DP link scales the air bands back the hardest`() {
        val standard = HardwareProfile.airScaleFor(HardwareProfile.CodecTier.A2DP_STANDARD)
        val ldac = HardwareProfile.airScaleFor(HardwareProfile.CodecTier.LDAC)
        val hiRes = HardwareProfile.airScaleFor(HardwareProfile.CodecTier.LDAC_HIRES)

        assertTrue(standard < ldac)
        assertTrue(ldac < hiRes)
        assertEquals(1f, hiRes, 0.001f)
    }

    @Test
    fun `air scaling never inverts or exceeds unity`() {
        for (tier in HardwareProfile.CodecTier.entries) {
            val scale = HardwareProfile.airScaleFor(tier)
            assertTrue("$tier produced $scale", scale in 0f..1f)
        }
    }

    // ── Dolby state resolution & formatting ───────────────────────────────────

    @Test
    fun `dolby derating applies only on Samsung when package exists and setting is not off`() {
        assertFalse(HardwareProfile.isDolbyActiveFor(isSamsung = false, packageInstalled = true, dolbySetting = 1))
        assertFalse(HardwareProfile.isDolbyActiveFor(isSamsung = true, packageInstalled = false, dolbySetting = 1))
        assertFalse(HardwareProfile.isDolbyActiveFor(isSamsung = true, packageInstalled = true, dolbySetting = 0))
        assertTrue(HardwareProfile.isDolbyActiveFor(isSamsung = true, packageInstalled = true, dolbySetting = 1))
        assertTrue(HardwareProfile.isDolbyActiveFor(isSamsung = true, packageInstalled = true, dolbySetting = -1))
    }

    @Test
    fun `sample rate formatting uses US decimal point regardless of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("nl-NL"))
            assertEquals("44.1 kHz", HardwareProfile.formatSampleRateKhz(44100))
            assertEquals("48 kHz", HardwareProfile.formatSampleRateKhz(48000))
            assertEquals("96 kHz", HardwareProfile.formatSampleRateKhz(96000))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `codecTierFor distinguishes Bluetooth hi-res, standard A2DP, and LE audio`() {
        assertEquals(
            HardwareProfile.CodecTier.LE_LC3,
            HardwareProfile.codecTierFor(HardwareProfile.Route.BLUETOOTH_LE, 48000, hasHiResPcm = false, supportsSnapdragonSound = false)
        )
        assertEquals(
            HardwareProfile.CodecTier.LDAC_HIRES,
            HardwareProfile.codecTierFor(HardwareProfile.Route.BLUETOOTH, 96000, hasHiResPcm = false, supportsSnapdragonSound = false)
        )
        assertEquals(
            HardwareProfile.CodecTier.APTX_ADAPTIVE,
            HardwareProfile.codecTierFor(HardwareProfile.Route.BLUETOOTH, 48000, hasHiResPcm = true, supportsSnapdragonSound = true)
        )
        assertEquals(
            HardwareProfile.CodecTier.LDAC,
            HardwareProfile.codecTierFor(HardwareProfile.Route.BLUETOOTH, 48000, hasHiResPcm = true, supportsSnapdragonSound = false)
        )
        assertEquals(
            HardwareProfile.CodecTier.A2DP_STANDARD,
            HardwareProfile.codecTierFor(HardwareProfile.Route.BLUETOOTH, 48000, hasHiResPcm = false, supportsSnapdragonSound = false)
        )
        assertEquals(
            HardwareProfile.CodecTier.HIRES_PCM,
            HardwareProfile.codecTierFor(HardwareProfile.Route.USB, 192000, hasHiResPcm = true, supportsSnapdragonSound = false)
        )
    }
}
