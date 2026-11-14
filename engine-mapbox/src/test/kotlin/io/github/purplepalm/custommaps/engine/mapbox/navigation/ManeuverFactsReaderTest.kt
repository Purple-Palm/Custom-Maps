package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import io.github.purplepalm.custommaps.nav.camera.FocusKind
import io.github.purplepalm.custommaps.nav.camera.FocusWhy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The facts of the maneuver at the end of a step, read from the route's steps for the view down onto
 * its junction. Made-up legs at 0, 0: each step begins at its maneuver's junction, the road to the
 * next maneuver passes one more intersection, and the car arrives from the south.
 */
class ManeuverFactsReaderTest {

    /**
     * The junction a step begins at: [bearings] of the roads that meet there, the car arriving by the
     * one at [arrives] (pointing back the way it came) and leaving by the one at [leaves].
     */
    private fun junction(bearings: List<Int>? = listOf(180, 0, 90, 270), arrives: Int? = 0, leaves: Int? = 1): StepIntersection =
        StepIntersection.builder()
            .rawLocation(doubleArrayOf(0.0, 0.0))
            .bearings(bearings)
            .entry(bearings?.map { true })
            .`in`(arrives)
            .out(leaves)
            .build()

    /** The intersection the road to the next maneuver passes last; a freeway where [freeway]. */
    private fun road(freeway: Boolean): StepIntersection = StepIntersection.builder()
        .rawLocation(doubleArrayOf(0.0, 0.0009))
        .bearings(listOf(180, 0))
        .entry(listOf(false, true))
        .`in`(0)
        .out(1)
        .classes(if (freeway) listOf("motorway") else null)
        .build()

    /** A step that begins with the maneuver [type] [modifier] at [at] and runs [meters] to the next maneuver. */
    private fun step(
        type: String,
        modifier: String?,
        meters: Double,
        at: StepIntersection = junction(),
        freeway: Boolean = false,
        before: Double? = null,
        after: Double? = null,
    ): LegStep = LegStep.builder()
        .maneuver(
            StepManeuver.builder().rawLocation(doubleArrayOf(0.0, 0.0)).type(type).modifier(modifier)
                .bearingBefore(before).bearingAfter(after).build(),
        )
        .distance(meters)
        .duration(meters / 13.4)
        .weight(meters / 13.4)
        .mode("driving")
        .intersections(listOf(at, road(freeway)))
        .build()

    private val depart = step("depart", null, 600.0, junction(listOf(0), arrives = null, leaves = 0))
    private val arrive = step("arrive", "right", 0.0)

    private fun facts(steps: List<LegStep>, stepIndex: Int = 0) = ManeuverFactsReader.factsOf("r", 0, steps, stepIndex)

    @Test
    fun `the facts are the next step's own maneuver, keyed by the step before it`() {
        // Depart, right onto Oak Street at a crossroads, arrive.
        val right = junction(listOf(180, 0, 90, 270), arrives = 0, leaves = 2)
        val leg = listOf(depart, step("turn", "right", 400.0, right), arrive)
        val first = requireNotNull(facts(leg, 0))
        assertEquals("r/0/0", first.stepKey)
        assertEquals(FocusKind.TURN, first.kind)
        assertNull(first.why)
        assertEquals(90.0, requireNotNull(first.turnDegrees), 1e-9)
        assertNull(first.next)
        // The arrival is a plain junction; past it there is no maneuver, and no step past the leg.
        assertEquals(FocusKind.TURN, requireNotNull(facts(leg, 1)).kind)
        assertEquals("r/0/1", requireNotNull(facts(leg, 1)).stepKey)
        assertNull(facts(leg, 2))
        assertNull(facts(leg, 3))
        assertNull(facts(leg, -1))
        assertNull(facts(emptyList(), 0))
    }

    @Test
    fun `the route's own maneuver counts, not the banner's word for it`() {
        // As the Directions API sends a leg: the banner calls the name change onto Pine Street a turn
        // straight on, and the ramp off Pine Street a slight right turn.
        val leg = RouteLeg.fromJson(
            """
            {"distance": 1500.0, "duration": 110.0, "steps": [
              {"distance": 500.0, "duration": 40.0, "weight": 40.0, "mode": "driving", "name": "Oak Street",
               "maneuver": {"location": [0.0, 0.0], "type": "depart"},
               "intersections": [{"location": [0.0, 0.0], "bearings": [0], "entry": [true], "out": 0}],
               "bannerInstructions": [{"distanceAlongGeometry": 500.0,
                 "primary": {"text": "Pine Street", "type": "turn", "modifier": "straight"}}]},
              {"distance": 400.0, "duration": 30.0, "weight": 30.0, "mode": "driving", "name": "Pine Street",
               "maneuver": {"location": [0.0, 0.0045], "type": "new name", "modifier": "straight"},
               "intersections": [{"location": [0.0, 0.0045], "bearings": [180, 0], "entry": [false, true], "in": 0, "out": 1}],
               "bannerInstructions": [{"distanceAlongGeometry": 400.0,
                 "primary": {"text": "Office Way", "type": "turn", "modifier": "slight right"}}]},
              {"distance": 600.0, "duration": 40.0, "weight": 40.0, "mode": "driving", "name": "Office Way",
               "maneuver": {"location": [0.0, 0.0081], "type": "on ramp", "modifier": "slight right"},
               "intersections": [{"location": [0.0, 0.0081], "bearings": [180, 0, 30], "entry": [false, true, true], "in": 0, "out": 2}],
               "bannerInstructions": [{"distanceAlongGeometry": 600.0,
                 "primary": {"text": "Office", "type": "arrive", "modifier": "right"}}]},
              {"distance": 0.0, "duration": 0.0, "weight": 0.0, "mode": "driving", "name": "Office Way",
               "maneuver": {"location": [0.003, 0.0128], "type": "arrive", "modifier": "right"},
               "intersections": [{"location": [0.003, 0.0128], "bearings": [210], "entry": [true], "in": 0}]}
            ]}
            """.trimIndent(),
        )
        val steps = leg.steps().orEmpty()
        assertEquals("turn", steps[0].bannerInstructions()?.first()?.primary()?.type())
        val nameChange = requireNotNull(ManeuverFactsReader.factsOf("r", 0, steps, 0))
        assertEquals(FocusKind.NONE, nameChange.kind)
        assertEquals("r/0/1", nameChange.nextKey)
        assertEquals(FocusKind.COMPLEX, nameChange.nextKind)
        assertEquals(400.0, nameChange.nextMeters, 1e-9)
        val ramp = requireNotNull(ManeuverFactsReader.factsOf("r", 0, steps, 1))
        assertEquals(FocusKind.COMPLEX, ramp.kind)
        assertEquals(FocusWhy.SPLIT, ramp.why)
        assertEquals(30.0, requireNotNull(ramp.turnDegrees), 1e-9)
    }

    @Test
    fun `a name change straight on keeps the view up and names the turn after it`() {
        // Oak Street becomes Pine Street, then left 100 m later.
        val leg = listOf(
            depart,
            step("new name", "straight", 100.0, junction(listOf(180, 0), arrives = 0, leaves = 1)),
            step("turn", "left", 300.0, junction(listOf(180, 0, 270), arrives = 0, leaves = 2)),
            arrive,
        )
        val nameChange = requireNotNull(facts(leg, 0))
        assertEquals(FocusKind.NONE, nameChange.kind)
        assertEquals(0.0, requireNotNull(nameChange.turnDegrees), 1e-9)
        assertEquals("r/0/1", nameChange.nextKey)
        assertEquals(FocusKind.TURN, nameChange.nextKind)
        assertEquals(100.0, nameChange.nextMeters, 1e-9)
        assertEquals(90.0, requireNotNull(nameChange.next?.turnDegrees), 1e-9)
        // The step that ends there frames the turn as its own.
        assertEquals(FocusKind.TURN, requireNotNull(facts(leg, 1)).kind)
        assertNull(requireNotNull(facts(leg, 1)).next)
        // A name change before another one names nothing: the view stays up for both.
        val twice = listOf(depart, step("new name", "straight", 100.0), step("new name", "straight", 300.0), arrive)
        assertNull(requireNotNull(facts(twice, 0)).next)
        assertEquals(Double.NaN, requireNotNull(facts(twice, 0)).nextMeters)
    }

    @Test
    fun `a ramp off a street is complex, the same off a freeway keeps the view up`() {
        val street = listOf(depart, step("on ramp", "slight right", 800.0), arrive)
        assertEquals(FocusKind.COMPLEX, requireNotNull(facts(street)).kind)
        assertEquals(FocusWhy.SPLIT, requireNotNull(facts(street)).why)
        // The road to the maneuver is a freeway by the last intersection it passes.
        val freeway = listOf(step("depart", null, 2000.0, junction(listOf(0), arrives = null, leaves = 0), freeway = true), step("off ramp", "slight right", 800.0), arrive)
        assertEquals(FocusKind.NONE, requireNotNull(facts(freeway)).kind)
        assertNull(requireNotNull(facts(freeway)).why)
        val fork = listOf(step("depart", null, 2000.0, junction(listOf(0), arrives = null, leaves = 0), freeway = true), step("fork", "left", 800.0), arrive)
        assertEquals(FocusKind.NONE, requireNotNull(facts(fork)).kind)
    }

    @Test
    fun `the way into a roundabout is complex, the way out a plain junction`() {
        val leg = listOf(depart, step("roundabout", "right", 60.0), step("exit roundabout", "right", 500.0), arrive)
        assertEquals(FocusKind.COMPLEX, requireNotNull(facts(leg, 0)).kind)
        assertEquals(FocusWhy.ROUNDABOUT, requireNotNull(facts(leg, 0)).why)
        assertEquals(FocusKind.TURN, requireNotNull(facts(leg, 1)).kind)
    }

    @Test
    fun `five roads at the junction make it complex, four do not`() {
        val four = listOf(depart, step("turn", "right", 400.0, junction(listOf(180, 0, 90, 270), leaves = 2)), arrive)
        assertEquals(FocusKind.TURN, requireNotNull(facts(four)).kind)
        val five = listOf(depart, step("turn", "right", 400.0, junction(listOf(180, 0, 60, 120, 270), leaves = 2)), arrive)
        assertEquals(FocusKind.COMPLEX, requireNotNull(facts(five)).kind)
        assertEquals(FocusWhy.MANY_ROADS, requireNotNull(facts(five)).why)
        // A sharp turn is complex at any junction.
        val sharp = listOf(depart, step("turn", "sharp left", 400.0, junction(listOf(180, 0, 200), leaves = 2)), arrive)
        assertEquals(FocusWhy.SHARP, requireNotNull(facts(sharp)).why)
    }

    @Test
    fun `the angle of the turn comes from the junction's bearings, else from the maneuver's`() {
        fun degrees(at: StepIntersection, before: Double? = null, after: Double? = null) =
            facts(listOf(depart, step("turn", "left", 400.0, at, before = before, after = after), arrive))?.turnDegrees
        assertEquals(90.0, requireNotNull(degrees(junction(listOf(180, 0, 90), leaves = 2))), 1e-9)
        assertEquals(90.0, requireNotNull(degrees(junction(listOf(180, 0, 270), leaves = 2))), 1e-9)
        assertEquals(0.0, requireNotNull(degrees(junction(listOf(180, 0), leaves = 1))), 1e-9)
        assertEquals(45.0, requireNotNull(degrees(junction(listOf(180, 45), leaves = 1))), 1e-9)
        // Back the way the car came.
        assertEquals(180.0, requireNotNull(degrees(junction(listOf(180, 0), leaves = 0))), 1e-9)
        // Arriving from the east, leaving to the north: a right angle across north.
        assertEquals(90.0, requireNotNull(degrees(junction(listOf(90, 0), arrives = 0, leaves = 1))), 1e-9)
        // Without the junction's way in or out: the maneuver's headings, across north as well.
        assertEquals(90.0, requireNotNull(degrees(junction(arrives = null), before = 350.0, after = 260.0)), 1e-9)
        assertEquals(30.0, requireNotNull(degrees(junction(bearings = null, arrives = null, leaves = null), before = 10.0, after = 40.0)), 1e-9)
        assertEquals(170.0, requireNotNull(degrees(junction(listOf(180, 0), leaves = 7), before = 0.0, after = 170.0)), 1e-9)
        assertNull(degrees(junction(arrives = null), before = 350.0))
        assertNull(degrees(junction(arrives = null)))
        assertNull(ManeuverFactsReader.turnDegrees(null, 10.0))
    }

    @Test
    fun `the short steps right after the maneuver count with its frame, up to the first long one`() {
        val leg = listOf(depart, step("turn", "right", 100.0), step("turn", "left", 120.0), step("turn", "right", 400.0), step("turn", "left", 90.0), arrive)
        assertEquals(220.0, requireNotNull(facts(leg, 0)).chainedMeters, 1e-9)
        assertEquals(120.0, requireNotNull(facts(leg, 1)).chainedMeters, 1e-9)
        assertEquals(0.0, requireNotNull(facts(leg, 2)).chainedMeters, 1e-9)
        // A step of exactly 150 m is still framed with it.
        assertEquals(150.0, ManeuverFactsReader.chainedMeters(listOf(depart, step("turn", "right", 150.0), step("turn", "left", 151.0)), 1), 1e-9)
        assertEquals(0.0, ManeuverFactsReader.chainedMeters(leg, leg.size), 1e-9)
    }

    @Test
    fun `the facts are read once a step and handed out again while the step lasts`() {
        val leg = listOf(depart, step("turn", "right", 400.0), step("turn", "left", 400.0), arrive)
        var reads = 0
        val reader = ManeuverFactsReader()
        val steps = { reads += 1; leg }
        val first = reader.facts("r", 0, 0, steps)
        assertSame(first, reader.facts("r", 0, 0, steps))
        assertEquals(1, reads)
        val second = reader.facts("r", 0, 1, steps)
        assertEquals("r/0/1", second?.stepKey)
        assertEquals(2, reads)
        // A new route with the same step index is a new step.
        assertEquals("q/0/1", reader.facts("q", 0, 1, steps)?.stepKey)
        assertEquals(3, reads)
    }

    @Test
    fun `a route whose steps cannot be read gives no facts and reports it once`() {
        val errors = mutableListOf<RuntimeException>()
        val reader = ManeuverFactsReader(onError = { errors += it })
        val broken = { throw IllegalStateException("no steps") }
        assertNull(reader.facts("r", 0, 0, broken))
        assertNull(reader.facts("r", 0, 0, broken))
        assertEquals(1, errors.size)
        // The next step reads again.
        val leg = listOf(depart, step("turn", "right", 400.0), step("turn", "left", 400.0), arrive)
        assertEquals(FocusKind.TURN, reader.facts("r", 0, 1) { leg }?.kind)
        assertEquals(1, errors.size)
    }
}
