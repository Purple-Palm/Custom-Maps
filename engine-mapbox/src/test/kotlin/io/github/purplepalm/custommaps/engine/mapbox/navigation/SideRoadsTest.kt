package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MapboxStreetsV8
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.SideRoadsLine
import io.github.purplepalm.custommaps.nav.lanes.SideRoadsLine.Fields
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A made-up route north along longitude 0 from 0, 0, a point every 0.0009 degrees (about 100 m): a street with a side
 * road, a right turn, an on ramp, a freeway, an off ramp, a left turn and the arrival. Invented roads only; the answer
 * either brings the side roads' two fields at every intersection or brings none.
 */
class SideRoadsTest {

    private val points = (0..19).map { Point.fromLngLat(0.0, it * 0.0009) }

    private val single = "single_carriageway"

    /**
     * An intersection at point [at] with a road for each of [access] (the route in on the first and out on the second;
     * out on the first at the route's [first] and in on it at its [last]), each with its access and form of way when [fields].
     */
    private fun intersection(
        at: Int,
        fields: Boolean,
        access: List<Int>,
        formOfWay: List<String>,
        first: Boolean = false,
        last: Boolean = false,
        motorway: Boolean = false,
        streetsClass: String? = null,
    ): StepIntersection {
        val bearings = listOf(180, 0, 90, 270).take(access.size)
        val builder = StepIntersection.builder()
            .rawLocation(doubleArrayOf(points[at].longitude(), points[at].latitude()))
            .bearings(bearings)
            .entry(bearings.mapIndexed { i, _ -> i != 0 || first })
            .`in`(if (first) null else 0)
            .out(if (last) null else if (first) 0 else 1)
            .classes(if (motorway) listOf("motorway") else null)
            .mapboxStreetsV8(streetsClass?.let { MapboxStreetsV8.builder().roadClass(it).build() })
            .geometryIndex(at)
        if (fields) builder.access(access).formOfWay(formOfWay)
        return builder.build()
    }

    private fun step(type: String, modifier: String?, from: Int, intersections: List<StepIntersection>): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(points[from].longitude(), points[from].latitude())).type(type).modifier(modifier).build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(intersections)
        .build()

    /** Depart on a street with a one-way side road at 2; right at 4; an on ramp at 6; merge at 8; off at 12; left at 15; arrive at 17. */
    private fun leg(fields: Boolean): RouteLeg = RouteLeg.builder().steps(
        listOf(
            step(
                "depart",
                null,
                0,
                listOf(
                    intersection(0, fields, listOf(2), listOf(single), first = true),
                    intersection(2, fields, listOf(2, 2, -1), listOf(single, single, single)),
                ),
            ),
            step("turn", "right", 4, listOf(intersection(4, fields, listOf(2, -1, 2, 0), listOf(single, single, single, "service_road")))),
            step("on ramp", "slight right", 6, listOf(intersection(6, fields, listOf(-1, -1), listOf(single, "ramp"), streetsClass = "motorway_link"))),
            step("merge", "slight left", 8, listOf(intersection(8, fields, listOf(-1, -1, 1), listOf("ramp", "freeway", "freeway"), motorway = true))),
            step("off ramp", "slight right", 12, listOf(intersection(12, fields, listOf(-1, -1), listOf("freeway", "ramp"), streetsClass = "motorway_link"))),
            step("turn", "left", 15, listOf(intersection(15, fields, listOf(-1, 2, 2), listOf("ramp", "multiple_carriageway", "multiple_carriageway")))),
            step("arrive", null, 17, listOf(intersection(17, fields, listOf(2), listOf("multiple_carriageway"), last = true))),
        ),
    ).build()

    private val place = Place("Gym", "1 Oak Street", GeoPoint(points[17].latitude(), points[17].longitude()))

    /** The route of the answer, with the side roads' fields at its intersections or without, its request asking for them or not. */
    private fun route(fields: Boolean, asked: Boolean = true, options: Boolean = true): DirectionsRoute = DirectionsRoute.builder()
        .distance(1_530.0)
        .duration(120.0)
        .legs(listOf(leg(fields)))
        .routeOptions(if (options) routeOptions(points[0], place, sideRoadFields = asked) else null)
        .build()

    private fun bytesOf(route: DirectionsRoute): Long = route.toJson().toByteArray(Charsets.UTF_8).size.toLong()

    private fun line(route: DirectionsRoute, errors: MutableList<Throwable> = mutableListOf()) =
        sideRoadsFields("route-sr#0", SideRoadsLine.Reason.START, route, onSizeError = { errors += it })

    @Test
    fun `every intersection is read with the kind of road the lane evidence gives it`() {
        val nodes = sideRoadNodes(listOf(leg(fields = true)))
        assertEquals(
            listOf(RoadKind.STREET, RoadKind.STREET, RoadKind.STREET, RoadKind.RAMP, RoadKind.FREEWAY, RoadKind.RAMP, RoadKind.STREET, RoadKind.STREET),
            nodes.map { it.road },
        )
        // The same kinds as the lane evidence's reading of the route, which measures its places along the steps' shapes.
        val shapes = listOf(
            listOf(points.subList(0, 5), points.subList(4, 7), points.subList(6, 9), points.subList(8, 13), points.subList(12, 16), points.subList(15, 18), listOf(points[17], points[17])),
        )
        assertEquals(laneRoute("route-sr#0", listOf(leg(fields = true)), shapes).nodes.map { it.road }, nodes.map { it.road })
        // The maneuver of each step at its first intersection, and only there.
        assertEquals(
            listOf(ManeuverType.DEPART, null, ManeuverType.TURN, ManeuverType.ON_RAMP, ManeuverType.MERGE, ManeuverType.OFF_RAMP, ManeuverType.TURN, ManeuverType.ARRIVE),
            nodes.map { it.maneuver?.type },
        )
        assertEquals(listOf(1, 3, 4, 2, 3, 2, 3, 1), nodes.map { it.roads })
        assertEquals(listOf(2, -1, 2, 0), nodes[2].access)
        assertEquals(listOf(null, 0, 0, 0, 0, 0, 0, 0), nodes.map { it.inIndex })
    }

    @Test
    fun `a route that came with the fields says so, counted`() {
        val errors = mutableListOf<Throwable>()
        val fields = line(route(fields = true), errors)

        assertEquals("route-sr#0", fields[Fields.ROUTE_ID])
        assertEquals("start", fields[Fields.REASON])
        assertEquals(true, fields[Fields.LINK_ACCESS])
        assertEquals(true, fields[Fields.LINK_FORM_OF_WAY])
        assertEquals(8, fields[Fields.INTERSECTIONS])
        assertEquals(8, fields[Fields.WITH_ACCESS])
        assertEquals(8, fields[Fields.WITH_FORM_OF_WAY])
        assertEquals(mapOf("none" to 1, "forward" to 9, "backward" to 1, "both" to 8, "other" to 0, "missing" to 0), fields[Fields.ACCESS])
        // The right turn and the left turn at the ramp's end; their approaches have a value.
        assertEquals(mapOf("total" to 2, "withAccess" to 2), fields[Fields.STREET_MANEUVERS])
        // Leaving the street intersections: two-way at the depart and past the side road, one-way after the right turn,
        // two-way after the left turn; the arrival leaves on no road.
        assertEquals(mapOf("oneWay" to 1, "twoWay" to 3, "none" to 0, "other" to 0, "missing" to 0), fields[Fields.OWN_ROAD])
        assertEquals(emptyList<Throwable>(), errors)
    }

    @Test
    fun `the route's size is its JSON as the models write it, and the fields' bytes are exactly what they add`() {
        val served = route(fields = true)
        val notServed = route(fields = false)

        val withFields = line(served)
        val without = line(notServed)

        assertEquals(bytesOf(served), withFields[Fields.JSON_BYTES])
        assertEquals(bytesOf(notServed), without[Fields.JSON_BYTES])
        // The same route without the two fields is the size less their bytes, to the byte.
        assertEquals(bytesOf(served) - bytesOf(notServed), withFields[Fields.FIELDS_BYTES])
        assertEquals(0L, without[Fields.FIELDS_BYTES])
        assertEquals(SideRoadsLine.growthPercent(bytesOf(served), bytesOf(served) - bytesOf(notServed)), withFields[Fields.GROWTH_PCT])
        assertTrue((withFields[Fields.GROWTH_PCT] as Double) > 0.0)
        println("side roads: ${bytesOf(notServed)} bytes without the fields, ${bytesOf(served)} with them, ${withFields[Fields.GROWTH_PCT]} %")
    }

    @Test
    fun `a route asked for the fields that came without them says so`() {
        val fields = line(route(fields = false))
        assertEquals(true, fields[Fields.LINK_ACCESS], "asked")
        assertEquals(0, fields[Fields.WITH_ACCESS], "not served")
        assertEquals(0, fields[Fields.WITH_FORM_OF_WAY])
        assertEquals(19, (fields[Fields.ACCESS] as Map<*, *>)["missing"])
        assertEquals(19, (fields[Fields.FORM_OF_WAY] as Map<*, *>)["missing"])
        assertEquals(mapOf("total" to 2, "withAccess" to 0), fields[Fields.STREET_MANEUVERS])
    }

    @Test
    fun `whether the fields were asked comes from the route's own options`() {
        val notAsked = line(route(fields = false, asked = false))
        assertEquals(false, notAsked[Fields.LINK_ACCESS])
        assertEquals(false, notAsked[Fields.LINK_FORM_OF_WAY])
        val noOptions = line(route(fields = true, options = false))
        assertNull(noOptions[Fields.LINK_ACCESS])
        assertNull(noOptions[Fields.LINK_FORM_OF_WAY])
        assertEquals(8, noOptions[Fields.WITH_ACCESS])
    }

    @Test
    fun `a size that cannot be measured costs the size alone, with one error`() {
        for (failure in listOf<Throwable>(IllegalStateException("no JSON"), OutOfMemoryError("no room"))) {
            val errors = mutableListOf<Throwable>()
            val fields = sideRoadsFields("route-sr#0", SideRoadsLine.Reason.REROUTE, route(fields = true), onSizeError = { errors += it }) { throw failure }
            assertNull(fields[Fields.JSON_BYTES])
            assertNull(fields[Fields.GROWTH_PCT])
            assertEquals(8, fields[Fields.WITH_ACCESS], "the counts all the same")
            assertEquals("reroute", fields[Fields.REASON])
            assertEquals(listOf(failure), errors)
        }
    }

    @Test
    fun `steps beside a freeway are the ones a ramp can begin`() {
        val steps = leg(fields = false).steps().orEmpty()
        // The on ramp leads onto the freeway and the off ramp leaves it; no other step lies beside it.
        assertEquals(listOf(false, false, true, false, true, false, false), stepsBesideFreeway(steps).toList())
    }
}
