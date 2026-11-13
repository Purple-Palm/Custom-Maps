package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Trip
import io.github.purplepalm.custommaps.car.routing.Drives.CHOICE_LANES
import io.github.purplepalm.custommaps.car.routing.Drives.NOW
import io.github.purplepalm.custommaps.car.routing.Drives.OFFICE
import io.github.purplepalm.custommaps.car.routing.Drives.RUN_UP
import io.github.purplepalm.custommaps.car.routing.Drives.WORDS
import io.github.purplepalm.custommaps.car.routing.Drives.towardsExit
import io.github.purplepalm.custommaps.nav.glyph.GlyphPart
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The library's routing card, trip and arrival from the car's routing content, built by the library
 * itself here; the app's images stand in as the library's own icons, as a bitmap needs a phone.
 */
class CarRoutingCardTest {

    private val arrows = mutableListOf<Pair<List<GlyphPart>, ArrowRole>>()
    private val rows = mutableListOf<LaneInfo>()
    private var lanesImage: CarIcon? = CarIcon.APP_ICON

    private val card = CarRoutingCard(
        arrow = { parts, role -> arrows += parts to role; CarIcon.APP_ICON },
        lanesImage = { lanes -> rows += lanes; lanesImage },
    )

    private fun routing(progress: RouteProgressSnapshot) = CarRouting.of(progress, OFFICE, NOW, WORDS)

    @Test
    fun `the routing card has the maneuver, its arrow, words and distance, and the lane row with its image`() {
        val info = card.routingInfo(routing(towardsExit(0.4)))
        assertTrue(!info.isLoading)
        val step = checkNotNull(info.currentStep)
        assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, step.maneuver?.type)
        assertNotNull(step.maneuver?.icon)
        assertEquals("Exit 24: Oak Street · Office", step.cue.toString())
        // The road alone is the variant for less room; the library keeps variants as Android text, which a JVM test cannot read.
        assertEquals(1, step.cue?.variants?.size)
        assertEquals(4, step.lanes.size)
        assertNotNull(step.lanesImage)
        assertEquals(0.4, info.currentDistance?.displayDistance)
        assertEquals(ArrowRole.NOW, arrows.single().second)
        assertNull(info.nextStep)
    }

    @Test
    fun `the lanes image is the phone card's row`() {
        val choice = towardsExit(1.2).copy(laneChoiceAhead = LanesAhead(Distance.miles(0.4), CHOICE_LANES))
        card.routingInfo(routing(choice))
        card.routingInfo(routing(choice.copy(runUpLanes = RUN_UP)))
        assertEquals(listOf(CHOICE_LANES, RUN_UP.lanes), rows)
    }

    @Test
    fun `lanes whose image could not be drawn are not sent at all`() {
        lanesImage = null
        val step = checkNotNull(card.routingInfo(routing(towardsExit(0.4))).currentStep)
        assertTrue(step.lanes.isEmpty())
        assertNull(step.lanesImage)
        val trip = card.trip(routing(towardsExit(0.4)))
        assertTrue(trip.steps.single().lanes.isEmpty())
    }

    @Test
    fun `the Then step is the card's next step, in the card's ink`() {
        val info = card.routingInfo(routing(towardsExit(0.2).copy(showThen = true)))
        val next = checkNotNull(info.nextStep)
        assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_RIGHT, next.maneuver?.type)
        assertEquals("Pine Street", next.cue.toString())
        assertTrue(next.lanes.isEmpty())
        assertEquals(listOf(ArrowRole.NOW, ArrowRole.THEN), arrows.map { it.second })
    }

    @Test
    fun `while the route is worked out the card loads, and before the first progress too`() {
        assertTrue(card.routingInfo(null).isLoading)
        val loading = card.routingInfo(routing(towardsExit(0.4).copy(offRoute = true)))
        assertTrue(loading.isLoading)
        assertNull(loading.currentStep)
        assertNull(card.destinationEstimate(null))
        assertEquals(12 * 60L, card.destinationEstimate(routing(towardsExit(0.4).copy(offRoute = true)))?.remainingTimeSeconds)
    }

    @Test
    fun `the trip has the card's steps and the destination, and a loading trip keeps the destination only`() {
        val trip = card.trip(routing(towardsExit(0.2).copy(showThen = true)))
        assertEquals(2, trip.steps.size)
        assertEquals(2, trip.stepTravelEstimates.size)
        assertEquals("Office", trip.destinations.single().name.toString())
        assertEquals(12 * 60L, trip.destinationTravelEstimates.single().remainingTimeSeconds)
        val loading = card.trip(routing(towardsExit(0.2).copy(pending = true)))
        assertTrue(loading.isLoading)
        assertTrue(loading.steps.isEmpty())
        assertEquals(1, loading.destinations.size)
    }

    @Test
    fun `the arrival says so with the destination's name and mark`() {
        val arrival = card.arrival("You have arrived", "Office")
        assertEquals("You have arrived", arrival.title.toString())
        assertEquals("Office", arrival.text.toString())
        assertNotNull(arrival.image)
        assertEquals(listOf(ArrowRole.NOW), arrows.map { it.second })
        assertNull(card.arrival("You have arrived", " ").text)
    }

    @Test
    fun `the library builds the card and the trip of any drive the app can have`() {
        val random = Random(2000)
        val turns = TurnModifier.entries + listOf<TurnModifier?>(null)
        repeat(3_000) { round ->
            fun maneuver() = Maneuver(
                ManeuverType.entries.random(random),
                turns.random(random),
                listOf("", "Oak Street", "Exit 3: Elm Street").random(random),
                listOf(null, "", "Gym").random(random),
                roundaboutExitNumber = listOf(null, 1, 4).random(random),
                roundaboutExitDegrees = listOf(null, 0.2, 90.0, 360.0, 412.0).random(random),
            )
            fun lanes(): LaneInfo? = if (random.nextInt(3) == 0) {
                null
            } else {
                LaneInfo(
                    List(1 + random.nextInt(9)) {
                        val lit = random.nextBoolean()
                        Lane(
                            turns.filterNotNull().shuffled(random).take(random.nextInt(3)).toSet(),
                            isRecommended = lit,
                            activeIndication = if (lit) turns.random(random) else null,
                            designated = random.nextInt(5) == 0,
                        )
                    },
                )
            }
            val progress = RouteProgressSnapshot(
                nextManeuver = maneuver(),
                distanceToNextManeuver = Distance.meters(random.nextDouble(0.0, 40_000.0)),
                lanes = lanes(),
                distanceRemaining = Distance.meters(random.nextDouble(0.0, 400_000.0)),
                durationRemaining = random.nextInt(-5, 100_000).seconds,
                durationToNextManeuver = random.nextInt(-5, 2_000).seconds,
                stepKey = "route/0/$round",
                following = if (random.nextBoolean()) FollowingManeuver(maneuver(), Distance.meters(random.nextDouble(0.0, 2_000.0)), random.nextInt(0, 90).seconds) else null,
                offRoute = random.nextInt(8) == 0,
                showThen = random.nextBoolean(),
                laneChoiceAhead = lanes()?.let { LanesAhead(Distance.meters(300.0), it) },
                pending = random.nextInt(8) == 0,
            )
            lanesImage = if (random.nextInt(4) == 0) null else CarIcon.APP_ICON
            val routing = routing(progress)
            assertDoesNotThrow<RoutingInfo>({ card.routingInfo(routing) }, "$progress")
            assertDoesNotThrow<Trip>({ card.trip(routing) }, "$progress")
            assertDoesNotThrow({ card.destinationEstimate(routing) }, "$progress")
        }
    }
}
