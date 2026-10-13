/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the DataStore snapshot rules (migration slice 2): scaled-int reads,
 * the `hidePtt` inversion, the TTS tri-state (including `""` normalization),
 * news set-ops, overlay caller-defaults, and theme defaults. Written against
 * the pedantic-review P2 tail (spurious TTS diff, untested scaling/inversion).
 */
class SettingsSnapshotTest {
    private lateinit var store: DataStore<Preferences>
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        store = TestDataStores.create()
        settings = Settings.createForTesting(store)
    }

    @Test
    fun scaledReadsDivideByHundred() {
        TestDataStores.put(store, TestDataStores.intKey("vadThreshold"), 50)
        TestDataStores.put(store, TestDataStores.intKey("inputVolume"), 80)
        assertEquals(0.5f, settings.detectionThreshold, 0.0001f)
        assertEquals(0.8f, settings.amplitudeBoostMultiplier, 0.0001f)
    }

    @Test
    fun defaultsMatchConstants() {
        assertEquals(0.35f, settings.detectionThreshold, 0.0001f)
        assertEquals(1.0f, settings.amplitudeBoostMultiplier, 0.0001f)
        assertTrue(settings.isPushToTalkButtonShown)
        assertNull(settings.ttsEngine)
        assertEquals(Settings.DEFAULT_THEME, settings.theme)
        assertEquals(Settings.DEFAULT_THEME_SYSTEM, "system")
    }

    @Test
    fun hidePttInvertsButtonShown() {
        TestDataStores.put(store, TestDataStores.booleanKey("hidePtt"), true)
        assertEquals(false, settings.isPushToTalkButtonShown)
        TestDataStores.put(store, TestDataStores.booleanKey("hidePtt"), false)
        assertEquals(true, settings.isPushToTalkButtonShown)
    }

    @Test
    fun ttsEmptyStringNormalizesToNull() {
        TestDataStores.put(store, TestDataStores.stringKey("ttsEngine"), "")
        assertNull("stored \"\" must read as system-default null", settings.ttsEngine)
        settings.ttsEngine = "com.example.tts"
        assertEquals("com.example.tts", settings.ttsEngine)
        settings.ttsEngine = null
        assertNull(settings.ttsEngine)
    }

    @Test
    fun newsSetOpsAccumulate() {
        settings.addNewsShownVersions(listOf("1.0", null, "", "2.0"))
        assertEquals(setOf("1.0", "2.0"), settings.newsShownVersions)
        settings.resetNewsShownVersion()
        assertTrue(settings.newsShownVersions.isEmpty())
    }

    @Test
    fun overlayPosFallsBackToCallerDefault() {
        assertEquals(42, settings.getOverlayPosX(42))
        assertEquals(43, settings.getOverlayPosY(43))
        settings.setOverlayPosition(7, 9)
        assertEquals(7, settings.getOverlayPosX(42))
        assertEquals(9, settings.getOverlayPosY(43))
    }

    @Test
    fun pttButtonHeightClamps() {
        TestDataStores.put(store, TestDataStores.intKey("pttButtonHeight"), 5)
        assertEquals(Settings.MIN_PTT_BUTTON_HEIGHT, settings.pttButtonHeight)
        TestDataStores.put(store, TestDataStores.intKey("pttButtonHeight"), 500)
        assertEquals(Settings.MAX_PTT_BUTTON_HEIGHT, settings.pttButtonHeight)
    }

    @Test
    fun corruptFramesPerPacketFallsBack() {
        TestDataStores.put(store, TestDataStores.stringKey("audio_per_packet"), "fast")
        assertEquals(se.lublin.humla.Constants.DEFAULT_FRAMES_PER_PACKET, settings.framesPerPacket)
    }

    @Test
    fun languageDefaultsToSystem() {
        assertEquals(Settings.DEFAULT_LANGUAGE, settings.language)
        assertEquals("system", settings.language)
    }
}
