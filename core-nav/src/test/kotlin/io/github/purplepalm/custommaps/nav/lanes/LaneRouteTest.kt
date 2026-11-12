package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaneRouteTest {

    @Test
    fun `a link class is a ramp and any other class is not, whatever the step`() {
        for (link in listOf("motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link")) {
            assertTrue(RoadKind.isRamp(link, ManeuverType.TURN, besideFreewayStep = false, freeway = false), link)
        }
        for (other in listOf("motorway", "trunk", "primary", "street", "service")) {
            assertFalse(RoadKind.isRamp(other, ManeuverType.OFF_RAMP, besideFreewayStep = true, freeway = false), other)
        }
    }

    @Test
    fun `without a class an off ramp or an on ramp next to a freeway step is a ramp`() {
        for (type in listOf(ManeuverType.OFF_RAMP, ManeuverType.ON_RAMP)) {
            assertTrue(RoadKind.isRamp(null, type, besideFreewayStep = true, freeway = false), "$type")
            assertTrue(RoadKind.isRamp(null, type, besideFreewayStep = true, freeway = true), "$type: a ramp the route calls a motorway")
            assertFalse(RoadKind.isRamp(null, type, besideFreewayStep = false, freeway = false), "$type far from freeways")
        }
    }

    @Test
    fun `without a class a fork next to a freeway step is a ramp only where its road is no freeway`() {
        assertTrue(RoadKind.isRamp(null, ManeuverType.FORK, besideFreewayStep = true, freeway = false))
        assertFalse(RoadKind.isRamp(null, ManeuverType.FORK, besideFreewayStep = true, freeway = true), "two freeways part")
        assertFalse(RoadKind.isRamp(null, ManeuverType.FORK, besideFreewayStep = false, freeway = false))
    }

    @Test
    fun `without a class other steps are no ramp`() {
        for (type in ManeuverType.entries - setOf(ManeuverType.OFF_RAMP, ManeuverType.ON_RAMP, ManeuverType.FORK)) {
            assertFalse(RoadKind.isRamp(null, type, besideFreewayStep = true, freeway = false), "$type")
        }
        assertFalse(RoadKind.isRamp(null, null, besideFreewayStep = true, freeway = false))
    }

    @Test
    fun `a ramp is a ramp before it is a freeway`() {
        assertEquals(RoadKind.RAMP, RoadKind.of(freeway = true, ramp = true))
        assertEquals(RoadKind.FREEWAY, RoadKind.of(freeway = true, ramp = false))
        assertEquals(RoadKind.STREET, RoadKind.of(freeway = false, ramp = false))
        assertEquals(listOf("freeway", "ramp", "street"), RoadKind.entries.map { it.logName })
    }

    @Test
    fun `designated kinds go by the route's own names`() {
        assertEquals(DesignatedKind.HOV, DesignatedKind.of("hov"))
        assertEquals(DesignatedKind.BUS, DesignatedKind.of("bus"))
        assertEquals(DesignatedKind.TAXI, DesignatedKind.of("taxi"))
        assertEquals(DesignatedKind.MOTORCYCLE, DesignatedKind.of("motorcycle"))
        assertEquals(DesignatedKind.MOPED, DesignatedKind.of("moped"))
        assertEquals(DesignatedKind.BICYCLE, DesignatedKind.of("bicycle"))
        assertEquals(DesignatedKind.OTHER, DesignatedKind.of("truck"), "a name not listed so far")
        assertEquals(DesignatedKind.OTHER, DesignatedKind.of("other"))
        assertEquals(DesignatedKind.OTHER, DesignatedKind.of(null))
        assertEquals(DesignatedKind.OTHER, DesignatedKind.of("HOV"), "the route writes them in lower case")
    }

    @Test
    fun `a route's intersections come in the order the car reaches them`() {
        LaneRoute("route-1", 1, 100.0, listOf(RouteNode(0.0), RouteNode(0.0), RouteNode(100.0)))
        assertThrows(IllegalArgumentException::class.java) {
            LaneRoute("route-1", 1, 100.0, listOf(RouteNode(50.0), RouteNode(40.0)))
        }
    }

    @Test
    fun `an intersection is a lane point where the route lists lanes for it`() {
        assertFalse(RouteNode(0.0).isLanePoint)
        assertTrue(RouteNode(0.0, lanes = listOf(RouteLane(emptySet()))).isLanePoint)
    }
}
