package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The limits Mapbox tuned gives the data source before each frame, at example latitudes. */
class LaneZoomFramesTest {
    private val tuning = CameraTuning.PHONE
    private val sdkMin = LaneZoomFrames.SDK_MIN_ZOOM
    private val sdkLead = LaneZoomFrames.SDK_BEARING_LEAD

    private fun limits(mode: LaneZoom, framesManeuver: Boolean, laneWindow: Boolean, latitude: Double = 35.0) =
        LaneZoomFrames.tunedLimits(mode, tuning, framesManeuver, laneWindow, latitude)

    private fun assertLimits(minZoom: Double, maxZoom: Double, bearingLead: Double, actual: LaneZoomFrames.Limits) {
        assertEquals(minZoom, actual.minZoom, 1e-9, "the lower limit")
        assertEquals(maxZoom, actual.maxZoom, 1e-9, "the upper limit")
        assertEquals(bearingLead, actual.bearingLead, "the bearing lead")
    }

    @Test
    fun `default keeps its limits whatever the drive does`() {
        for (framesManeuver in listOf(false, true)) {
            for (laneWindow in listOf(false, true)) {
                assertLimits(sdkMin, tuning.defaultMaxZoom, sdkLead, limits(LaneZoom.DEFAULT, framesManeuver, laneWindow))
            }
        }
    }

    @Test
    fun `large on the open road has the default limits`() {
        assertLimits(sdkMin, tuning.defaultMaxZoom, sdkLead, limits(LaneZoom.LARGE, framesManeuver = false, laneWindow = false))
    }

    @Test
    fun `large lets the frame of a maneuver be fitted until a lane has its junction width`() {
        val junction = MapScale.zoomForLaneDp(tuning.junctionLaneDp, 35.0)
        assertTrue(junction > tuning.defaultMaxZoom)
        assertLimits(sdkMin, junction, sdkLead, limits(LaneZoom.LARGE, framesManeuver = true, laneWindow = false))
        // The same width is another zoom at another latitude.
        assertEquals(MapScale.zoomForLaneDp(tuning.junctionLaneDp, 45.0), limits(LaneZoom.LARGE, true, false, latitude = 45.0).maxZoom, 1e-9)
    }

    @Test
    fun `large near a lane choice holds the frame of the road at the lane width and turns less`() {
        val lanes = MapScale.zoomForLaneDp(tuning.freewayLaneDp, 35.0)
        // The upper limit comes up with the lower one: the SDK takes the lower limit last.
        assertTrue(lanes > tuning.defaultMaxZoom)
        assertLimits(lanes, lanes, tuning.laneMaxBearingLead, limits(LaneZoom.LARGE, framesManeuver = false, laneWindow = true))
    }

    @Test
    fun `the frame of a maneuver keeps the lower limit also while a lane choice holds the view`() {
        // That frame is fitted around the car and the junction: a lower limit above its fit would
        // push both off the map. The view still turns less.
        val junction = MapScale.zoomForLaneDp(tuning.junctionLaneDp, 35.0)
        assertLimits(sdkMin, junction, tuning.laneMaxBearingLead, limits(LaneZoom.LARGE, framesManeuver = true, laneWindow = true))
    }

    @Test
    fun `large never asks for more than its closest zoom`() {
        val close = tuning.copy(maxZoom = 17.0)
        val window = LaneZoomFrames.tunedLimits(LaneZoom.LARGE, close, framesManeuver = false, laneWindow = true, latitude = 35.0)
        assertLimits(17.0, 17.0, tuning.laneMaxBearingLead, window)
        assertEquals(17.0, LaneZoomFrames.tunedLimits(LaneZoom.LARGE, close, framesManeuver = true, laneWindow = false, latitude = 35.0).maxZoom)
    }

    @Test
    fun `a latitude that is not a number moves no limit`() {
        assertLimits(sdkMin, tuning.defaultMaxZoom, tuning.laneMaxBearingLead, limits(LaneZoom.LARGE, false, true, latitude = Double.NaN))
        assertLimits(sdkMin, tuning.defaultMaxZoom, sdkLead, limits(LaneZoom.LARGE, true, false, latitude = Double.NaN))
    }
}
