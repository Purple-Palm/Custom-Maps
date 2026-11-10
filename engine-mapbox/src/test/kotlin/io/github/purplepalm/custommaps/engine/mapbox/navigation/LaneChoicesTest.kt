package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.IntersectionLaneAccess
import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.MapboxStreetsV8
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneChoiceSkip
import io.github.purplepalm.custommaps.nav.geo.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A made-up freeway step: north from 0, 0 for about a kilometer, with a point every 0.0009 degrees. */
class LaneChoicesTest {
    private val tuning = CameraTuning.PHONE
    private val points = (0..10).map { Point.fromLngLat(0.0, it * 0.0009) }

    /** The length between two points of the step, about 100 m. */
    private val spacing = Geo.meters(0.0, 0.0, 0.0009, 0.0)

    private fun lane(valid: Boolean?, active: Boolean? = valid, designated: List<String>? = null): IntersectionLanes =
        IntersectionLanes.builder()
            .valid(valid)
            .active(active)
            .indications(listOf("straight"))
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
}
