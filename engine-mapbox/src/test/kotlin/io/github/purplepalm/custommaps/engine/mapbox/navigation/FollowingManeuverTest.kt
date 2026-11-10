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
        // A plain turn carries neither, whatever the step after it says.
        val turn = requireNotNull(followingManeuver(leg, stepIndex = 0))
        assertNull(turn.maneuver.roundaboutExitNumber)
        assertNull(turn.maneuver.roundaboutExitDegrees)
    }

    @Test
    fun `a step without a banner or a valid length gives nothing, a missing time gives zero`() {
        val noBanner = leg.toMutableList().also { it[1] = step("turn", "left", 150.0, 12.0, banner = null) }
        assertNull(followingManeuver(noBanner, stepIndex = 0))
        val noLength = leg.toMutableList().also { it[1] = step("turn", "left", Double.NaN, 12.0, banner("Pine Street", "turn", "right")) }
        assertNull(followingManeuver(noLength, stepIndex = 0))
        val endless = leg.toMutableList().also { it[1] = step("turn", "left", Double.POSITIVE_INFINITY, 12.0, banner("Pine Street", "turn", "right")) }
        assertNull(followingManeuver(endless, stepIndex = 0))
        val noTime = leg.toMutableList().also { it[1] = step("turn", "left", 150.0, Double.NaN, banner("Pine Street", "turn", "right")) }
        assertEquals(Duration.ZERO, requireNotNull(followingManeuver(noTime, stepIndex = 0)).durationAfterNext)
    }
}
