package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.ARRIVE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.DEPART
import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection.Carriageway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How the streets of a made-up route sit, by the sources [StreetWays] reads in their order. */
class StreetWaysTest {

    /**
     * A freeway from the route's start, a ramp off it at 1,000 m, and streets from 1,300 m, 1,700 m and 2,000 m, whose own
     * access fields say [oneWay] (null: nothing); the arrival at 2,600 m, where no road leaves.
     */
    private fun route(vararg oneWay: Boolean?): LaneRoute {
        require(oneWay.size == 3)
        return LaneScenes.route(
            LaneScenes.node(0.0, maneuver = DEPART),
            LaneScenes.node(1_000.0, road = RoadKind.RAMP),
            LaneScenes.street(1_300.0).copy(oneWay = oneWay[0]),
            LaneScenes.street(1_700.0).copy(oneWay = oneWay[1]),
            LaneScenes.street(2_000.0).copy(oneWay = oneWay[2]),
            LaneScenes.street(2_600.0, maneuver = ARRIVE),
            id = "ways",
        )
    }

    @Test
    fun `the route's own access field tells first, the electronic horizon where it says nothing`() {
        val asked = ArrayList<Double>()
        val ways = StreetWays.of(route(true, null, false)) { station ->
            asked.add(station)
            false
        }
        assertEquals(StreetWays.Way(Carriageway.ONE_WAY, StreetWays.Source.ACCESS), ways.at(2))
        assertEquals(StreetWays.Way(Carriageway.TWO_WAY, StreetWays.Source.HORIZON), ways.at(3))
        assertEquals(StreetWays.Way(Carriageway.TWO_WAY, StreetWays.Source.ACCESS), ways.at(4))
        // The horizon is asked only for the street the access field leaves open, at its middle: 1,700 m to 2,000 m.
        assertEquals(listOf(1_850.0), asked)
        assertEquals(mapOf(StreetWays.Source.ACCESS to 2, StreetWays.Source.HORIZON to 1), ways.known)
        assertEquals(0, ways.unknown)
        assertEquals("ways", ways.routeId)
    }

    @Test
    fun `where no source tells, the street is unknown and counted`() {
        // The horizon holds no edge there.
        val noEdge = StreetWays.of(route(true, null, false)) { null }
        assertNull(noEdge.at(3))
        assertEquals(1, noEdge.unknown)
        assertEquals(mapOf(StreetWays.Source.ACCESS to 2, StreetWays.Source.HORIZON to 0), noEdge.known)
        // No horizon at all (its switch off): the access field alone.
        val noHorizon = StreetWays.of(route(null, null, true))
        assertNull(noHorizon.at(2))
        assertNull(noHorizon.at(3))
        assertEquals(StreetWays.Way(Carriageway.ONE_WAY, StreetWays.Source.ACCESS), noHorizon.at(4))
        assertEquals(2, noHorizon.unknown)
    }

    @Test
    fun `freeways, ramps and the route's end are never looked up, each street at the middle of its stretch`() {
        val asked = ArrayList<Double>()
        val ways = StreetWays.of(route(null, null, null)) { station ->
            asked.add(station)
            station > 1_800.0
        }
        // 1,300 m to 1,700 m, 1,700 m to 2,000 m, 2,000 m to the arrival at 2,600 m.
        assertEquals(listOf(1_500.0, 1_850.0, 2_300.0), asked)
        for (i in listOf(0, 1, 5)) assertNull(ways.at(i), "intersection $i")
        assertEquals(Carriageway.TWO_WAY, ways.at(2)?.carriageway)
        assertEquals(Carriageway.ONE_WAY, ways.at(3)?.carriageway)
        assertEquals(Carriageway.ONE_WAY, ways.at(4)?.carriageway)
        assertEquals(0, ways.unknown)
        // An intersection the route does not have.
        assertNull(ways.at(99))
    }

    @Test
    fun `the last street of a route that goes on past its last intersection is read to the route's end`() {
        val nodes = route(null, null, null).nodes.dropLast(1)
        val open = LaneRoute("open", steps = 1, lengthMeters = 2_600.0, nodes = nodes)
        val asked = ArrayList<Double>()
        StreetWays.of(open) { station ->
            asked.add(station)
            true
        }
        assertEquals(listOf(1_500.0, 1_850.0, 2_300.0), asked)
    }

    @Test
    fun `none tells nothing and counts every street`() {
        val none = StreetWays.none(route(true, false, true))
        for (i in 0..5) assertNull(none.at(i), "intersection $i")
        assertEquals(3, none.unknown)
        assertEquals(mapOf(StreetWays.Source.ACCESS to 0, StreetWays.Source.HORIZON to 0), none.known)
        assertEquals(listOf("access", "horizon"), StreetWays.Source.entries.map { it.logName })
    }
}
