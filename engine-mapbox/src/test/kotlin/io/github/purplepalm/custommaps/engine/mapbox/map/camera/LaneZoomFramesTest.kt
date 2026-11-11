package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.navigation.ui.maps.camera.data.FollowingFrameOptions
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LookAhead
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.camera.guidancePitch
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
    fun `smooth follow tilts toward the lanes only with large while it follows the car`() {
        var asked = 0
        fun share(mode: LaneZoom, following: Boolean) = LaneZoomFrames.laneShare(mode, following) {
            asked++
            0.8
        }
        assertEquals(0.8, share(LaneZoom.LARGE, following = true))
        assertEquals(1, asked)
        for ((mode, following) in listOf(LaneZoom.DEFAULT to true, LaneZoom.DEFAULT to false, LaneZoom.LARGE to false)) {
            assertEquals(0.0, share(mode, following), "$mode, following $following")
        }
        assertEquals(1, asked, "the lane focus is read only where it counts")
        // With no share the tilt is the one it always was: the road's, down onto a junction.
        for (junction in listOf(0.0, 0.5, 1.0)) {
            val plain = tuning.cruisePitch + (tuning.maneuverPitch - tuning.cruisePitch) * junction
            assertEquals(plain, guidancePitch(tuning, share(LaneZoom.DEFAULT, following = true), junction), 1e-9, "junction $junction")
            assertEquals(plain, guidancePitch(tuning, share(LaneZoom.LARGE, following = false), junction), 1e-9, "junction $junction")
        }
        assertEquals(tuning.lanePitch, guidancePitch(tuning, LaneZoomFrames.laneShare(LaneZoom.LARGE, following = true) { 1.0 }, 0.0), 1e-9)
    }

    // The lanes' look-ahead floor, at latitude 0 on a made-up screen: a map 2,400 px high at 3 px
    // per dp, its padding 838 px at the top (a card with a lane row) and 459 px at the bottom.

    private val height = 2_400.0
    private val top = 838.0
    private val bottom = 459.0
    private val ratio = 3.0

    /** The zoom where a lane is 14 dp at latitude 0: the levels below are what the cap gives up. */
    private val lanesAt0 = MapScale.zoomForLaneDp(14.0, 0.0)

    private fun cap(speed: Double?, pitch: Double = 45.0, paddingTop: Double = top, paddingBottom: Double = bottom) =
        LaneZoomFrames.lanesCap(tuning, speed, pitch, height, paddingTop, paddingBottom, ratio, latitude = 0.0)

    @Test
    fun `the lanes' floor is the closest zoom that still shows 12 s of road`() {
        for (speed in listOf(25.0, 29.0, 33.0)) {
            for (pitch in listOf(45.0, 55.0)) {
                val zoom = cap(speed, pitch)
                val meters = LookAhead.metersAhead(zoom, pitch, height, height - bottom, top, ratio, 0.0)
                assertEquals(speed * tuning.laneLookAheadSeconds, meters, 1e-6, "$speed m/s at $pitch")
            }
        }
        // At the SDK's tilt a lane is about 9 dp at 25 m/s and 7 dp at 33 m/s: 0.6 and 1 level out.
        assertEquals(-0.60, cap(25.0) - lanesAt0, 0.01)
        assertEquals(-1.00, cap(33.0) - lanesAt0, 0.01)
        // At the lanes' tilt 25 m/s keeps the 14 dp lanes.
        assertTrue(cap(25.0, pitch = tuning.lanePitch) > lanesAt0)
    }

    @Test
    fun `no speed, no padding or no map gives no floor`() {
        assertEquals(Double.POSITIVE_INFINITY, cap(null))
        assertEquals(Double.POSITIVE_INFINITY, cap(25.0, paddingTop = 0.0, paddingBottom = 0.0), "the padding not set yet")
        assertEquals(Double.POSITIVE_INFINITY, LaneZoomFrames.lanesCap(tuning, 25.0, 45.0, 0.0, top, bottom, ratio, 0.0), "the map not laid out")
        assertEquals(Double.POSITIVE_INFINITY, cap(25.0, paddingTop = height - bottom), "no map between the car and the card")
    }

    @Test
    fun `mapbox tuned near a lane choice goes no closer than the floor`() {
        val floor = cap(25.0)
        assertTrue(floor < lanesAt0)
        fun limits(framesManeuver: Boolean, laneWindow: Boolean, lanesCap: Double) =
            LaneZoomFrames.tunedLimits(LaneZoom.LARGE, tuning, framesManeuver, laneWindow, latitude = 0.0, lanesCap = lanesCap)
        assertLimits(floor, tuning.defaultMaxZoom.coerceAtLeast(floor), tuning.laneMaxBearingLead, limits(false, true, floor))
        // A floor above the lanes' zoom changes nothing; nor does none, or one that is no number.
        for (none in listOf(lanesAt0 + 0.5, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertLimits(lanesAt0, lanesAt0, tuning.laneMaxBearingLead, limits(false, true, none))
        }
        // Never under the SDK's own lower limit.
        assertLimits(sdkMin, tuning.defaultMaxZoom, tuning.laneMaxBearingLead, limits(false, true, sdkMin - 2.0))
        // The frame of a maneuver, and the road outside a lane choice, are as they were.
        val junction = MapScale.zoomForLaneDp(tuning.junctionLaneDp, 0.0)
        assertLimits(sdkMin, junction, tuning.laneMaxBearingLead, limits(true, true, floor))
        assertLimits(sdkMin, tuning.defaultMaxZoom, sdkLead, limits(false, false, floor))
        // With Default nothing moves, floor or not.
        assertLimits(sdkMin, tuning.defaultMaxZoom, sdkLead, LaneZoomFrames.tunedLimits(LaneZoom.DEFAULT, tuning, false, true, 0.0, floor))
    }

    @Test
    fun `near a lane choice onto a turn the smaller of the two leads holds`() {
        val tuned = tuning.copy(laneMaxBearingLead = 15.0, junctionMaxBearingLead = 25.0)
        assertEquals(15.0, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuned, laneWindow = true, junctionFocus = 1.0))
        assertEquals(25.0, LaneZoomFrames.smoothBearingLead(LaneZoom.LARGE, tuned, laneWindow = false, junctionFocus = 1.0))
    }
}
