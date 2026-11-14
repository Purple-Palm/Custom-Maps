package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The horizon held for the route the drive is on ([RouteHorizon]): invented routes and edges. */
class RouteHorizonTest {

    private fun edge(oneWay: Boolean, roadClass: HorizonRoadClass) =
        HorizonEdge(100.0, 2, motorway = false, ramp = false, oneWay = oneWay, roadClass = roadClass)

    private val street = HorizonAhead.of(
        "route-1",
        500.0,
        0.0,
        listOf(edge(oneWay = true, roadClass = HorizonRoadClass.PRIMARY), edge(oneWay = false, roadClass = HorizonRoadClass.RESIDENTIAL)),
    )

    @Test
    fun `it holds the newest update of a route and answers for that route only`() {
        val held = RouteHorizon()
        assertNull(held.aheadOf("route-1"))
        held.take(street)
        assertSame(street, held.aheadOf("route-1"))
        assertNull(held.aheadOf("route-2"))
        assertNull(held.aheadOf(null))
        val next = HorizonAhead.of("route-2", 0.0, 0.0, emptyList())
        held.take(next)
        assertSame(next, held.ahead)
        assertNull(held.aheadOf("route-1"))
        held.clear()
        assertNull(held.ahead)
    }

    @Test
    fun `whether a street of the route is one-way, and its class, where the horizon holds it`() {
        val held = RouteHorizon()
        held.take(street)
        assertEquals(true, held.oneWayAt("route-1", 550.0))
        assertEquals(false, held.oneWayAt("route-1", 650.0))
        assertEquals(HorizonRoadClass.PRIMARY, held.roadClassAt("route-1", 500.0))
        assertEquals(HorizonRoadClass.RESIDENTIAL, held.roadClassAt("route-1", 699.0))
        assertNull(held.oneWayAt("route-1", 700.0))
        assertNull(held.oneWayAt("route-1", 499.0))
        assertNull(held.oneWayAt("route-2", 550.0))
        assertNull(held.roadClassAt("route-2", 550.0))
    }
}
