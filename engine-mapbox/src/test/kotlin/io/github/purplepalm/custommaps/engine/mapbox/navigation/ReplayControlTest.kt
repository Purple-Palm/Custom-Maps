@file:OptIn(ExperimentalPreviewMapboxNavigationAPI::class)

package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.core.replay.route.ReplayRouteOptions
import com.mapbox.navigation.core.replay.route.ReplayRouteSessionOptions
import io.github.purplepalm.custommaps.nav.replay.ReplayPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The replay of a simulated drive, and where its slider stands, on the replay's own clock. */
class ReplayControlTest {

    @Test
    fun `a simulated drive's replay holds its whole route, with the SDK's driver otherwise`() {
        val options = wholeRouteReplay().getOptions()
        assertEquals(Double.MAX_VALUE, options.decodeMinDistance)
        // The rest as Mapbox has it: one position a second, its speeds, and the reset to the
        // phone's location when the routes are cleared.
        val defaults = ReplayRouteSessionOptions.Builder().build()
        assertEquals(ReplayRouteOptions.Builder().build(), options.replayRouteOptions)
        assertEquals(defaults.locationResetEnabled, options.locationResetEnabled)
        assertEquals(1.0, options.replayRouteOptions.frequency)
    }

    @Test
    fun `the slider counts from the first position the replayer holds`() {
        // The replayer holds 1.0 s to 601.0 s: 600 s of drive, played up to 151.0 s.
        assertEquals(ReplayPosition(seconds = 150.0, durationSeconds = 600.0, metres = 1_234.5), replayPosition(151.0, 1.0, 600.0, 1_234.5))
        assertEquals(0.25, replayPosition(151.0, 1.0, 600.0, null).fraction)
    }

    @Test
    fun `a position before the first held one, or past the end, is shown at the ends`() {
        // The very first position is played as the replayer drops it: it lies before the new first.
        assertEquals(0.0, replayPosition(0.0, 1.0, 600.0, null).seconds)
        assertEquals(600.0, replayPosition(700.0, 1.0, 600.0, null).seconds)
    }

    @Test
    fun `an empty replayer holds nothing`() {
        assertEquals(ReplayPosition(seconds = 0.0, durationSeconds = 0.0), replayPosition(5.0, 5.0, 0.0, null))
        assertEquals(ReplayPosition(seconds = 0.0, durationSeconds = 0.0), replayPosition(5.0, 5.0, -1.0, null))
    }
}
