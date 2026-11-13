package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.IntersectionLaneAccess
import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.MapboxStreetsV8
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.camera.LaneChoiceSkip
import io.github.purplepalm.custommaps.nav.camera.RunUpLatch
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.DivergeWatch
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A made-up freeway step: north from 0, 0 for about a kilometer, with a point every 0.0009 degrees. */
class LaneChoicesTest {
    private val tuning = CameraTuning.PHONE
    private val points = (0..10).map { Point.fromLngLat(0.0, it * 0.0009) }

    /** The length between two points of the step, about 100 m. */
    private val spacing = Geo.meters(0.0, 0.0, 0.0009, 0.0)

    private fun lane(
        valid: Boolean?,
        active: Boolean? = valid,
        designated: List<String>? = null,
        arrows: List<String> = listOf("straight"),
        used: String? = null,
    ): IntersectionLanes =
        IntersectionLanes.builder()
            .valid(valid)
            .active(active)
            .indications(arrows)
            .validIndication(used)
            .access(designated?.let { IntersectionLaneAccess.builder().designated(it).build() })
            .build()

    private fun intersection(
        at: Int,
        lanes: List<IntersectionLanes>? = null,
        freeway: Boolean = true,
        roadClass: String? = null,
    ): StepIntersection =
        StepIntersection.builder()
            .rawLocation(doubleArrayOf(points[at].longitude(), points[at].latitude()))
            .classes(if (freeway) listOf("motorway") else null)
            .mapboxStreetsV8(roadClass?.let { MapboxStreetsV8.builder().roadClass(it).build() })
            .lanes(lanes)
            .build()

    private fun loaded(intersections: List<StepIntersection?>, maneuver: StepIntersection? = null) =
        LaneChoices().apply { load("r/0/2", intersections, points, maneuver) }

    @Test
    fun `reads straight-on choices ahead of the car with their distance`() {
        val split = listOf(lane(true), lane(true), lane(true), lane(false), lane(false))
        val step = listOf(intersection(0), intersection(4, listOf(lane(true), lane(true), lane(true))), intersection(7, split))
        val choices = loaded(step).choices(metersOnStep = 250.0, metersToManeuver = 750.0, maneuverIsChoice = false)
        assertEquals(2, choices.size)
        val first = choices[0]
        assertEquals("r/0/2/1", first.key)
        assertEquals(4 * spacing - 250.0, first.metersAhead, 1e-6)
        assertEquals(LaneChoiceSkip.NO_LANE_LEAVES, first.skipReason(tuning), "every lane goes on")
        val second = choices[1]
        assertEquals("r/0/2/2", second.key)
        assertEquals(7 * spacing - 250.0, second.metersAhead, 1e-6)
        assertEquals(5, second.laneCount)
        assertEquals(3, second.usableLanes)
        assertEquals(2, second.leavingLanes)
        assertEquals(0, second.designatedLanes)
        assertTrue(second.onFreeway)
        assertFalse(second.atManeuver)
        assertTrue(second.counts(tuning))
    }

    @Test
    fun `skips choices behind the car and designated lanes`() {
        val withCarPool = listOf(lane(false, designated = listOf("hov")), lane(true), lane(true), lane(true))
        val step = listOf(intersection(0), intersection(2, withCarPool), intersection(5, withCarPool))
        val choices = loaded(step).choices(metersOnStep = 300.0, metersToManeuver = 700.0, maneuverIsChoice = false)
        assertEquals(1, choices.size, "the one at 200 m is behind")
        assertEquals("r/0/2/2", choices[0].key)
        assertEquals(3, choices[0].laneCount, "the car pool lane is not counted")
        assertEquals(1, choices[0].designatedLanes)
        assertEquals(3, choices[0].usableLanes)
        assertFalse(choices[0].counts(tuning))
    }

    @Test
    fun `a point the car is at is still ahead and the next update drops it`() {
        val split = listOf(lane(true), lane(true), lane(false), lane(false))
        val lanes = loaded(listOf(intersection(0), intersection(5, split)))
        val fromStart = lanes.choices(0.0, 1_000.0, false).single().metersAhead
        assertEquals(5 * spacing, fromStart, 1e-6)
        assertEquals(0.0, lanes.choices(fromStart, 500.0, false).single().metersAhead)
        assertTrue(lanes.choices(fromStart + 1.0, 499.0, false).isEmpty())
    }

    @Test
    fun `the next maneuver counts as a choice at the maneuver`() {
        val exit = listOf(lane(false), lane(false), lane(false), lane(true))
        val step = listOf(intersection(0), intersection(5))
        // The ramp itself is no freeway; the road that leads to it is.
        val ramp = intersection(10, exit, freeway = false)
        val lanes = loaded(step, maneuver = ramp)
        val atExit = lanes.choices(metersOnStep = 400.0, metersToManeuver = 600.0, maneuverIsChoice = true).single()
        assertEquals("r/0/2/end", atExit.key)
        assertTrue(atExit.atManeuver)
        assertEquals(600.0, atExit.metersAhead)
        assertEquals(4, atExit.laneCount)
        assertEquals(1, atExit.usableLanes)
        assertTrue(atExit.onFreeway, "the road leading to it is")
        assertTrue(atExit.counts(tuning))
        // Before a turn the view goes down onto the junction instead: its lanes are no lane choice.
        assertTrue(lanes.choices(metersOnStep = 400.0, metersToManeuver = 600.0, maneuverIsChoice = false).isEmpty())
    }

    @Test
    fun `the maneuver comes after the points of the step and one lane leaving is enough there`() {
        val oneLeaves = listOf(lane(true), lane(true), lane(true), lane(false))
        val step = listOf(intersection(0), intersection(6, oneLeaves))
        val choices = loaded(step, maneuver = intersection(10, oneLeaves)).choices(100.0, 900.0, maneuverIsChoice = true)
        assertEquals(listOf("r/0/2/1", "r/0/2/end"), choices.map { it.key })
        assertEquals(LaneChoiceSkip.TOO_FEW_LEAVING, choices[0].skipReason(tuning), "straight on, one lane leaves")
        assertNull(choices[1].skipReason(tuning))
    }

    @Test
    fun `which maneuvers are a lane choice where they have lanes`() {
        for (type in listOf("off ramp", "fork", "continue", "merge", "on ramp")) {
            assertTrue(LaneChoices.isLaneChoiceManeuver(type), type)
        }
        for (type in listOf("turn", "end of road", "roundabout", "rotary", "roundabout turn", "arrive", "depart", "new name", "", null)) {
            assertFalse(LaneChoices.isLaneChoiceManeuver(type), "$type")
        }
    }

    @Test
    fun `a road that is not a freeway never counts`() {
        val split = listOf(lane(true), lane(false), lane(false), lane(false))
        val step = listOf(intersection(0, freeway = false), intersection(5, split, freeway = false))
        val choice = loaded(step).choices(metersOnStep = 0.0, metersToManeuver = 1_000.0, maneuverIsChoice = false).single()
        assertFalse(choice.onFreeway)
        assertEquals(LaneChoiceSkip.NOT_A_FREEWAY, choice.skipReason(tuning))
    }

    @Test
    fun `the road that leads to a point decides, also by its road class`() {
        val split = listOf(lane(true), lane(false), lane(false))
        // The road changes at the second intersection: a trunk road up to it, a street after it.
        val step = listOf(
            intersection(0, freeway = false, roadClass = "trunk"),
            intersection(3, split, freeway = false, roadClass = "street"),
            intersection(6, split, freeway = false, roadClass = "motorway"),
            intersection(9, split, freeway = false),
        )
        val choices = loaded(step).choices(0.0, 1_000.0, false)
        assertEquals(listOf(true, false, true), choices.map { it.onFreeway })
    }

    @Test
    fun `the first intersection of a step is the maneuver it began with`() {
        val split = listOf(lane(true), lane(true), lane(false), lane(false))
        assertTrue(loaded(listOf(intersection(0, split))).choices(0.0, 1_000.0, false).isEmpty())
        // Its lanes are behind the car from the first meter of the step; the next point's are not.
        val errors = ArrayList<RuntimeException>()
        val lanes = LaneChoices { errors.add(it) }
        lanes.load("r/0/2", listOf(intersection(0, split), intersection(5, split)), points, maneuver = null)
        assertEquals(listOf("r/0/2/1"), lanes.choices(0.0, 1_000.0, false).map { it.key })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a lane that does not say whether it can be used counts by whether it is preferred`() {
        val unknown = listOf(lane(valid = null, active = true), lane(valid = null, active = false), lane(valid = null, active = null))
        val counts = requireNotNull(intersection(3, unknown).laneCounts())
        assertEquals(3, counts.lanes)
        assertEquals(1, counts.usable)
        assertEquals(1, counts.active)
        // Usable and preferred are two things: a lane the route can be followed from need not be the preferred one.
        val one = requireNotNull(intersection(3, listOf(lane(valid = true, active = false), lane(valid = true, active = true))).laneCounts())
        assertEquals(2, one.usable)
        assertEquals(1, one.active)
    }

    @Test
    fun `an intersection without lanes has no counts and one with only designated lanes has no lane to choose`() {
        assertNull(intersection(3).laneCounts())
        assertNull(intersection(3, emptyList()).laneCounts())
        val busOnly = requireNotNull(intersection(3, listOf(lane(true, designated = listOf("bus")))).laneCounts())
        assertEquals(0, busOnly.lanes)
        assertEquals(0, busOnly.usable)
        assertEquals(1, busOnly.designated)
    }

    @Test
    fun `a step is kept until the next one is loaded or the drive ends`() {
        val split = listOf(lane(true), lane(true), lane(false), lane(false))
        val lanes = loaded(listOf(intersection(0), intersection(5, split)), maneuver = intersection(10, split))
        assertEquals(2, lanes.choices(0.0, 1_000.0, true).size)
        lanes.load("r/0/3", listOf(intersection(0), intersection(8, split)), points, maneuver = null)
        assertEquals(listOf("r/0/3/1"), lanes.choices(0.0, 1_000.0, true).map { it.key })
        lanes.clear()
        assertTrue(lanes.choices(0.0, 1_000.0, true).isEmpty())
    }

    @Test
    fun `lane data that cannot be read costs the step its lane choices and is reported`() {
        val errors = ArrayList<RuntimeException>()
        val lanes = LaneChoices { errors.add(it) }
        val broken = object : AbstractList<StepIntersection?>() {
            override val size = 3

            override fun get(index: Int): StepIntersection? = throw IllegalStateException("unreadable")
        }
        lanes.load("r/0/2", broken, points, maneuver = null)
        assertTrue(lanes.choices(0.0, 1_000.0, true).isEmpty())
        assertEquals("unreadable", errors.single().message)
    }

    @Test
    fun `a step with a coordinate that is none has no lane choices`() {
        val split = listOf(lane(true), lane(true), lane(false), lane(false))
        val errors = ArrayList<RuntimeException>()
        val lanes = LaneChoices { errors.add(it) }
        val offTheGlobe = points + Point.fromLngLat(0.0, 95.0)
        lanes.load("r/0/2", listOf(intersection(0), intersection(5, split)), offTheGlobe, maneuver = null)
        assertTrue(lanes.choices(0.0, 1_000.0, false).isEmpty())
        assertTrue(errors.isEmpty())
    }

    /** A split at the eighth point of the step, where two of five lanes leave and a car pool lane runs on the left. */
    private val splitLanes = listOf(
        lane(true, designated = listOf("hov")),
        lane(true),
        lane(true, arrows = listOf("straight", "slight right"), used = "straight"),
        lane(false, arrows = listOf("slight right")),
        lane(false, arrows = listOf("slight right")),
    )

    /** The split loaded, and the choice it is with the car [metersAhead] before it. */
    private fun beforeSplit(lanes: LaneChoices, metersAhead: Double) =
        lanes.choices(0.0, 1_000.0, false).single { it.key == "r/0/2/2" }.copy(metersAhead = metersAhead)

    private fun split() = loaded(listOf(intersection(0), intersection(4), intersection(7, splitLanes)))

    @Test
    fun `the card shows the lanes of a choice from where its window starts`() {
        // At 29 m/s the window is 725 m.
        val inside = split()
        val shown = requireNotNull(inside.lanesAhead(listOf(beforeSplit(inside, 725.0)), speedMps = 29.0))
        assertEquals(Distance.meters(725.0), shown.distance)
        assertEquals(5, shown.lanes.lanes.size)
        val outside = split()
        assertNull(outside.lanesAhead(listOf(beforeSplit(outside, 725.5)), speedMps = 29.0))
        // A car that stands, or whose speed is unknown, gets the shortest window.
        assertNull(outside.lanesAhead(listOf(beforeSplit(outside, 400.5)), speedMps = null))
        assertEquals(Distance.meters(400.0), outside.lanesAhead(listOf(beforeSplit(outside, 400.0)), speedMps = 0.0)?.distance)
    }

    @Test
    fun `once shown the lanes stay until the car has passed the choice`() {
        val lanes = split()
        assertEquals(Distance.meters(700.0), lanes.lanesAhead(listOf(beforeSplit(lanes, 700.0)), 29.0)?.distance)
        // Braking to 10 m/s shortens the window to 400 m; the choice is still 650 m ahead.
        assertEquals(Distance.meters(650.0), lanes.lanesAhead(listOf(beforeSplit(lanes, 650.0)), 10.0)?.distance)
        // Passed: the choice is no longer ahead.
        assertNull(lanes.lanesAhead(emptyList(), 10.0))
        // What was shown is forgotten with it.
        assertNull(lanes.lanesAhead(listOf(beforeSplit(lanes, 650.0)), 10.0))
    }

    @Test
    fun `a distance that is no number shows nothing and ends nothing`() {
        val lanes = split()
        assertNull(lanes.lanesAhead(listOf(beforeSplit(lanes, Double.NaN)), 29.0))
        // Also for a choice the card already shows.
        assertEquals(Distance.meters(300.0), lanes.lanesAhead(listOf(beforeSplit(lanes, 300.0)), 29.0)?.distance)
        assertNull(lanes.lanesAhead(listOf(beforeSplit(lanes, Double.POSITIVE_INFINITY)), 29.0))
    }

    @Test
    fun `the lanes to use are lit and the leaving and designated ones are dim`() {
        val lanes = split()
        val shown = requireNotNull(lanes.lanesAhead(listOf(beforeSplit(lanes, 300.0)), 29.0)).lanes.lanes
        assertEquals(listOf(false, true, true, false, false), shown.map { it.isRecommended })
        assertEquals(listOf(null, TurnModifier.STRAIGHT, TurnModifier.STRAIGHT, null, null), shown.map { it.activeIndication })
        assertEquals(setOf(TurnModifier.STRAIGHT), shown[0].indications, "the car pool lane keeps its arrow, dim")
        assertEquals(listOf(true, false, false, false, false), shown.map { it.designated }, "and is drawn as one")
        assertEquals(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), shown[2].indications)
        assertEquals(setOf(TurnModifier.SLIGHT_RIGHT), shown[4].indications)
    }

    @Test
    fun `every lane the route can be followed from is lit, not only the preferred one`() {
        // As routes mark them: the lanes that go on are valid and one of them is the preferred one.
        val marked = listOf(
            lane(valid = true, active = false),
            lane(valid = true, active = true),
            lane(valid = null, active = true),
            lane(valid = false, active = true),
            lane(valid = false, active = false),
            lane(valid = null, active = false),
            lane(valid = null, active = null),
        )
        val shown = requireNotNull(intersection(3, marked).lanesAhead()).lanes
        assertEquals(listOf(true, true, true, false, false, false, false), shown.map { it.isRecommended })
        // The card's row for the same point, through the engine's cache.
        val lanes = loaded(listOf(intersection(0), intersection(7, marked)))
        val row = requireNotNull(lanes.lanesAhead(lanes.choices(0.0, 1_000.0, false), 29.0)).lanes.lanes
        assertEquals(shown, row)
    }

    @Test
    fun `only a lane choice where the route goes straight on gets the card's row`() {
        val oneLeaves = listOf(lane(true), lane(true), lane(true), lane(false))
        val lanes = loaded(listOf(intersection(0), intersection(3, oneLeaves), intersection(6, splitLanes)), maneuver = intersection(10, splitLanes))
        val ahead = lanes.choices(0.0, 1_000.0, maneuverIsChoice = true)
        assertEquals(listOf("r/0/2/1", "r/0/2/2", "r/0/2/end"), ahead.map { it.key })
        // The first is passed over by the rule; the second counts, 600 m ahead.
        assertEquals(Distance.meters(ahead[1].metersAhead), lanes.lanesAhead(ahead, 29.0)?.distance)
        // The maneuver's own lanes are the card's usual row: no lane choice ahead of the maneuver.
        assertTrue(ahead[2].counts(tuning))
        assertNull(lanes.lanesAhead(listOf(ahead[0], ahead[2]), 29.0))
        // On a road that is no freeway nothing counts.
        val street = loaded(listOf(intersection(0, freeway = false), intersection(6, splitLanes, freeway = false)))
        assertNull(street.lanesAhead(street.choices(0.0, 1_000.0, false), 29.0))
    }

    @Test
    fun `of two lane choices inside their window the card shows the nearer`() {
        val wide = splitLanes + lane(false, arrows = listOf("slight right"))
        val lanes = loaded(listOf(intersection(0), intersection(3, splitLanes), intersection(6, wide)))
        val ahead = lanes.choices(0.0, 1_000.0, false)
        assertEquals(2, ahead.size)
        assertEquals(5, lanes.lanesAhead(ahead, 29.0)?.lanes?.lanes?.size)
        assertEquals(5, lanes.lanesAhead(ahead.reversed(), 29.0)?.lanes?.lanes?.size)
        // Past the first one the second takes the row.
        assertEquals(6, lanes.lanesAhead(lanes.choices(400.0, 600.0, false), 29.0)?.lanes?.lanes?.size)
    }

    @Test
    fun `the card gets the same objects for as long as nothing changes`() {
        val lanes = split()
        val first = requireNotNull(lanes.lanesAhead(listOf(beforeSplit(lanes, 300.0)), 29.0))
        // A car that stands: the same distance, the same object.
        assertSame(first, lanes.lanesAhead(listOf(beforeSplit(lanes, 300.0)), 0.0))
        // A car that moves: a new distance, and the lanes are the same object still.
        val nearer = requireNotNull(lanes.lanesAhead(listOf(beforeSplit(lanes, 270.0)), 29.0))
        assertNotSame(first, nearer)
        assertEquals(Distance.meters(270.0), nearer.distance)
        assertSame(first.lanes, nearer.lanes)
    }

    @Test
    fun `the end of a drive forgets what the card showed`() {
        val lanes = split()
        val choice = beforeSplit(lanes, 650.0)
        assertEquals(Distance.meters(650.0), lanes.lanesAhead(listOf(choice), 29.0)?.distance)
        lanes.clear()
        // The same step again, for a slow car whose window is 400 m: the choice is outside it, and
        // it is not kept from the drive before.
        lanes.load("r/0/2", listOf(intersection(0), intersection(4), intersection(7, splitLanes)), points, maneuver = null)
        assertNull(lanes.lanesAhead(listOf(choice), 10.0))
        // And with nothing loaded there are no lanes to show at all.
        lanes.clear()
        assertNull(lanes.lanesAhead(listOf(choice), 29.0))
    }

    @Test
    fun `a point is reported once when it comes within the close view's start`() {
        val lanes = split()
        // At 29 m/s the window is 725 m.
        assertTrue(lanes.entering(listOf(beforeSplit(lanes, 726.0)), 29.0).isEmpty(), "still outside")
        assertEquals(listOf("r/0/2/2"), lanes.entering(listOf(beforeSplit(lanes, 725.0)), 29.0).map { it.key })
        assertTrue(lanes.entering(listOf(beforeSplit(lanes, 700.0)), 29.0).isEmpty(), "reported already")
        assertTrue(lanes.entering(emptyList(), 29.0).isEmpty())
    }

    @Test
    fun `the points passed over are reported too, each once, nearest first`() {
        val allGoOn = listOf(lane(true), lane(true), lane(true))
        val lanes = loaded(
            listOf(intersection(0), intersection(2, allGoOn), intersection(3, splitLanes)),
            maneuver = intersection(10, listOf(lane(false), lane(true))),
        )
        val ahead = lanes.choices(metersOnStep = 0.0, metersToManeuver = 380.0, maneuverIsChoice = true)
        // A standing car: the window is 400 m, and all three lie within it.
        val entered = lanes.entering(ahead, speedMps = null)
        assertEquals(listOf("r/0/2/1", "r/0/2/2", "r/0/2/end"), entered.map { it.key })
        assertEquals(
            listOf(LaneChoiceSkip.NO_LANE_LEAVES, null, LaneChoiceSkip.TOO_FEW_LANES),
            entered.map { it.skipReason(tuning) },
        )
        assertTrue(lanes.entering(ahead, speedMps = null).isEmpty())
    }

    @Test
    fun `a point behind the car or at a distance that is no number is not reported`() {
        val lanes = split()
        val choice = beforeSplit(lanes, 100.0)
        assertTrue(lanes.entering(listOf(choice.copy(metersAhead = -1.0)), 29.0).isEmpty())
        assertTrue(lanes.entering(listOf(choice.copy(metersAhead = Double.NaN)), 29.0).isEmpty())
        assertTrue(lanes.entering(listOf(choice.copy(metersAhead = Double.POSITIVE_INFINITY)), 29.0).isEmpty())
        // None of them used up the point's one report.
        assertEquals(1, lanes.entering(listOf(choice), 29.0).size)
    }

    @Test
    fun `a new step and a new drive report their points afresh`() {
        val lanes = split()
        assertEquals(1, lanes.entering(listOf(beforeSplit(lanes, 300.0)), 29.0).size)
        // The same step again after a reroute has the same keys only if the route is the same.
        lanes.load("r/0/2", listOf(intersection(0), intersection(4), intersection(7, splitLanes)), points, maneuver = null)
        assertEquals(1, lanes.entering(listOf(beforeSplit(lanes, 300.0)), 29.0).size)
        lanes.clear()
        lanes.load("r/0/2", listOf(intersection(0), intersection(4), intersection(7, splitLanes)), points, maneuver = null)
        assertEquals(1, lanes.entering(listOf(beforeSplit(lanes, 300.0)), 29.0).size)
    }

    /** Reads the usual split for whatever step is asked for, and counts how often. */
    private class StepLoader(private val lanes: LaneChoices, private val read: (String) -> Unit) : (String) -> Unit {
        var loads = 0
        val keys = ArrayList<String>()

        override fun invoke(stepKey: String) {
            loads++
            keys.add(stepKey)
            read(stepKey)
        }
    }

    private fun loaderOf(lanes: LaneChoices, maneuver: StepIntersection? = null) = StepLoader(lanes) { key ->
        lanes.load(key, listOf(intersection(0), intersection(4), intersection(7, splitLanes)), points, maneuver)
    }

    @Test
    fun `the lanes of a step are read once and each of its points is reported once`() {
        val lanes = LaneChoices()
        val loader = loaderOf(lanes)
        val first = lanes.update(offRoute = false, "r/0/2", metersOnStep = 0.0, metersToManeuver = 1_000.0, "turn", loader)
        assertEquals(listOf("r/0/2"), loader.keys)
        assertEquals(listOf("r/0/2/2"), lanes.entering(first, 29.0).map { it.key })
        // The next updates on the same step measure the distance again and read nothing.
        for (t in 1..5) {
            val next = lanes.update(offRoute = false, "r/0/2", metersOnStep = 29.0 * t, metersToManeuver = 1_000.0 - 29.0 * t, "turn", loader)
            assertEquals(7 * spacing - 29.0 * t, next.single().metersAhead, 1e-6)
            assertTrue(lanes.entering(next, 29.0).isEmpty(), "reported already, update $t")
        }
        assertEquals(1, loader.loads)
        // A new step, and the same step of a new route, are read anew and report their points afresh.
        lanes.update(offRoute = false, "r/0/3", 0.0, 1_000.0, "turn", loader)
        val rerouted = lanes.update(offRoute = false, "r2/0/3", 0.0, 1_000.0, "turn", loader)
        assertEquals(listOf("r/0/2", "r/0/3", "r2/0/3"), loader.keys)
        assertEquals(listOf("r2/0/3/2"), lanes.entering(rerouted, 29.0).map { it.key })
    }

    @Test
    fun `off the route there are no lane choices and the step stays as it was read`() {
        val lanes = LaneChoices()
        val loader = loaderOf(lanes)
        val before = lanes.update(offRoute = false, "r/0/2", 100.0, 900.0, "turn", loader)
        assertEquals(1, lanes.entering(before, 29.0).size)
        // Off the route the progress still names the step the car left, or any other.
        assertTrue(lanes.update(offRoute = true, "r/0/2", 100.0, 900.0, "turn", loader).isEmpty())
        assertTrue(lanes.update(offRoute = true, "r/0/5", 0.0, 400.0, "turn", loader).isEmpty())
        assertEquals(1, loader.loads, "nothing is read off the route")
        // Back on the route on the same step: the point is there again, and is not reported twice.
        val back = lanes.update(offRoute = false, "r/0/2", 150.0, 850.0, "turn", loader)
        assertEquals(listOf("r/0/2/2"), back.map { it.key })
        assertTrue(lanes.entering(back, 29.0).isEmpty())
        assertEquals(1, loader.loads)
    }

    @Test
    fun `the kind of the next maneuver decides at each update whether its lanes are a lane choice`() {
        val lanes = LaneChoices()
        val exit = listOf(lane(false), lane(false), lane(true))
        val loader = loaderOf(lanes, maneuver = intersection(10, exit))
        val keysFor = { type: String? -> lanes.update(offRoute = false, "r/0/2", 0.0, 1_000.0, type, loader).map { it.key } }
        assertEquals(listOf("r/0/2/2", "r/0/2/end"), keysFor("off ramp"))
        assertEquals(listOf("r/0/2/2"), keysFor("turn"))
        // While the navigator has no banner the maneuver is of no known kind.
        assertEquals(listOf("r/0/2/2"), keysFor(null))
        assertEquals(listOf("r/0/2/2", "r/0/2/end"), keysFor("fork"))
        assertEquals(1, loader.loads)
    }

    @Test
    fun `the log line of a lane choice has its counts, its key and its lanes, and no place`() {
        // One more lane goes on than at the usual split, so that no two of the counts are the same.
        val lanes = loaded(listOf(intersection(0), intersection(4), intersection(7, splitLanes + lane(true))))
        val fields = lanes.debugFields(beforeSplit(lanes, 612.4), speedMps = 29.04)
        assertEquals(
            mapOf(
                "kind" to "straight",
                "key" to "r/0/2/2",
                "metersAhead" to 612L,
                "window" to 726L,
                "lanes" to 5,
                "usable" to 3,
                "leaving" to 2,
                "designated" to 1,
                "freeway" to true,
                "speed" to 29.0,
                "edgeLeaves" to false,
                "fromLeft" to listOf(
                    mapOf("ind" to listOf("straight"), "valid" to true, "active" to true, "validInd" to null, "des" to listOf("hov")),
                    mapOf("ind" to listOf("straight"), "valid" to true, "active" to true, "validInd" to null),
                    mapOf("ind" to listOf("straight", "slight right"), "valid" to true, "active" to true, "validInd" to "straight"),
                    mapOf("ind" to listOf("slight right"), "valid" to false, "active" to false, "validInd" to null),
                    mapOf("ind" to listOf("slight right"), "valid" to false, "active" to false, "validInd" to null),
                    mapOf("ind" to listOf("straight"), "valid" to true, "active" to true, "validInd" to null),
                ),
            ),
            fields,
        )
    }

    @Test
    fun `each lane keeps the kinds of vehicle it is kept for, any other word as other, and the card one flag`() {
        val names = listOf("hov", "bus", "taxi", "motorcycle", "moped", "bicycle")
        val lanes = names.map { lane(false, designated = listOf(it)) } + listOf(
            lane(true),
            lane(true, designated = emptyList()),
            IntersectionLanes.builder().valid(true).indications(listOf("straight")).access(IntersectionLaneAccess.builder().build()).build(),
            lane(true, designated = listOf("bus", "taxi")),
            lane(false, designated = listOf("delivery")),
            lane(false, designated = listOf("HOV")),
            IntersectionLanes.builder()
                .valid(false)
                .indications(listOf("straight"))
                .access(IntersectionLaneAccess.builder().designated(listOf<String?>(null)).build())
                .build(),
        )
        val read = intersection(3, lanes).routeLanes()
        assertEquals(
            listOf(
                setOf(DesignatedKind.HOV),
                setOf(DesignatedKind.BUS),
                setOf(DesignatedKind.TAXI),
                setOf(DesignatedKind.MOTORCYCLE),
                setOf(DesignatedKind.MOPED),
                setOf(DesignatedKind.BICYCLE),
                emptySet(),
                emptySet(),
                emptySet(),
                setOf(DesignatedKind.BUS, DesignatedKind.TAXI),
                setOf(DesignatedKind.OTHER),
                setOf(DesignatedKind.OTHER),
                setOf(DesignatedKind.OTHER),
            ),
            read.map { it.kinds },
            "the route's own names, an open lane however it says so, and anything else as a kind the app does not know",
        )
        assertEquals(listOf(false, false, false, false, false, false, true, true, true, false, false, false, false), read.map { it.usable })
        // The card draws the same lanes dim, still from one flag a lane.
        val card = requireNotNull(intersection(3, lanes).lanesAhead()).lanes
        assertEquals(read.map { it.designated }, card.map { it.designated })
        assertEquals(10, requireNotNull(intersection(3, lanes).laneCounts()).designated)
    }

    @Test
    fun `the log gets every lane as the route lists it, what it leaves out as null, and the exit's lanes too`() {
        val odd = listOf(
            // No arrows, no marks: every field the route leaves out is null.
            IntersectionLanes.builder().build(),
            null,
            lane(valid = null, active = true, designated = emptyList()),
            lane(false, designated = listOf("bus", "taxi")),
        )
        val fromLeft = intersection(3).toBuilder().lanes(odd).build().fromLeftFields()
        assertEquals(
            listOf(
                mapOf("ind" to null, "valid" to null, "active" to null, "validInd" to null),
                mapOf("ind" to listOf("straight"), "valid" to null, "active" to true, "validInd" to null),
                mapOf("ind" to listOf("straight"), "valid" to false, "active" to false, "validInd" to null, "des" to listOf("bus", "taxi")),
            ),
            fromLeft,
            "a lane that is null is skipped, as the card and the counts skip it",
        )
        assertTrue(intersection(3).fromLeftFields().isEmpty(), "no lanes")
        // The exit's own point, and an edge point on the way to it.
        val lanes = LaneChoices()
        runUpStep(lanes, "r/0/2")
        val ahead = lanes.choices(0.0, 1_000.0, maneuverIsChoice = true)
        val atExit = lanes.debugFields(ahead.single { it.atManeuver }, speedMps = 25.0)
        assertEquals("r/0/2/end", atExit["key"])
        assertEquals(false, atExit["edgeLeaves"])
        assertEquals(7, (atExit["fromLeft"] as List<*>).size)
        assertEquals(mapOf("ind" to listOf("slight right"), "valid" to true, "active" to true, "validInd" to "slight right"), (atExit["fromLeft"] as List<*>)[5])
        val edge = lanes.debugFields(ahead.single { it.key == "r/0/2/2" }, speedMps = 25.0)
        assertEquals(true, edge["edgeLeaves"])
        assertEquals(listOf("hov"), ((edge["fromLeft"] as List<*>)[0] as Map<*, *>)["des"])
    }

    @Test
    fun `the log line of a point passed over says why, and one at the maneuver says so`() {
        val exit = listOf(lane(false), lane(true))
        val lanes = loaded(listOf(intersection(0), intersection(5)), maneuver = intersection(10, exit))
        val atExit = lanes.choices(metersOnStep = 700.0, metersToManeuver = 300.0, maneuverIsChoice = true).single()
        val fields = lanes.debugFields(atExit, speedMps = null)
        assertEquals("maneuver", fields["kind"])
        assertEquals(300L, fields["metersAhead"])
        assertEquals(400L, fields["window"], "a car whose speed is not known counts as standing")
        assertEquals(LaneChoiceSkip.TOO_FEW_LANES, fields["skipped"])
        assertFalse("speed" in fields)
    }

    @Test
    fun `the log gets the lanes of a step as the rule counts them`() {
        val split = listOf(lane(true), lane(true), lane(true, active = false), lane(false), lane(false, designated = listOf("hov")))
        val step = listOf(intersection(0), intersection(4), intersection(7, split, freeway = false))
        val fields = requireNotNull(laneIntersectionFields(step, points)).single()
        assertEquals(7 * spacing, fields["meters"] as Double, 0.5)
        assertEquals(fields["meters"], Math.rint(fields["meters"] as Double), "whole meters")
        assertEquals(4, fields["lanes"])
        assertEquals(3, fields["usable"])
        assertEquals(2, fields["active"])
        assertEquals(1, fields["designated"])
        assertNull(fields["classes"], "the road that leaves it has no class")
        assertEquals(true, fields["freeway"], "the road that leads to it is a freeway")
        assertEquals(setOf("meters", "lanes", "usable", "active", "designated", "classes", "freeway"), fields.keys)
    }

    @Test
    fun `the log names the classes of the road that leaves an intersection`() {
        val split = listOf(lane(true), lane(false))
        val step = listOf(intersection(0, freeway = false), intersection(2, split))
        val fields = requireNotNull(laneIntersectionFields(step, points)).single()
        assertEquals(listOf("motorway"), fields["classes"])
        assertEquals(false, fields["freeway"])
    }

    @Test
    fun `a step without lanes adds nothing to the log`() {
        assertNull(laneIntersectionFields(listOf(intersection(0), intersection(5)), points))
        assertNull(laneIntersectionFields(emptyList(), points))
    }

    // The run-up. A made-up freeway with a car pool lane on the left and five general lanes, and an
    // exit on the right whose own lanes, past two lanes that open for it, are the next step's first
    // intersection. All distances are synthetic; 25 m/s gives a window of 625 m and a reach of
    // 2,125 m.

    private val hov = lane(false, designated = listOf("hov"))

    /** A point about 400 m into the step where the right lane leaves: an edge point for a right exit. */
    private val rightLeaves = listOf(hov, lane(true), lane(true), lane(true), lane(true), lane(false, arrows = listOf("slight right")))

    /** A point where the left general lane leaves instead. */
    private val leftLeaves = listOf(hov, lane(false, arrows = listOf("slight left")), lane(true), lane(true), lane(true), lane(true))

    /** The exit's own lanes: the two new right lanes go on to the ramp. */
    private val exitLanes = listOf(hov, lane(false), lane(false), lane(false), lane(false), rampLane(), rampLane())

    private fun rampLane() = lane(true, arrows = listOf("slight right"), used = "slight right")

    private fun runUpStep(
        lanes: LaneChoices,
        key: String,
        modifier: String? = "slight right",
        exit: List<IntersectionLanes> = exitLanes,
        step: List<StepIntersection?> = listOf(intersection(0), intersection(2), intersection(4, rightLeaves)),
    ) = lanes.load(key, step, points, maneuver = intersection(10, exit, freeway = false), exitModifier = modifier)

    /** One update of a car [onStep] meters into the step, the exit [toExit] ahead, at 25 m/s, at [t] seconds. */
    private fun LaneChoices.at(onStep: Double, toExit: Double, t: Double, offRoute: Boolean = false, type: String? = "off ramp", key: String = "r/0/2"): RunUpLanes? {
        val choices = update(offRoute, key, onStep, toExit, type) { runUpStep(this, it) }
        return moveRunUp(choices, onRoute = !offRoute, metersOnStep = onStep, speedMps = 25.0, nowSeconds = t)
    }

    private fun RunUpLanes.lit() = lanes.lanes.map { it.isRecommended }

    @Test
    fun `a point where the lane at the exit's side leaves is an edge point, by the exit's own side`() {
        fun edgeLeaves(modifier: String?, exit: List<IntersectionLanes> = exitLanes): Map<String, Boolean> {
            val lanes = LaneChoices()
            val step = listOf(intersection(0), intersection(3, rightLeaves), intersection(6, leftLeaves))
            runUpStep(lanes, "r/0/2", modifier, exit, step)
            return lanes.choices(0.0, 1_000.0, maneuverIsChoice = true).associate { it.key to it.edgeLeaves }
        }
        assertEquals(mapOf("r/0/2/1" to true, "r/0/2/2" to false, "r/0/2/end" to false), edgeLeaves("slight right"))
        assertEquals(mapOf("r/0/2/1" to true, "r/0/2/2" to false, "r/0/2/end" to false), edgeLeaves("sharp right"))
        assertEquals(mapOf("r/0/2/1" to false, "r/0/2/2" to true, "r/0/2/end" to false), edgeLeaves("slight left"))
        // Straight on, the exit's lanes to use tell the side: here the two right ones.
        assertEquals(mapOf("r/0/2/1" to true, "r/0/2/2" to false, "r/0/2/end" to false), edgeLeaves("straight"))
        // Lanes to use in the middle say no side: no edge points.
        val middle = listOf(lane(false), lane(true), lane(false), lane(false))
        assertEquals(mapOf("r/0/2/1" to false, "r/0/2/2" to false, "r/0/2/end" to false), edgeLeaves(null, middle))
    }

    @Test
    fun `the run-up begins at its reach for an edge point ahead, and its row goes from that point to the exit`() {
        val lanes = LaneChoices()
        // Beyond the reach nothing begins, though the edge point lies ahead.
        assertNull(lanes.at(onStep = 0.0, toExit = 2_200.0, t = 0.0))
        assertNull(lanes.runUpKey)
        val first = requireNotNull(lanes.at(onStep = 0.0, toExit = 2_100.0, t = 1.0))
        assertEquals("r/0/2/end", lanes.runUpKey)
        assertEquals(RunUpLatch.EDGE, lanes.runUpStartKey)
        // The edge point's row: the second lane from the right lit, the car pool lane dim.
        assertEquals("r/0/2/2", first.key)
        assertEquals(listOf(false, false, false, false, true, false), first.lit())
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 1), first.target)
        assertNull(first.odd)
        assertEquals(1, first.need)
        assertTrue(first.lanes.lanes[0].designated)
        // The same row, the same object, while the car drives on to it.
        assertSame(first, lanes.at(onStep = 25.0, toExit = 2_075.0, t = 2.0))
        assertSame(first, lanes.at(onStep = 400.0, toExit = 1_700.0, t = 16.0))
        // Past it: the exit's own lanes, the two right lanes in words.
        val exit = requireNotNull(lanes.at(onStep = 450.0, toExit = 1_650.0, t = 18.0))
        assertEquals("r/0/2/end", exit.key)
        assertEquals(listOf(false, false, false, false, false, true, true), exit.lit())
        assertEquals(LaneTarget(LaneEdge.RIGHT, 2, 0), exit.target)
        assertEquals(TurnModifier.SLIGHT_RIGHT, exit.lanes.lanes[5].activeIndication)
        assertEquals(TurnModifier.SLIGHT_RIGHT, exit.lanes.lanes[6].activeIndication)
        assertTrue(exit.lanes.lanes[0].designated)
        assertFalse(exit.lanes.lanes[0].isRecommended)
        assertSame(exit, lanes.at(onStep = 900.0, toExit = 100.0, t = 50.0))
        // Past the gore the exit leaves the list: the run-up ends.
        assertNull(lanes.at(onStep = 0.0, toExit = 800.0, t = 54.0, type = "turn", key = "r/0/3"))
        assertNull(lanes.runUpKey)
        assertNull(lanes.runUpStartKey)
    }

    @Test
    fun `the run-up holds whatever the speed does and ends off the route, and a short time off begins it again`() {
        val lanes = LaneChoices()
        requireNotNull(lanes.at(onStep = 0.0, toExit = 1_500.0, t = 0.0))
        // Past the edge point the exit is beyond its own window: only the latch holds it.
        val held = requireNotNull(lanes.at(onStep = 450.0, toExit = 1_050.0, t = 18.0))
        val choices = lanes.update(false, "r/0/2", 460.0, 1_040.0, "off ramp") { error("loaded") }
        assertSame(held, lanes.moveRunUp(choices, onRoute = true, metersOnStep = 460.0, speedMps = 0.0, nowSeconds = 19.0), "standing")
        assertSame(held, lanes.moveRunUp(choices, onRoute = true, metersOnStep = 460.0, speedMps = null, nowSeconds = 20.0), "no speed")
        // Two updates off the route, then back: within 3 s, so it begins again on its reach alone.
        assertNull(lanes.at(onStep = 460.0, toExit = 1_040.0, t = 21.0, offRoute = true))
        assertNull(lanes.runUpKey)
        assertNull(lanes.at(onStep = 460.0, toExit = 1_040.0, t = 22.0, offRoute = true))
        assertEquals(held, lanes.at(onStep = 480.0, toExit = 1_020.0, t = 23.0))
        assertEquals(RunUpLatch.RESUME, lanes.runUpStartKey)
        // Six off: too long. The same place starts nothing now.
        for (t in 24..29) assertNull(lanes.at(onStep = 480.0, toExit = 1_020.0, t = t.toDouble(), offRoute = true))
        assertNull(lanes.at(onStep = 500.0, toExit = 1_000.0, t = 30.0))
        assertNull(lanes.runUpKey)
    }

    @Test
    fun `an update without the maneuver's type ends the run-up and the next one with it begins it again`() {
        val lanes = LaneChoices()
        requireNotNull(lanes.at(onStep = 0.0, toExit = 1_500.0, t = 0.0))
        val held = requireNotNull(lanes.at(onStep = 450.0, toExit = 1_050.0, t = 18.0))
        assertNull(lanes.at(onStep = 475.0, toExit = 1_025.0, t = 19.0, type = null))
        assertEquals(held, lanes.at(onStep = 500.0, toExit = 1_000.0, t = 20.0))
        assertEquals(RunUpLatch.RESUME, lanes.runUpStartKey)
    }

    @Test
    fun `a seek ends the run-up so that it can begin again, and a new drive forgets it`() {
        val lanes = LaneChoices()
        requireNotNull(lanes.at(onStep = 0.0, toExit = 1_500.0, t = 0.0))
        val held = requireNotNull(lanes.at(onStep = 450.0, toExit = 1_050.0, t = 18.0))
        lanes.onSeek(18.5)
        assertNull(lanes.runUpKey)
        assertTrue(lanes.choices(450.0, 1_050.0, true).isEmpty(), "the step is read again")
        // Landed on the same approach, past the edge point: it begins again on its reach alone.
        assertEquals(held, lanes.at(onStep = 460.0, toExit = 1_040.0, t = 19.0))
        assertEquals(RunUpLatch.RESUME, lanes.runUpStartKey)
        // A seek that lands where the latch would not begin, later than 3 s: nothing.
        lanes.onSeek(19.5)
        assertNull(lanes.at(onStep = 470.0, toExit = 1_030.0, t = 23.0))
        // A new drive forgets that one ended: the same place starts nothing.
        requireNotNull(lanes.at(onStep = 0.0, toExit = 1_500.0, t = 30.0))
        lanes.clear()
        assertNull(lanes.runUpKey)
        assertNull(lanes.at(onStep = 470.0, toExit = 1_030.0, t = 30.5))
    }

    @Test
    fun `the latch moves once for each update, whoever asks first`() {
        val lanes = LaneChoices()
        val update = Any()
        var moves = 0
        val move = {
            moves++
            lanes.at(onStep = 0.0, toExit = 1_500.0, t = moves.toDouble())
            Unit
        }
        val first = lanes.runUpOnce(update, move)
        assertSame(first, lanes.runUpOnce(update, move))
        assertEquals(1, moves)
        assertEquals("r/0/2/end", lanes.runUpKey)
        lanes.runUpOnce(Any(), move)
        assertEquals(2, moves)
        // After a seek the same update object moves it again.
        lanes.onSeek(10.0)
        lanes.runUpOnce(update, move)
        assertEquals(3, moves)
    }

    @Test
    fun `on a run-up the card shows its row and no lane choice ahead`() {
        // A split 600 m in where two lanes leave on the right: a lane choice, and an edge point.
        val split = listOf(hov, lane(true), lane(true), lane(true), lane(false), lane(false))
        val lanes = LaneChoices()
        val step = listOf(intersection(0), intersection(6, split))
        fun at(onStep: Double, toExit: Double, t: Double): Pair<RunUpLanes?, Distance?> {
            val choices = lanes.update(false, "r/0/2", onStep, toExit, "off ramp") { runUpStep(lanes, it, step = step) }
            val row = lanes.moveRunUp(choices, onRoute = true, metersOnStep = onStep, speedMps = 25.0, nowSeconds = t)
            return row to lanes.lanesAhead(choices, 25.0)?.distance
        }
        // The exit beyond the reach: the split's own row, with its distance.
        val before = at(onStep = 0.0, toExit = 2_500.0, t = 0.0)
        assertNull(before.first)
        assertEquals(Distance.meters(6 * spacing), before.second)
        // In reach: the run-up's row, and nothing of the choice.
        val on = at(onStep = 30.0, toExit = 2_100.0, t = 1.0)
        assertEquals("r/0/2/1", on.first?.key)
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 2), on.first?.target, "the third lane from the right")
        assertNull(on.second)
    }

    @Test
    fun `a road without lane data that leaves on the exit's side withholds the words until it is passed`() {
        // Three intersections without lanes: a road off to the right, one off to the left, and an
        // on ramp joining from the right, which may not be entered.
        fun crossing(at: Int, branch: Int, enter: Boolean) = intersection(at).toBuilder()
            .bearings(listOf(180, 0, branch))
            .entry(listOf(false, true, enter))
            .`in`(0)
            .out(1)
            .build()
        val offRight = crossing(2, branch = 30, enter = true)
        val offLeft = crossing(3, branch = 330, enter = true)
        val onRamp = crossing(5, branch = 160, enter = false)
        assertTrue(offRight.branchesOff(LaneEdge.RIGHT))
        assertFalse(offRight.branchesOff(LaneEdge.LEFT))
        assertTrue(offLeft.branchesOff(LaneEdge.LEFT))
        assertFalse(offLeft.branchesOff(LaneEdge.RIGHT))
        assertFalse(onRamp.branchesOff(LaneEdge.RIGHT))
        assertFalse(intersection(4).branchesOff(LaneEdge.RIGHT), "no bearings")
        // The road the car comes in on is no road off, even where the data lets it be entered.
        val bent = intersection(4).toBuilder().bearings(listOf(170, 0)).entry(listOf(true, true)).`in`(0).out(1).build()
        assertFalse(bent.branchesOff(LaneEdge.RIGHT))
        val step = listOf(intersection(0), offRight, offLeft, onRamp)
        assertArrayEquals(doubleArrayOf(2 * spacing), exitSideBranches(step, points, LaneEdge.RIGHT).map { it.metersFromStepStart }.toDoubleArray(), 1e-6)
        assertArrayEquals(doubleArrayOf(3 * spacing), exitSideBranches(step, points, LaneEdge.LEFT).map { it.metersFromStepStart }.toDoubleArray(), 1e-6)
        assertEquals(listOf(1), exitSideBranches(step, points, LaneEdge.RIGHT).map { it.index })
        // On a road that is no freeway such a road counts for nothing.
        val street = listOf(intersection(0, freeway = false), crossing(2, branch = 30, enter = true))
        assertEquals(0, exitSideBranches(street, points, LaneEdge.RIGHT).size)

        val lanes = LaneChoices()
        fun at(onStep: Double, toExit: Double, t: Double): RunUpLanes? {
            val choices = lanes.update(false, "r/0/2", onStep, toExit, "off ramp") { runUpStep(lanes, it, step = step) }
            return lanes.moveRunUp(choices, onRoute = true, metersOnStep = onStep, speedMps = 25.0, nowSeconds = t)
        }
        // The exit's own window opens the run-up; the road off to the right is still ahead.
        val withheld = requireNotNull(at(onStep = 100.0, toExit = 600.0, t = 0.0))
        // The debug log's watch of diverges gets it with its key, as long as it is ahead.
        val road = lanes.branchesAhead(metersOnStep = 100.0).single()
        assertEquals("r/0/2/1", road.key)
        assertEquals(2 * spacing - 100.0, road.metersAhead, 1e-6)
        assertEquals(emptyList<DivergeWatch.Branch>(), lanes.branchesAhead(metersOnStep = 2 * spacing + 1.0))
        assertNull(withheld.target)
        assertEquals(RunUpRule.ODD_UNKNOWN_BRANCH, withheld.odd)
        assertEquals(listOf(false, false, false, false, false, true, true), withheld.lit(), "lit as the data says")
        assertSame(withheld, at(onStep = 2 * spacing, toExit = 500.0, t = 1.0), "at the road itself")
        val passed = requireNotNull(at(onStep = 2 * spacing + 1.0, toExit = 499.0, t = 2.0))
        assertNull(passed.odd)
        assertEquals(LaneTarget(LaneEdge.RIGHT, 2, 0), passed.target)
    }

    // ------------------------------------------------------------------ the ramp bridge (D20)

    /** The split 260 m into the ramp after the exit's gore: a fork of two lanes, the left one the route's. */
    private val split = listOf(lane(true, arrows = listOf("slight left"), used = "slight left"), lane(false, arrows = listOf("slight right")))

    /** The ramp's planning speed. */
    private val rampMps = 21.0

    /** One move of the run-up's latch per update and what it gives: the card's row, the run-up's exit, the metres to the ramp's point. */
    private class RampDrive(val lanes: LaneChoices) {
        val out = ArrayList<Triple<RunUpLanes?, String?, Double>>()

        /**
         * One update, as the engine moves the latch: its own progress observer first, then the map's camera asking for the
         * same update ([LaneChoices.runUp]); both move it with the route's id, and only the first moves it.
         */
        fun move(choices: List<LaneChoice>, onStep: Double, routeMeters: Double, t: Double) {
            val update = Any()
            val engine = lanes.runUpOnce(update) {
                lanes.moveRunUp(choices, onRoute = true, metersOnStep = onStep, speedMps = 20.0, nowSeconds = t, routeMeters = routeMeters, routeId = "r")
            }
            val camera = lanes.runUpOnce(update) { error("the latch moves once an update") }
            assertSame(engine, camera)
            assertNull(lanes.lanesAhead(choices, 20.0), "no lane choice's row on a run-up or its ramp")
            out.add(Triple(engine, lanes.runUpKey, lanes.rampAhead))
        }
    }

    /**
     * The run-up to the exit whose gore lies 2,000 m along the route, with an update at each of [runUp] (metres into the step,
     * seconds), then the ramp step to its split, 260 m past the gore.
     */
    private fun RampDrive.toTheSplit(runUp: List<Pair<Double, Double>> = listOf(0.0 to 0.0, 1_990.0 to 80.0)): List<Triple<RunUpLanes?, String?, Double>> {
        for ((onStep, t) in runUp) {
            move(lanes.update(false, "r/0/2", onStep, 2_000.0 - onStep, "off ramp") { runUpStep(lanes, it) }, onStep, onStep, t)
        }
        for ((onStep, t) in listOf(20.0 to 81.0, 60.0 to 83.0, 240.0 to 92.0, 260.0 to 93.0)) {
            val choices = lanes.update(false, "r/0/3", onStep, 260.0 - onStep, "fork") { key ->
                lanes.load(key, listOf(intersection(0), intersection(1)), points, maneuver = intersection(3, split), exitModifier = "slight left")
            }
            move(choices, onStep, 2_000.0 + onStep, t)
        }
        return out
    }

    @Test
    fun `through the ramp the card shows the split's row from the gore on, and the latch lets go at the split`() {
        val asked = ArrayList<Pair<String, String>>()
        fun run(planHolds: Boolean): List<Triple<RunUpLanes?, String?, Double>> {
            // The lane plan of the route holds the run-up to its exit through the ramp to the split ([LanePlan.rampAfter]).
            val lanes = LaneChoices(
                rampOf = { routeId, exitKey ->
                    asked += routeId to exitKey
                    if (planHolds && routeId == "r" && exitKey == "r/0/2/end") LanePlan.RampHold(260.0, rampMps) else null
                },
            )
            return RampDrive(lanes).toTheSplit()
        }
        val held = run(planHolds = true)
        assertEquals("r/0/2/end", held[1].first?.key, "the exit's own row up to the gore")
        // From the gore on, the split's row: its left lane lit, in words; the run-up still the exit's, 240 m to go.
        val ramp = requireNotNull(held[2].first)
        assertEquals("r/0/3/end", ramp.key)
        assertEquals(listOf(true, false), ramp.lit())
        assertEquals(LaneTarget(LaneEdge.LEFT, 1, 0), ramp.target)
        assertEquals("r/0/2/end", held[2].second)
        assertEquals(240.0, held[2].third, 1e-9)
        // The same row, the same object, on the way to the split.
        assertSame(ramp, held[3].first)
        assertSame(ramp, held[4].first)
        assertEquals(20.0, held[4].third, 1e-9)
        // At the split the latch lets go, and the card's row with it.
        assertNull(held.last().first)
        assertNull(held.last().second)
        assertTrue(held.last().third.isNaN())
        // The plan was asked for the run-up on now, with the route's id, before each move of a run-up: never outside one.
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.all { it == ("r" to "r/0/2/end") }, "$asked")
        // Where the plan holds through no ramp, the run-up ends at the gore as before.
        val interim = run(planHolds = false)
        assertEquals("r/0/2/end", interim[1].first?.key)
        assertTrue(interim.drop(2).all { it.first == null && it.second == null && it.third.isNaN() })
    }

    @Test
    fun `a ramp the plan cannot give costs its hold and one error line, never the drive`() {
        val errors = ArrayList<RuntimeException>()
        var asked = 0
        val lanes = LaneChoices(
            onError = { errors += it },
            rampOf = { _, _ ->
                asked++
                throw IllegalStateException("no plan")
            },
        )
        // A run-up of four updates: the plan is asked before each move of it after the first, and once past the gore.
        val runUp = listOf(1_600.0 to 61.0, 1_700.0 to 66.0, 1_800.0 to 71.0, 1_990.0 to 80.0)
        val drive = RampDrive(lanes).toTheSplit(runUp)
        assertTrue(drive.take(4).all { it.first?.key == "r/0/2/end" }, "the run-up as before")
        assertTrue(drive.drop(4).all { it.first == null && it.second == null && it.third.isNaN() }, "it ends at the gore")
        assertEquals(4, asked)
        assertEquals(1, errors.size, "one error line in the drive")
        // A new drive reports it again.
        lanes.clear()
        RampDrive(lanes).toTheSplit(runUp)
        assertEquals(2, errors.size)
    }

    @Test
    fun `an exit whose side cannot be told shows its own lanes lit without words`() {
        val middle = listOf(lane(false), lane(true), lane(false), lane(false))
        val lanes = LaneChoices()
        val choices = lanes.update(false, "r/0/2", 0.0, 500.0, "fork") { runUpStep(lanes, it, modifier = null, exit = middle) }
        val row = requireNotNull(lanes.moveRunUp(choices, onRoute = true, metersOnStep = 0.0, speedMps = 25.0, nowSeconds = 0.0))
        assertEquals("r/0/2/end", row.key)
        assertEquals(listOf(false, true, false, false), row.lit())
        assertNull(row.target)
        assertNull(row.need)
    }

    @Test
    fun `no run-up before a turn, on a road that is no freeway or for an exit that does not count`() {
        val lanes = LaneChoices()
        assertNull(lanes.at(onStep = 0.0, toExit = 500.0, t = 0.0, type = "turn"))
        val street = LaneChoices()
        val choices = street.update(false, "r/0/2", 0.0, 500.0, "off ramp") {
            street.load(it, listOf(intersection(0, freeway = false), intersection(4, rightLeaves, freeway = false)), points, intersection(10, exitLanes, freeway = false), "slight right")
        }
        assertNull(street.moveRunUp(choices, onRoute = true, metersOnStep = 0.0, speedMps = 25.0, nowSeconds = 0.0))
        val twoLanes = LaneChoices()
        val few = twoLanes.update(false, "r/0/2", 0.0, 500.0, "off ramp") { runUpStep(twoLanes, it, exit = listOf(lane(false), lane(true))) }
        assertNull(twoLanes.moveRunUp(few, onRoute = true, metersOnStep = 0.0, speedMps = 25.0, nowSeconds = 0.0))
    }
}
