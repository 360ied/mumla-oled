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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.feature

import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.client.ClientState
import se.lublin.humla.state.emptySessionState
import se.lublin.humla.voice.TalkActivity
import se.lublin.mumla.service.MumlaOverlay

/** Overlay margins plus the feature's show/hide and permission rules. */
class OverlayFeatureTest {

    private class Fixture {
        val platform = FakeFeaturePlatform()
        val state = MutableStateFlow<ClientState>(ClientState.Idle)
        val session = MutableStateFlow(emptySessionState())
        val talk = MutableStateFlow(TalkActivity.initial())
        val configuration = MutableStateFlow(Configuration())
        val settings = MutableStateFlow(testSnapshot(overlayShown = false))
        val writes = ArrayList<Boolean>()
        val feature = OverlayFeature(
            state, session, talk, configuration, settings, platform,
            object : OverlayHost {
                override fun overlayPinned() = error("unused")
                override fun overlayLayout(): OverlayLayout = error("unused")
                override fun overlayPosX(defaultX: Int) = error("unused")
                override fun overlayPosY(defaultY: Int) = error("unused")
                override fun setOverlayPosition(x: Int, y: Int) = error("unused")
                override fun sessionState() = session
                override fun talkActivity() = talk
            },
        ) { writes.add(it) }
    }

    @Test fun edgeMarginsPreferLiveInsetsThenResourcesThenDefaults() {
        assertEquals(116, MumlaOverlay.resolveEdgeMarginPx(100, 60, 40, 2.0f))
        assertEquals(76, MumlaOverlay.resolveEdgeMarginPx(0, 60, 40, 2.0f))
        assertEquals(80, MumlaOverlay.resolveEdgeMarginPx(-10, -5, 40, 2.0f))
        assertEquals(72, MumlaOverlay.resolveEdgeMarginPx(48, 100, 56, 3.0f))
        assertEquals(124, MumlaOverlay.resolveEdgeMarginPx(0, 100, 56, 3.0f))
        assertEquals(168, MumlaOverlay.resolveEdgeMarginPx(0, 0, 56, 3.0f))
        assertEquals(46, MumlaOverlay.resolveSideMarginPx(30, 2.0f))
        assertEquals(32, MumlaOverlay.resolveSideMarginPx(0, 2.0f))
    }

    @Test fun showsOnlyWhileConnectedWithTheSetting() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = connectedState()
        f.settings.value = testSnapshot(overlayShown = true)
        assertTrue(f.feature.isShown())
        f.state.value = ClientState.Idle
        assertFalse(f.feature.isShown())
    }

    @Test fun missingPermissionRevertsTheSetting() {
        val f = Fixture()
        f.platform.canDraw = false
        f.feature.setShown(true)
        assertEquals(1, f.platform.permissionRequests)
        assertEquals(listOf(false), f.writes)
    }

    @Test fun configurationChangeRepositions() = runFeature { scope ->
        val f = Fixture()
        f.feature.start(scope)
        f.state.value = connectedState()
        f.settings.value = testSnapshot(overlayShown = true)
        f.configuration.value = Configuration()
        assertTrue(f.platform.overlay.positions > 0)
    }
}
