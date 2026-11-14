package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.ApproachKind
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.FocusKind
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.ViewInput
import io.github.purplepalm.custommaps.nav.geo.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The points ahead the drive views' planner comes down for, walked from made-up legs north from 0, 0:
 * a point of a step's shape every 0.0009 degrees, about 100 m, each step beginning at its maneuver.
 * The car is on step 0 of leg 0 of the route "r" unless a test says otherwise.
 */
class ViewPointsTest {
    private val tuning = CameraTuning.PHONE

    /** About 100 m: the length between two points of a step's shape. */
    private val spacing = Geo.meters(0.0, 0.0, 0.0009, 0.0)

    private fun at(index: Int): Point = Point.fromLngLat(0.0, index * 0.0009)

    /** A junction a step begins at, the car arriving from the south and leaving by [leaves] of four roads. */
    private fun junction(at: Int, leaves: Int = 1): StepIntersection = StepIntersection.builder()
        .rawLocation(doubleArrayOf(0.0, at * 0.0009))
        .bearings(listOf(180, 0, 90, 270))
        .entry(listOf(false, true, true, true))
        .`in`(0)
        .out(leaves)
        .build()

    /** A plain intersection along a step, on a freeway where [freeway], with [lanes] where it has any. */
    private fun road(at: Int, freeway: Boolean = false, lanes: List<IntersectionLanes>? = null): StepIntersection = StepIntersection.builder()
        .rawLocation(doubleArrayOf(0.0, at * 0.0009))
        .bearings(listOf(180, 0))
        .entry(listOf(false, true))
        .`in`(0)
        .out(1)
        .classes(if (freeway) listOf("motorway") else null)
        .lanes(lanes)
        .build()

    private fun lane(valid: Boolean): IntersectionLanes =
        IntersectionLanes.builder().valid(valid).active(valid).indications(listOf("straight")).build()

    /** Five lanes, two of them leaving the route: a lane choice that counts on a freeway where the route goes straight on. */
    private val split = listOf(lane(true), lane(true), lane(true), lane(false), lane(false))

    /** One step of a leg: [type] [modifier] at point [from], [length] points of shape long, through [roads]. */
    private class Step(val type: String, val modifier: String?, val from: Int, val length: Int, val roads: List<StepIntersection> = emptyList())

    /** The leg of [steps], each step's shape from its first point to the next step's, and the step after the last the arrival. */
    private inner class Leg(steps: List<Step>) {
        val shapes = HashMap<String, List<Point>>()
        val steps: List<LegStep> = steps.mapIndexed { i, s ->
            val name = "s$i"
            shapes[name] = (s.from..s.from + s.length).map(::at)
            val meters = s.length * spacing
            LegStep.builder()
                .name(name)
                .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(0.0, s.from * 0.0009)).type(s.type).modifier(s.modifier).build())
                .distance(meters)
                .duration(meters / 13.4)
                .weight(meters / 13.4)
                .mode("driving")
                .intersections(listOf(junction(s.from, leaves = if (s.modifier == "right") 2 else 1)) + s.roads)
                .build()
        }

        val shapeOf: (LegStep) -> List<Point> = { step -> shapes.getValue(requireNotNull(step.name())) }
    }

    /** Steps one after the other from point 0, each [lengths] points long, with these maneuvers; the first a departure. */
    private fun leg(vararg maneuvers: Triple<String, String?, Int>): Leg {
        var from = 0
        val steps = maneuvers.map { (type, modifier, length) -> Step(type, modifier, from, length).also { from += length } }
        return Leg(steps)
    }

    private fun input(
        leg: Leg,
        stepIndex: Int = 0,
        metersToManeuver: Double,
        secondsToManeuver: Double = metersToManeuver / 13.4,
        laneChoices: List<LaneChoice> = emptyList(),
        points: ViewPoints = ViewPoints(tuning),
        update: Any = Any(),
    ): ViewInput = points.ahead(
        update = update,
        nowSeconds = 12.0,
        routeId = "r",
        legIndex = 0,
        stepIndex = stepIndex,
        routeMeters = 100.0,
        offRoute = false,
        speedMps = 13.4,
        metersToManeuver = metersToManeuver,
        secondsToManeuver = secondsToManeuver,
        laneChoices = laneChoices,
        runUpKey = null,
        steps = { leg.steps },
        stepPoints = leg.shapeOf,
    )

    @Test
    fun `straight steps are walked past to the turn after them, with their metres and seconds`() {
        // Depart onto Oak Street, its name changes straight on, the road goes straight on again, then a
        // right turn onto Pine Street and the arrival.
        val leg = leg(
            Triple("depart", null, 5),
            Triple("new name", "straight", 4),
            Triple("continue", "straight", 3),
            Triple("turn", "right", 6),
            Triple("arrive", "right", 0),
        )
        val view = input(leg, metersToManeuver = 200.0, secondsToManeuver = 15.0)
        assertEquals(listOf("r/0/2", "r/0/3"), view.points.map { it.key })
        val turn = view.points[0]
        assertEquals(ApproachKind.TURN, turn.kind)
        assertEquals(200.0 + 7 * spacing, turn.metersAhead, 1e-6)
        assertEquals(15.0 + 7 * spacing / 13.4, turn.secondsAhead, 1e-6)
        // The arrival after it is the next point; nothing comes after the arrival.
        val arrival = view.points[1]
        assertEquals(ApproachKind.TURN, arrival.kind)
        assertEquals(200.0 + 13 * spacing, arrival.metersAhead, 1e-6)
        // What the update said is handed on as it is.
        assertEquals(12.0, view.nowSeconds)
        assertEquals("r", view.routeId)
        assertEquals(100.0, view.routeMeters)
        assertEquals(13.4, view.speedMps)
        assertFalse(view.offRoute)
    }

    @Test
    fun `the road that leads to a point is a freeway by the last intersection of its step`() {
        val freeway = Leg(listOf(Step("depart", null, 0, 6, listOf(road(3, freeway = true))), Step("off ramp", "right", 6, 4), Step("arrive", "right", 10, 0)))
        val exit = input(freeway, metersToManeuver = 300.0).points.first()
        assertEquals(ApproachKind.RAMP, exit.kind)
        assertTrue(exit.onFreeway)
        // On a street the same ramp has a street's road to it.
        val street = Leg(listOf(Step("depart", null, 0, 6, listOf(road(3))), Step("off ramp", "right", 6, 4), Step("arrive", "right", 10, 0)))
        assertFalse(input(street, metersToManeuver = 300.0).points.first().onFreeway)
    }

    @Test
    fun `a lane choice on the car's step nearer than the maneuver comes first, and only one that counts`() {
        val leg = leg(Triple("depart", null, 9), Triple("off ramp", "right", 4), Triple("arrive", "right", 0))
        val counted = LaneChoice("r/0/0/3", metersAhead = 300.0, laneCount = 5, usableLanes = 3, designatedLanes = 0, onFreeway = true, atManeuver = false)
        // One lane leaving where the route goes straight on is what nearly every exit looks like: no choice.
        val oneLeaving = counted.copy(key = "r/0/0/2", metersAhead = 150.0, usableLanes = 4)
        // The exit's own lanes are the exit's point.
        val atExit = counted.copy(key = "r/0/0/end", metersAhead = 800.0, atManeuver = true)
        val view = input(leg, metersToManeuver = 800.0, secondsToManeuver = 60.0, laneChoices = listOf(oneLeaving, counted, atExit))
        assertEquals(listOf("r/0/0/3", "r/0/0"), view.points.map { it.key })
        val choice = view.points[0]
        assertEquals(ApproachKind.LANE_CHOICE, choice.kind)
        assertEquals(FocusKind.NONE, choice.focus)
        assertEquals(300.0, choice.metersAhead)
        // Its seconds are the step's in proportion to the way to it.
        assertEquals(60.0 * 300.0 / 800.0, choice.secondsAhead, 1e-9)
    }

    @Test
    fun `the way out of a roundabout is a point of its own a few metres after the way in`() {
        val leg = Leg(listOf(Step("depart", null, 0, 6), Step("roundabout", "right", 6, 1), Step("exit roundabout", "right", 7, 5), Step("arrive", "right", 12, 0)))
        val view = input(leg, metersToManeuver = 400.0)
        assertEquals(listOf("r/0/0", "r/0/1"), view.points.map { it.key })
        assertEquals(ApproachKind.TURN, view.points[0].kind)
        assertEquals(ApproachKind.TURN, view.points[1].kind)
        assertEquals(spacing, view.points[1].metersAhead - view.points[0].metersAhead, 1e-6)
    }

    @Test
    fun `a freeway lane choice behind a straight continue step is a point, keyed as the lane choices key it`() {
        // A freeway step, the road goes straight on onto the next, which splits at its second intersection.
        val walkedRoads = listOf(road(10, freeway = true), road(13, freeway = true, lanes = split))
        val leg = Leg(listOf(Step("depart", null, 0, 8, listOf(road(4, freeway = true))), Step("continue", "straight", 8, 10, walkedRoads), Step("off ramp", "right", 18, 4), Step("arrive", "right", 22, 0)))
        val view = input(leg, metersToManeuver = 250.0)
        assertEquals(2, view.points.size)
        val choice = view.points[0]
        assertEquals(ApproachKind.LANE_CHOICE, choice.kind)
        assertTrue(choice.onFreeway)
        assertEquals(250.0 + 5 * spacing, choice.metersAhead, 1e-6)
        assertEquals("r/0/1", view.points[1].key)
        // Its key is the one the engine's lane choices give it once its step is the car's, so the planner keeps it.
        val step = leg.steps[1]
        val current = LaneChoices(tuning).apply { load("r/0/1", step.intersections().orEmpty(), leg.shapeOf(step), maneuver = null) }
            .choices(metersOnStep = 0.0, metersToManeuver = step.distance(), maneuverIsChoice = false)
        assertEquals(listOf(choice.key), current.filter { it.counts(tuning) }.map { it.key })
        assertEquals(5 * spacing, current.single { it.key == choice.key }.metersAhead, 1e-6)
    }

    @Test
    fun `each maneuver's focus is the camera's own kind of junction for its step`() {
        val leg = leg(
            Triple("depart", null, 3),
            Triple("turn", "right", 3),
            Triple("turn", "sharp left", 3),
            Triple("fork", "slight left", 3),
            Triple("roundabout", "right", 1),
            Triple("exit roundabout", "right", 3),
            Triple("arrive", "left", 0),
        )
        for (stepIndex in 0..5) {
            val point = input(leg, stepIndex = stepIndex, metersToManeuver = 100.0).points.first()
            assertEquals("r/0/$stepIndex", point.key)
            assertEquals(requireNotNull(ManeuverFactsReader.factsOf("r", 0, leg.steps, stepIndex)).kind, point.focus, "step $stepIndex")
        }
        // A right turn, a sharp turn, a fork off a street, a roundabout's way in, its way out.
        val kinds = (0..4).map { input(leg, stepIndex = it, metersToManeuver = 100.0).points.first().focus }
        assertEquals(listOf(FocusKind.TURN, FocusKind.COMPLEX, FocusKind.COMPLEX, FocusKind.COMPLEX, FocusKind.TURN), kinds)
    }

    @Test
    fun `the planner gets the next point and the one after it, no more`() {
        val leg = leg(
            Triple("depart", null, 3),
            Triple("turn", "right", 3),
            Triple("turn", "left", 3),
            Triple("turn", "right", 3),
            Triple("arrive", "left", 0),
        )
        val choice = LaneChoice("r/0/0/1", metersAhead = 50.0, laneCount = 5, usableLanes = 3, designatedLanes = 0, onFreeway = true, atManeuver = false)
        assertEquals(listOf("r/0/0", "r/0/1"), input(leg, metersToManeuver = 200.0).points.map { it.key })
        assertEquals(listOf("r/0/0/1", "r/0/0"), input(leg, metersToManeuver = 200.0, laneChoices = listOf(choice)).points.map { it.key })
    }

    @Test
    fun `the walk goes past at most twelve straight steps, and ends at the arrival`() {
        val straights = (1..13).map { Triple("new name", "straight", 1) }
        val far = leg(Triple("depart", null, 2), *straights.toTypedArray(), Triple("turn", "right", 2), Triple("arrive", "left", 0))
        assertTrue(input(far, metersToManeuver = 100.0).points.isEmpty())
        val near = leg(Triple("depart", null, 2), *straights.drop(1).toTypedArray(), Triple("turn", "right", 2), Triple("arrive", "left", 0))
        assertEquals(listOf("r/0/12", "r/0/13"), input(near, metersToManeuver = 100.0).points.map { it.key })
        // On the last step only the arrival is left.
        assertEquals(listOf("r/0/13"), input(near, stepIndex = 13, metersToManeuver = 100.0).points.map { it.key })
    }

    @Test
    fun `the steps are walked once per step, and one update gives one object to every caller`() {
        val leg = leg(Triple("depart", null, 3), Triple("turn", "right", 3), Triple("arrive", "left", 0))
        var reads = 0
        val points = ViewPoints(tuning)
        fun ask(update: Any, stepIndex: Int, meters: Double) = points.ahead(
            update, 0.0, "r", 0, stepIndex, 0.0, false, null, meters, 0.0, emptyList(), null,
            steps = {
                reads += 1
                leg.steps
            },
            stepPoints = leg.shapeOf,
        )
        val update = Any()
        val first = ask(update, 0, 250.0)
        assertSame(first, ask(update, 0, 250.0), "the phone's map and the car's get the same answer")
        val next = ask(Any(), 0, 240.0)
        assertNotSame(first, next)
        assertEquals(240.0, next.points.first().metersAhead)
        assertEquals(1, reads)
        ask(Any(), 1, 300.0)
        assertEquals(2, reads)
    }

    @Test
    fun `a route whose steps cannot be read gives the lane choices alone and one report`() {
        val errors = ArrayList<RuntimeException>()
        val points = ViewPoints(tuning) { errors += it }
        val choice = LaneChoice("r/0/0/1", metersAhead = 50.0, laneCount = 5, usableLanes = 3, designatedLanes = 0, onFreeway = true, atManeuver = false)
        repeat(3) {
            val view = points.ahead(Any(), 0.0, "r", 0, 0, 0.0, false, null, 300.0, 20.0, listOf(choice), "exit", { error("unreadable") }, { emptyList() })
            assertEquals(listOf("r/0/0/1"), view.points.map { it.key })
            assertEquals("exit", view.runUpKey)
        }
        assertEquals(1, errors.size)
    }
}
