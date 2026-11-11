package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.BannerText
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.StepManeuver
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Synthetic legs: invented roads at a made-up origin. */
class FollowingManeuverTest {

    /**
     * A step that starts with the maneuver [type] [modifier] and whose banner describes the
     * maneuver at its end, as the Directions API sends them.
     */
    private fun step(
        type: String,
        modifier: String?,
        meters: Double,
        seconds: Double,
        banner: BannerText?,
        exit: Int? = null,
    ): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(0.0, 0.0)).type(type).modifier(modifier).exit(exit).build())
        .distance(meters)
        .duration(seconds)
        .weight(seconds)
        .mode("driving")
        .apply { banner?.let { bannerInstructions(listOf(BannerInstructions.builder().distanceAlongGeometry(meters).primary(it).build())) } }
        .build()

    private fun banner(text: String, type: String, modifier: String?, degrees: Double? = null): BannerText =
        BannerText.builder().text(text).type(type).modifier(modifier).degrees(degrees).build()

    /** Depart, left onto Oak Street, right onto Pine Street 150 m later, arrive 300 m after that. */
    private val leg = listOf(
        step("depart", null, 500.0, 40.0, banner("Oak Street", "turn", "left")),
        step("turn", "left", 150.0, 12.0, banner("Pine Street", "turn", "right")),
        step("turn", "right", 300.0, 25.0, banner("Pine Street", "arrive", "right")),
        step("arrive", "right", 0.0, 0.0, null),
    )

    @Test
    fun `before the first turn the following maneuver is the second turn`() {
        val following = requireNotNull(followingManeuver(leg, stepIndex = 0))
        assertEquals(ManeuverType.TURN, following.maneuver.type)
        assertEquals(TurnModifier.RIGHT, following.maneuver.modifier)
        assertEquals("Pine Street", following.maneuver.primaryText)
        assertEquals(Distance.meters(150.0), following.distanceAfterNext)
        assertEquals(12.seconds, following.durationAfterNext)
    }

    @Test
    fun `before the last turn the following maneuver is the arrival`() {
        val following = requireNotNull(followingManeuver(leg, stepIndex = 1))
        assertEquals(ManeuverType.ARRIVE, following.maneuver.type)
        assertEquals(TurnModifier.RIGHT, following.maneuver.modifier)
        assertEquals(Distance.meters(300.0), following.distanceAfterNext)
    }

    @Test
    fun `nothing follows when the next maneuver is the arrival`() {
        assertNull(followingManeuver(leg, stepIndex = 2))
        assertNull(followingManeuver(leg, stepIndex = 3))
        assertNull(followingManeuver(emptyList(), stepIndex = 0))
    }

    @Test
    fun `a roundabout keeps its exit number and angle`() {
        val roundaboutLeg = listOf(
            step("depart", null, 400.0, 30.0, banner("Oak Street", "turn", "right")),
            step("turn", "right", 120.0, 10.0, banner("Pine Street", "roundabout", "left", degrees = 270.0)),
            step("roundabout", "left", 300.0, 25.0, banner("Elm Street", "arrive", null), exit = 3),
            step("arrive", null, 0.0, 0.0, null),
        )
        val following = requireNotNull(followingManeuver(roundaboutLeg, stepIndex = 0))
        assertEquals(ManeuverType.ROUNDABOUT, following.maneuver.type)
        assertEquals(3, following.maneuver.roundaboutExitNumber)
        assertEquals(270.0, following.maneuver.roundaboutExitDegrees)
        // An exit number of zero is no exit number, and must not end the drive.
        val zeroExit = roundaboutLeg.toMutableList().also {
            it[2] = step("roundabout", "left", 300.0, 25.0, banner("Elm Street", "arrive", null), exit = 0)
        }
        val unnumbered = requireNotNull(followingManeuver(zeroExit, stepIndex = 0))
        assertEquals(ManeuverType.ROUNDABOUT, unnumbered.maneuver.type)
        assertNull(unnumbered.maneuver.roundaboutExitNumber)
    }

    @Test
    fun `a plain turn carries no exit number and no angle, whatever the route says`() {
        // The turn's banner has an angle and the step after it an exit number, as only roundabouts should.
        val odd = leg.toMutableList().also {
            it[1] = step("turn", "left", 150.0, 12.0, banner("Pine Street", "turn", "right", degrees = 90.0))
            it[2] = step("turn", "right", 300.0, 25.0, banner("Pine Street", "arrive", "right"), exit = 2)
        }
        val turn = requireNotNull(followingManeuver(odd, stepIndex = 0))
        assertEquals(ManeuverType.TURN, turn.maneuver.type)
        assertNull(turn.maneuver.roundaboutExitNumber)
        assertNull(turn.maneuver.roundaboutExitDegrees)
    }

    /**
     * Along Oak Street into a roundabout, out at its second exit onto Elm Street, right onto Pine
     * Street 90 m later. The way out is a step of its own, as in the routes the app asks for.
     * [circle] is the route's word for it: "roundabout" or "rotary".
     */
    private fun through(circle: String) = listOf(
        step("depart", null, 400.0, 30.0, banner("Elm Street", circle, "straight", degrees = 180.0)),
        step(circle, "straight", 40.0, 6.0, banner("Elm Street", "exit $circle", "right"), exit = 2),
        step("exit $circle", "right", 90.0, 8.0, banner("Pine Street", "turn", "right"), exit = 2),
        step("turn", "right", 300.0, 25.0, banner("Pine Street", "arrive", "left")),
        step("arrive", "left", 0.0, 0.0, null),
    )

    private val throughRoundabout = through("roundabout")

    @Test
    fun `what follows a roundabout is the maneuver after its way out`() {
        val approaching = requireNotNull(followingManeuver(throughRoundabout, stepIndex = 0))
        assertEquals(ManeuverType.TURN, approaching.maneuver.type)
        assertEquals("Pine Street", approaching.maneuver.primaryText)
        // Counted from the way in: the roundabout itself and the road after it.
        assertEquals(Distance.meters(130.0), approaching.distanceAfterNext)
        assertEquals(14.seconds, approaching.durationAfterNext)
        // Inside the roundabout the same turn follows, now counted from the way out.
        val inside = requireNotNull(followingManeuver(throughRoundabout, stepIndex = 1))
        assertEquals(approaching.maneuver, inside.maneuver)
        assertEquals(Distance.meters(90.0), inside.distanceAfterNext)
        assertEquals(8.seconds, inside.durationAfterNext)
        // A rotary has a way out of its own too, and the same turn follows it.
        assertEquals(approaching, followingManeuver(through("rotary"), stepIndex = 0))
    }

    @Test
    fun `a roundabout with nothing after its way out, or with an unknown time, is handled`() {
        // The destination lies at the way out: the roundabout is followed by nothing.
        val endsAtExit = throughRoundabout.take(2) + step("arrive", null, 0.0, 0.0, null)
        assertNull(followingManeuver(endsAtExit, stepIndex = 0))
        // The destination is on the road after the way out.
        val endsAfterExit = throughRoundabout.take(2) +
            step("exit roundabout", "right", 90.0, 8.0, banner("Elm Street", "arrive", "right"), exit = 2) +
            step("arrive", "right", 0.0, 0.0, null)
        val destination = requireNotNull(followingManeuver(endsAfterExit, stepIndex = 0))
        assertEquals(ManeuverType.ARRIVE, destination.maneuver.type)
        assertEquals(Distance.meters(130.0), destination.distanceAfterNext)
        // One of the two times is unknown: so is their sum.
        val noTime = throughRoundabout.toMutableList().also {
            it[1] = step("roundabout", "straight", 40.0, Double.NaN, banner("Elm Street", "exit roundabout", "right"), exit = 2)
        }
        val following = requireNotNull(followingManeuver(noTime, stepIndex = 0))
        assertEquals(Distance.meters(130.0), following.distanceAfterNext)
        assertEquals(Duration.ZERO, following.durationAfterNext)
        // The same when it is the time after the way out that is unknown.
        val noTimeAfter = throughRoundabout.toMutableList().also {
            it[2] = step("exit roundabout", "right", 90.0, 0.0, banner("Pine Street", "turn", "right"), exit = 2)
        }
        val followingAfter = requireNotNull(followingManeuver(noTimeAfter, stepIndex = 0))
        assertEquals(Distance.meters(130.0), followingAfter.distanceAfterNext)
        assertEquals(Duration.ZERO, followingAfter.durationAfterNext)
    }

    @Test
    fun `a step without a banner or a valid length gives nothing, a missing time gives zero`() {
        val noBanner = leg.toMutableList().also { it[1] = step("turn", "left", 150.0, 12.0, banner = null) }
        assertNull(followingManeuver(noBanner, stepIndex = 0))
        val noLength = leg.toMutableList().also { it[1] = step("turn", "left", Double.NaN, 12.0, banner("Pine Street", "turn", "right")) }
        assertNull(followingManeuver(noLength, stepIndex = 0))
        val endless = leg.toMutableList().also { it[1] = step("turn", "left", Double.POSITIVE_INFINITY, 12.0, banner("Pine Street", "turn", "right")) }
        assertNull(followingManeuver(endless, stepIndex = 0))
        val negative = leg.toMutableList().also { it[1] = step("turn", "left", -1.0, 12.0, banner("Pine Street", "turn", "right")) }
        assertNull(followingManeuver(negative, stepIndex = 0))
        val noTime = leg.toMutableList().also { it[1] = step("turn", "left", 150.0, Double.NaN, banner("Pine Street", "turn", "right")) }
        assertEquals(Duration.ZERO, requireNotNull(followingManeuver(noTime, stepIndex = 0)).durationAfterNext)
    }

    @Test
    fun `a new route's card names the maneuver its step ends with, as the route has it`() {
        val next = requireNotNull(nextManeuverOf(leg, stepIndex = 0))
        assertEquals(ManeuverType.TURN, next.type)
        assertEquals(TurnModifier.LEFT, next.modifier)
        assertEquals("Oak Street", next.primaryText)
        assertEquals(ManeuverType.ARRIVE, nextManeuverOf(leg, stepIndex = 2)?.type)
        // The arrival's own step ends with nothing.
        assertNull(nextManeuverOf(leg, stepIndex = 3))
        assertNull(nextManeuverOf(emptyList(), stepIndex = 0))
    }
}
