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
    fun `each update of the route keeps the edges the one before laid behind the car, until a route change or a seek`() {
        val held = RouteHorizon()
        held.take(street)
        // The car 150 m along: on the second edge, the first behind it as the update before laid it.
        val on = HorizonAhead.of("route-1", 650.0, 0.5, listOf(edge(oneWay = false, roadClass = HorizonRoadClass.RESIDENTIAL), edge(oneWay = true, roadClass = HorizonRoadClass.PRIMARY)))
        held.take(on)
        val joined = requireNotNull(held.aheadOf("route-1"))
        assertEquals(listOf(500.0 to 600.0, 600.0 to 700.0, 700.0 to 800.0), joined.stretches.map { it.start to it.end })
        assertEquals(650.0, joined.from)
        assertEquals(true, held.oneWayAt("route-1", 550.0))
        assertEquals(HorizonRoadClass.PRIMARY, held.roadClassAt("route-1", 760.0))
        // A seek far ahead: no edge end of what was held meets the car's edge, the newest alone.
        val seek = HorizonAhead.of("route-1", 3_000.0, 0.5, listOf(edge(oneWay = true, roadClass = HorizonRoadClass.PRIMARY)))
        held.take(seek)
        assertSame(seek, held.ahead)
        // Another route's update starts over.
        held.take(on)
        val other = HorizonAhead.of("route-2", 650.0, 0.5, listOf(edge(oneWay = true, roadClass = HorizonRoadClass.PRIMARY)))
        held.take(other)
        assertSame(other, held.ahead)
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
