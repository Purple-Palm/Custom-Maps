package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.BannerText
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepManeuver
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The leg's maneuvers after the next one, for the list the phone shows while the car shows the map.
 * Synthetic legs: invented roads at a made-up origin, built as the Directions API sends them, as in
 * [FollowingManeuverTest].
 */
class UpcomingManeuversTest {

    /** A step that starts with the maneuver [type] [modifier] and whose banner describes the maneuver at its end. */
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

    private fun arrive(modifier: String? = null) = step("arrive", modifier, 0.0, 0.0, null)

    /**
     * Depart; left onto Oak Street; 150 m later right onto Pine Street; 300 m later left onto Elm
     * Street; 200 m later the arrival on the right.
     */
    private val plain = listOf(
        step("depart", null, 500.0, 40.0, banner("Oak Street", "turn", "left")),
        step("turn", "left", 150.0, 12.0, banner("Pine Street", "turn", "right")),
        step("turn", "right", 300.0, 25.0, banner("Elm Street", "turn", "left")),
        step("turn", "left", 200.0, 18.0, banner("Elm Street", "arrive", "right")),
        arrive("right"),
    )

    /**
     * Right onto Oak Street, into a roundabout 120 m later and out at its second exit onto Elm
     * Street, the way out a step of its own; right onto Pine Street 90 m after the way out, and the
     * arrival 300 m after that. [circle] is the route's word: "roundabout" or "rotary".
     */
    private fun withRoundabout(circle: String = "roundabout") = listOf(
        step("depart", null, 400.0, 30.0, banner("Oak Street", "turn", "right")),
        step("turn", "right", 120.0, 10.0, banner("Elm Street", circle, "straight", degrees = 180.0)),
        step(circle, "straight", 40.0, 6.0, banner("Elm Street", "exit $circle", "right"), exit = 2),
        step("exit $circle", "right", 90.0, 8.0, banner("Pine Street", "turn", "right"), exit = 2),
        step("turn", "right", 300.0, 25.0, banner("Pine Street", "arrive", "left")),
        arrive("left"),
    )

    @Test
    fun `three plain maneuvers after the next one, in route order, through the arrival`() {
        val upcoming = upcomingManeuvers(plain, stepIndex = 0)
        assertEquals(listOf(ManeuverType.TURN, ManeuverType.TURN, ManeuverType.ARRIVE), upcoming.map { it.maneuver.type })
        assertEquals(listOf(TurnModifier.RIGHT, TurnModifier.LEFT, TurnModifier.RIGHT), upcoming.map { it.maneuver.modifier })
        assertEquals(listOf("Pine Street", "Elm Street", "Elm Street"), upcoming.map { it.maneuver.primaryText })
    }

    @Test
    fun `each one's distance and time are counted from the next maneuver, summed along the way`() {
        val upcoming = upcomingManeuvers(plain, stepIndex = 0)
        assertEquals(listOf(150.0, 450.0, 650.0), upcoming.map { it.distanceAfterNext.meters })
        assertEquals(listOf(12.seconds, 37.seconds, 55.seconds), upcoming.map { it.durationAfterNext })
        // A step on, the next maneuver is the right turn: the sums start there.
        val later = upcomingManeuvers(plain, stepIndex = 1)
        assertEquals(listOf(300.0, 500.0), later.map { it.distanceAfterNext.meters })
        assertEquals(listOf(25.seconds, 43.seconds), later.map { it.durationAfterNext })
    }

    @Test
    fun `before the last maneuver only the arrival follows, and before the arrival nothing does`() {
        assertEquals(listOf(ManeuverType.ARRIVE), upcomingManeuvers(plain, stepIndex = 2).map { it.maneuver.type })
        assertEquals(Distance.meters(200.0), upcomingManeuvers(plain, stepIndex = 2).single().distanceAfterNext)
        assertEquals(emptyList<FollowingManeuver>(), upcomingManeuvers(plain, stepIndex = 3))
        // On the arrival's own step, past the end, and on a leg with no steps.
        assertEquals(emptyList<FollowingManeuver>(), upcomingManeuvers(plain, stepIndex = 4))
        assertEquals(emptyList<FollowingManeuver>(), upcomingManeuvers(plain, stepIndex = 9))
        assertEquals(emptyList<FollowingManeuver>(), upcomingManeuvers(emptyList(), stepIndex = 0))
    }

    @Test
    fun `a roundabout's way out is no item, and its length and time go to the maneuver after it`() {
        val upcoming = upcomingManeuvers(withRoundabout(), stepIndex = 0)
        assertEquals(listOf(ManeuverType.ROUNDABOUT, ManeuverType.TURN, ManeuverType.ARRIVE), upcoming.map { it.maneuver.type })
        // The roundabout keeps its exit number and angle, as the following maneuver does.
        assertEquals(2, upcoming[0].maneuver.roundaboutExitNumber)
        assertEquals(180.0, upcoming[0].maneuver.roundaboutExitDegrees)
        // The roundabout 120 m on; the turn after the way out 120 + 40 + 90 m on; the arrival 300 m later.
        assertEquals(listOf(120.0, 250.0, 550.0), upcoming.map { it.distanceAfterNext.meters })
        assertEquals(listOf(10.seconds, 24.seconds, 49.seconds), upcoming.map { it.durationAfterNext })
        // With the roundabout next, the way out is skipped from the first item on.
        val approaching = upcomingManeuvers(withRoundabout(), stepIndex = 1)
        assertEquals(listOf(ManeuverType.TURN, ManeuverType.ARRIVE), approaching.map { it.maneuver.type })
        assertEquals(listOf(130.0, 430.0), approaching.map { it.distanceAfterNext.meters })
        // Inside it the way out is the next maneuver, and the same turn follows, now from the way out.
        val inside = upcomingManeuvers(withRoundabout(), stepIndex = 2)
        assertEquals(listOf(90.0, 390.0), inside.map { it.distanceAfterNext.meters })
        // A rotary's way out is skipped the same way.
        assertEquals(
            listOf(ManeuverType.ROTARY, ManeuverType.TURN, ManeuverType.ARRIVE),
            upcomingManeuvers(withRoundabout("rotary"), stepIndex = 0).map { it.maneuver.type },
        )
    }

    @Test
    fun `a step without a banner ends the list there`() {
        val noBanner = plain.toMutableList().also { it[2] = step("turn", "right", 300.0, 25.0, banner = null) }
        // The left turn onto Elm Street is not known, and so is nothing after it.
        val upcoming = upcomingManeuvers(noBanner, stepIndex = 0)
        assertEquals(listOf("Pine Street"), upcoming.map { it.maneuver.primaryText })
        // The next maneuver itself without a banner after it: nothing.
        assertEquals(emptyList<FollowingManeuver>(), upcomingManeuvers(noBanner, stepIndex = 1))
        // Past it the list goes on from the step after.
        assertEquals(listOf(ManeuverType.ARRIVE), upcomingManeuvers(noBanner, stepIndex = 2).map { it.maneuver.type })
    }

    @Test
    fun `a length that is not a number ends the list there, since every distance after it would be wrong`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val badLength = plain.toMutableList().also { it[2] = step("turn", "right", bad, 25.0, banner("Elm Street", "turn", "left")) }
            assertEquals(listOf("Pine Street"), upcomingManeuvers(badLength, stepIndex = 0).map { it.maneuver.primaryText }, "length $bad")
        }
    }

    @Test
    fun `a time that is not known makes that time and every one after it unknown, and the list goes on`() {
        val noTime = plain.toMutableList().also { it[2] = step("turn", "right", 300.0, Double.NaN, banner("Elm Street", "turn", "left")) }
        val upcoming = upcomingManeuvers(noTime, stepIndex = 0)
        assertEquals(listOf(150.0, 450.0, 650.0), upcoming.map { it.distanceAfterNext.meters })
        assertEquals(listOf(12.seconds, Duration.ZERO, Duration.ZERO), upcoming.map { it.durationAfterNext })
    }

    @Test
    fun `the first maneuver is the one the following maneuver gives, on every leg and every step`() {
        val legs = listOf(
            plain,
            withRoundabout(),
            withRoundabout("rotary"),
            // The destination at a roundabout's way out: nothing follows the roundabout.
            withRoundabout().take(3) + arrive(),
            // An unknown time inside the roundabout or after its way out.
            withRoundabout().toMutableList().also { it[2] = step("roundabout", "straight", 40.0, Double.NaN, banner("Elm Street", "exit roundabout", "right"), exit = 2) },
            withRoundabout().toMutableList().also { it[3] = step("exit roundabout", "right", 90.0, 0.0, banner("Pine Street", "turn", "right"), exit = 2) },
            plain.toMutableList().also { it[1] = step("turn", "left", 150.0, 12.0, banner = null) },
            plain.toMutableList().also { it[2] = step("turn", "right", Double.NaN, 25.0, banner("Elm Street", "turn", "left")) },
            plain.toMutableList().also { it[1] = step("turn", "left", 150.0, Double.NaN, banner("Pine Street", "turn", "right")) },
            emptyList(),
        )
        for ((n, steps) in legs.withIndex()) {
            for (index in 0..steps.size + 1) {
                assertEquals(followingManeuver(steps, index), upcomingManeuvers(steps, index).firstOrNull(), "leg $n, step $index")
            }
        }
    }

    @Test
    fun `only the current leg counts, through its own arrival`() {
        // To a stop at Pine Street, then on to Elm Street.
        val toStop = listOf(
            step("depart", null, 500.0, 40.0, banner("Oak Street", "turn", "left")),
            step("turn", "left", 150.0, 12.0, banner("Pine Street", "arrive", "right")),
            arrive("right"),
        )
        val onward = listOf(
            step("depart", null, 100.0, 9.0, banner("Elm Street", "turn", "right")),
            step("turn", "right", 250.0, 20.0, banner("Elm Street", "arrive", "left")),
            arrive("left"),
        )
        val legs = listOf(leg(toStop), leg(onward))
        val maneuvers = UpcomingManeuvers()
        val first = maneuvers.of("route-a", legs, legIndex = 0, stepIndex = 0)
        assertEquals(listOf(ManeuverType.ARRIVE), first.map { it.maneuver.type })
        assertEquals("Pine Street", first.single().maneuver.primaryText)
        // On the second leg, its own maneuvers.
        val second = maneuvers.of("route-a", legs, legIndex = 1, stepIndex = 0)
        assertEquals(listOf(ManeuverType.ARRIVE), second.map { it.maneuver.type })
        assertEquals(Distance.meters(250.0), second.single().distanceAfterNext)
        // A leg the route does not have gives none.
        assertEquals(emptyList<FollowingManeuver>(), UpcomingManeuvers().of("route-a", legs, legIndex = 2, stepIndex = 0))
    }

    @Test
    fun `the same list for every update of one step, a new one at the next step and with a new route`() {
        val legs = listOf(leg(plain))
        val maneuvers = UpcomingManeuvers()
        val first = maneuvers.of("route-a", legs, legIndex = 0, stepIndex = 0)
        assertSame(first, maneuvers.of("route-a", legs, legIndex = 0, stepIndex = 0))
        assertSame(first, maneuvers.of("route-a", legs, legIndex = 0, stepIndex = 0))
        // The next step: a new list, without the maneuver passed.
        val next = maneuvers.of("route-a", legs, legIndex = 0, stepIndex = 1)
        assertNotSame(first, next)
        assertEquals(first.drop(1).map { it.maneuver }, next.map { it.maneuver })
        assertSame(next, maneuvers.of("route-a", legs, legIndex = 0, stepIndex = 1))
        // A new route, even on the same step numbers, is built again.
        val rerouted = maneuvers.of("route-b", legs, legIndex = 0, stepIndex = 1)
        assertNotSame(next, rerouted)
        assertEquals(next, rerouted)
        // A new drive starts over: the same step is built again.
        maneuvers.reset()
        val again = maneuvers.of("route-b", legs, legIndex = 0, stepIndex = 1)
        assertNotSame(rerouted, again)
        assertEquals(rerouted, again)
    }

    @Test
    fun `a leg that ends right after the next maneuver has nothing after it`() {
        val upcoming = UpcomingManeuvers().of("route-a", listOf(leg(plain)), legIndex = 0, stepIndex = 3)
        assertTrue(upcoming.isEmpty())
    }

    private fun leg(steps: List<LegStep>): RouteLeg = RouteLeg.builder().steps(steps).build()
}
