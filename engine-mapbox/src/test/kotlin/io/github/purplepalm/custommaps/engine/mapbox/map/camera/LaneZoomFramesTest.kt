package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.navigation.ui.maps.camera.data.FollowingFrameOptions
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The limits Mapbox tuned gives the data source before each frame, at example latitudes, and the
 * upper limit Smooth follow gives it, with the SDK's own options for when a maneuver is framed.
 */
class LaneZoomFramesTest {
    private val tuning = CameraTuning.PHONE
    private val sdkMin = LaneZoomFrames.SDK_MIN_ZOOM
    private val sdkLead = LaneZoomFrames.SDK_BEARING_LEAD

    /** The SDK's own options: a maneuver is framed within 180 m, but for the kinds it leaves the view up for. */
    private val near = FollowingFrameOptions.PitchNearManeuvers()
    private val trigger = near.triggerDistanceFromManeuver.toFloat()

    /** Smooth follow's upper limit for a progress with [metersToManeuver] left before a maneuver of [type]. */
    private fun smoothLimit(mode: LaneZoom, type: String?, metersToManeuver: Float): Double =
        LaneZoomFrames.smoothMaxZoom(mode, tuning, LaneZoomFrames.framesManeuver(type, metersToManeuver, near))

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

    @Test
    fun `smooth follow with large fits a frame of the road from the default limit`() {
        // The SDK's fit of a road frame starts its search at the upper limit, and from the raised
        // one it can end at the SDK's lowest zoom. So the road gets the limit Default gives it.
        assertTrue(tuning.maxZoom > tuning.defaultMaxZoom)
        assertEquals(tuning.defaultMaxZoom, smoothLimit(LaneZoom.LARGE, StepManeuver.TURN, 2_000f))
        assertEquals(tuning.defaultMaxZoom, smoothLimit(LaneZoom.LARGE, StepManeuver.TURN, trigger + 1f))
        // A maneuver the SDK leaves the view up for keeps the frame of the road all the way.
        assertEquals(tuning.defaultMaxZoom, smoothLimit(LaneZoom.LARGE, StepManeuver.OFF_RAMP, 50f))
        // Without a banner the SDK frames no maneuver.
        assertEquals(tuning.defaultMaxZoom, smoothLimit(LaneZoom.LARGE, null, 50f))
    }

    @Test
    fun `smooth follow with large lets the frame of a maneuver be fitted up to its closest zoom`() {
        assertEquals(tuning.maxZoom, smoothLimit(LaneZoom.LARGE, StepManeuver.TURN, trigger))
        assertEquals(tuning.maxZoom, smoothLimit(LaneZoom.LARGE, StepManeuver.ROUNDABOUT, 60f))
        assertEquals(tuning.maxZoom, smoothLimit(LaneZoom.LARGE, StepManeuver.ARRIVE, 0f))
    }

    @Test
    fun `smooth follow with default keeps the default limit whatever the drive does`() {
        for (meters in listOf(2_000f, trigger + 1f, trigger, 60f, 0f)) {
            for (type in listOf(StepManeuver.TURN, StepManeuver.OFF_RAMP, StepManeuver.ARRIVE, null)) {
                assertEquals(tuning.defaultMaxZoom, smoothLimit(LaneZoom.DEFAULT, type, meters))
            }
        }
        assertEquals(tuning.defaultMaxZoom, LaneZoomFrames.smoothMaxZoom(LaneZoom.DEFAULT, tuning, framesManeuver = true))
    }

    @Test
    fun `a maneuver is framed as the sdk frames it`() {
        // Its own rule: within the trigger distance, for every kind it does not leave out, and
        // only while that is switched on.
        assertTrue(LaneZoomFrames.framesManeuver(StepManeuver.TURN, trigger, near))
        assertFalse(LaneZoomFrames.framesManeuver(StepManeuver.TURN, trigger + 0.5f, near))
        for (type in near.excludedManeuvers) assertFalse(LaneZoomFrames.framesManeuver(type, 10f, near), type)
        assertFalse(LaneZoomFrames.framesManeuver(null, 10f, near))
        val off = FollowingFrameOptions.PitchNearManeuvers().apply { enabled = false }
        assertFalse(LaneZoomFrames.framesManeuver(StepManeuver.TURN, 10f, off))
        // The options are read each time: another distance and another list count at once.
        val wide = FollowingFrameOptions.PitchNearManeuvers().apply {
            triggerDistanceFromManeuver = 300.0
            excludedManeuvers = emptyList()
        }
        assertTrue(LaneZoomFrames.framesManeuver(StepManeuver.TURN, 300f, wide))
        assertTrue(LaneZoomFrames.framesManeuver(StepManeuver.FORK, 300f, wide))
    }

    @Test
    fun `smooth follow turns less toward the road ahead only with large, near a lane choice or onto a turn`() {
        for (laneWindow in listOf(false, true)) {
            for (focus in listOf(0.0, 0.5, 1.0)) {
                assertEquals(sdkLead, LaneZoomFrames.smoothBearingLead(LaneZoom.DEFAULT, tuning, laneWindow, focus))
            }
        }
        assertEquals(sdkLead, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuning, laneWindow = false, junctionFocus = 0.0))
        assertEquals(tuning.junctionMaxBearingLead, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuning, false, junctionFocus = 0.01))
        assertEquals(tuning.junctionMaxBearingLead, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuning, false, junctionFocus = 1.0))
        assertEquals(tuning.laneMaxBearingLead, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuning, laneWindow = true, junctionFocus = 0.0))
        assertEquals(20.0, tuning.junctionMaxBearingLead)
    }

    @Test
    fun `near a lane choice onto a turn the smaller of the two leads holds`() {
        val tuned = tuning.copy(laneMaxBearingLead = 15.0, junctionMaxBearingLead = 25.0)
        assertEquals(15.0, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuned, laneWindow = true, junctionFocus = 1.0))
        assertEquals(25.0, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuned, laneWindow = false, junctionFocus = 1.0))
    }
}
