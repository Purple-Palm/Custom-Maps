package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Place
import java.net.URL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Synthetic requests: an invented store in the sea near the point 0, 0. */
class RouteRequestTest {

    private val origin = Point.fromLngLat(0.01, 0.02)
    private val building = GeoPoint(0.001, 0.002)
    private val parking = GeoPoint(0.0012, 0.0026)

    private val plainPlace = Place("A large store", "1 Oak Street", building)
    private val placeWithStop = plainPlace.copy(stopPoint = parking)

    private fun assertPoint(expected: GeoPoint, actual: Point?) {
        requireNotNull(actual)
        assertEquals(expected.latitude, actual.latitude(), 1e-9)
        assertEquals(expected.longitude, actual.longitude(), 1e-9)
    }

    @Test
    fun `with a stop point the route ends there and the place is the arrival target`() {
        val options = routeOptions(origin, placeWithStop)

        val coordinates = options.coordinatesList()
        assertEquals(2, coordinates.size)
        assertEquals(origin, coordinates[0])
        assertPoint(parking, coordinates[1])

        val targets = requireNotNull(options.waypointTargetsList())
        assertEquals(2, targets.size)
        // The origin has no target: its place in the list stays empty.
        assertNull(targets[0])
        assertPoint(building, targets[1])
        // As it is sent: a list that begins with a semicolon, longitude first.
        assertEquals(";0.002,0.001", options.waypointTargets())
    }

    @Test
    fun `without a stop point the route ends at the place and no targets are sent`() {
        val options = routeOptions(origin, plainPlace)

        val coordinates = options.coordinatesList()
        assertEquals(2, coordinates.size)
        assertEquals(origin, coordinates[0])
        assertPoint(building, coordinates[1])
        assertNull(options.waypointTargets())
        assertNull(options.waypointTargetsList())
    }

    @Test
    fun `the side the car arrives on is never asked for`() {
        assertNull(routeOptions(origin, placeWithStop).approaches())
        assertNull(routeOptions(origin, plainPlace).approaches())
        assertNull(routeOptions(origin, placeWithStop, originBearing = 90.0).approaches())
    }

    @Test
    fun `a bearing is sent for the origin only, when one is given`() {
        val options = routeOptions(origin, placeWithStop, originBearing = 44.0)

        val bearings = requireNotNull(options.bearingsList())
        assertEquals(2, bearings.size)
        assertEquals(44.0, bearings[0]?.angle())
        assertEquals(45.0, bearings[0]?.degrees())
        assertNull(bearings[1])
        assertEquals("44,45;", options.bearings())
        // The bearing changes nothing else of the request.
        assertEquals(routeOptions(origin, placeWithStop).coordinates(), options.coordinates())
        assertEquals(routeOptions(origin, placeWithStop).waypointTargets(), options.waypointTargets())
    }

    @Test
    fun `no bearing is sent when none is given or when it is no number`() {
        assertNull(routeOptions(origin, plainPlace).bearings())
        assertNull(routeOptions(origin, plainPlace, originBearing = null).bearingsList())
        assertNull(routeOptions(origin, plainPlace, originBearing = Double.NaN).bearings())
        assertNull(routeOptions(origin, plainPlace, originBearing = Double.POSITIVE_INFINITY).bearings())
    }

    @Test
    fun `a bearing outside the circle is brought into it`() {
        assertEquals(350.0, routeOptions(origin, plainPlace, originBearing = -10.0).bearingsList()?.get(0)?.angle())
        assertEquals(10.0, routeOptions(origin, plainPlace, originBearing = 370.0).bearingsList()?.get(0)?.angle())
        assertEquals(0.0, routeOptions(origin, plainPlace, originBearing = 360.0).bearingsList()?.get(0)?.angle())
        assertEquals(0.0, routeOptions(origin, plainPlace, originBearing = 0.0).bearingsList()?.get(0)?.angle())
    }

    @Test
    fun `the request is for driving with traffic, with steps, alternatives and traffic updates`() {
        for (place in listOf(plainPlace, placeWithStop)) {
            val options = routeOptions(origin, place)

            assertEquals(DirectionsCriteria.PROFILE_DRIVING_TRAFFIC, options.profile())
            // Arrival targets need steps; traffic updates during a drive need a route that can be refreshed.
            assertEquals(true, options.steps())
            assertEquals(true, options.enableRefresh())
            assertEquals(true, options.alternatives())
            assertEquals("en", options.language())
            assertEquals(DirectionsCriteria.IMPERIAL, options.voiceUnits())
        }
    }

    @Test
    fun `the request line says where the routes end and what the targets are`() {
        val fields = routeRequestFields(placeWithStop, routeOptions(origin, placeWithStop, originBearing = 44.0))

        assertEquals(listOf(parking.latitude, parking.longitude), fields["stopPoint"])
        assertEquals(listOf(null, listOf(building.latitude, building.longitude)), fields["targets"])
        assertEquals(listOf(listOf(44.0, 45.0), null), fields["bearings"])
    }

    @Test
    fun `the request line of a place without a stop point has none of them`() {
        val fields = routeRequestFields(plainPlace, routeOptions(origin, plainPlace))

        assertEquals(setOf("stopPoint", "targets", "bearings", "linkAccess", "linkFormOfWay"), fields.keys)
        assertNull(fields["stopPoint"])
        assertNull(fields["targets"])
        assertNull(fields["bearings"])
    }

    // ------------------------------------------------------------------ the side roads' fields (Debug switch)

    /** The parameters of [options] as they are sent, each as name=value; the token in place of the real one. */
    private fun sent(options: RouteOptions): List<String> = options.toUrl("token").query.split("&")

    @Test
    fun `with the switch off the request asks for no field of the side roads`() {
        for (options in listOf(routeOptions(origin, placeWithStop, originBearing = 44.0), routeOptions(origin, plainPlace, sideRoadFields = false))) {
            assertNull(options.intersectionLinkAccess())
            assertNull(options.intersectionLinkFormOfWay())
            assertNull(options.intersectionLinkGeometry())
            assertNull(options.intersectionLinkElevated())
            assertNull(options.intersectionLinkBridge())
            assertTrue(sent(options).none { it.startsWith("intersection_link") }, "${sent(options)}")
        }
    }

    @Test
    fun `with the switch on the request asks for the access and the form of way, and for nothing else`() {
        for (place in listOf(plainPlace, placeWithStop)) {
            val off = routeOptions(origin, place, originBearing = 44.0)
            val on = routeOptions(origin, place, originBearing = 44.0, sideRoadFields = true)

            assertEquals(true, on.intersectionLinkAccess())
            assertEquals(true, on.intersectionLinkFormOfWay())
            // The roads' shapes can make the answer much larger and say nothing of the route's own road: never asked.
            assertNull(on.intersectionLinkGeometry())
            assertNull(on.intersectionLinkElevated())
            assertNull(on.intersectionLinkBridge())
            // The same request with two more parameters: every other one as it was, in its place.
            assertEquals(sent(off), sent(on) - setOf("intersection_link_form_of_way=true", "intersection_link_access=true"))
            assertEquals(sent(off).size + 2, sent(on).size)
            assertEquals(off.coordinates(), on.coordinates())
            assertEquals(off.waypointTargets(), on.waypointTargets())
            assertEquals(off.bearings(), on.bearings())
            assertEquals(off.alternatives(), on.alternatives())
        }
    }

    @Test
    fun `the request line names both fields, asked or not`() {
        val off = routeRequestFields(placeWithStop, routeOptions(origin, placeWithStop))
        assertEquals(false, off["linkAccess"])
        assertEquals(false, off["linkFormOfWay"])

        val on = routeRequestFields(placeWithStop, routeOptions(origin, placeWithStop, sideRoadFields = true))
        assertEquals(true, on["linkAccess"])
        assertEquals(true, on["linkFormOfWay"])
        // The rest of the line is what it was.
        assertEquals(off - setOf("linkAccess", "linkFormOfWay"), on - setOf("linkAccess", "linkFormOfWay"))
    }

    @Test
    fun `a request with the fields comes through its address with them`() {
        // What the navigator does with a reroute's request: read from its address and written again.
        val options = routeOptions(origin, placeWithStop, originBearing = 44.0, sideRoadFields = true)
        val read = RouteOptions.fromUrl(options.toUrl("token"))

        assertEquals(true, read.intersectionLinkAccess())
        assertEquals(true, read.intersectionLinkFormOfWay())
        assertEquals(options.toUrl("token").toString(), read.toUrl("token").toString())
    }

    @Test
    fun `with the switch off the watcher hands every reroute back as it came, fields or none`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { false }
        // As the navigator hands them over: read from the address of the request it made.
        val without = RouteOptions.fromUrl(routeOptions(origin, placeWithStop).toUrl("token"))
        val with = RouteOptions.fromUrl(routeOptions(origin, placeWithStop, sideRoadFields = true).toUrl("token"))

        assertSame(without, watcher.onRouteOptions(without))
        // A field is never taken away, the switch off or on.
        assertSame(with, watcher.onRouteOptions(with))

        assertEquals(listOf("reroute_options", "reroute_options"), lines.types)
        assertEquals(listOf(false, true), lines.fields.map { it["linkAccess"] })
        assertEquals(listOf(false, true), lines.fields.map { it["linkFormOfWay"] })
        assertEquals(listOf(0, 0), lines.fields.map { it["linkAdded"] })
    }

    @Test
    fun `with the switch on the watcher adds the fields a reroute lost, and nothing else`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { true }
        val lost = RouteOptions.fromUrl(routeOptions(origin, placeWithStop, originBearing = 44.0).toUrl("token"))

        val adapted = watcher.onRouteOptions(lost)

        assertEquals(true, adapted.intersectionLinkAccess())
        assertEquals(true, adapted.intersectionLinkFormOfWay())
        assertNull(adapted.intersectionLinkGeometry())
        // The same reroute request with two more parameters, as the navigator writes it to its address again.
        assertEquals(sent(lost), sent(adapted) - setOf("intersection_link_form_of_way=true", "intersection_link_access=true"))
        assertEquals(sent(lost).size + 2, sent(adapted).size)
        // The line says what the reroute came with, before anything was added.
        val fields = lines.fields.single()
        assertEquals(false, fields["linkAccess"])
        assertEquals(false, fields["linkFormOfWay"])
        assertEquals(2, fields["linkAdded"])
        assertEquals(2, fields["targets"])
        assertEquals(true, fields["lastTargetSet"])
    }

    @Test
    fun `with the switch on a parameter the models do not know stays in the reroute as it came`() {
        // The navigator's address may carry a parameter of its own; the watcher hands back every one of them.
        val address = URL(routeOptions(origin, placeWithStop).toUrl("token").toString() + "&invented_parameter=7")
        val lost = RouteOptions.fromUrl(address)
        val watcher = RerouteOptionsLogger(RecordingLog(), { placeWithStop }) { true }

        val adapted = watcher.onRouteOptions(lost)

        assertTrue("invented_parameter=7" in sent(lost))
        assertEquals(sent(lost), sent(adapted) - setOf("intersection_link_form_of_way=true", "intersection_link_access=true"))
        assertEquals(sent(lost).size + 2, sent(adapted).size)
    }

    @Test
    fun `with the switch on a reroute that kept the fields goes as it came`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { true }
        val kept = RouteOptions.fromUrl(routeOptions(origin, placeWithStop, sideRoadFields = true).toUrl("token"))

        assertSame(kept, watcher.onRouteOptions(kept))

        val fields = lines.fields.single()
        assertEquals(true, fields["linkAccess"])
        assertEquals(true, fields["linkFormOfWay"])
        assertEquals(0, fields["linkAdded"])
    }

    @Test
    fun `with the switch on a reroute that kept one field gets only the other`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { true }
        val accessOnly = routeOptions(origin, placeWithStop).toBuilder().intersectionLinkAccess(true).build()
        val formOnly = routeOptions(origin, placeWithStop).toBuilder().intersectionLinkFormOfWay(true).build()

        val fromAccess = watcher.onRouteOptions(accessOnly)
        val fromForm = watcher.onRouteOptions(formOnly)

        for (adapted in listOf(fromAccess, fromForm)) {
            assertEquals(true, adapted.intersectionLinkAccess())
            assertEquals(true, adapted.intersectionLinkFormOfWay())
        }
        // One more parameter each, every other one as it was, in its place.
        assertEquals(sent(accessOnly), sent(fromAccess) - "intersection_link_form_of_way=true")
        assertEquals(sent(accessOnly).size + 1, sent(fromAccess).size)
        assertEquals(sent(formOnly), sent(fromForm) - "intersection_link_access=true")
        assertEquals(sent(formOnly).size + 1, sent(fromForm).size)
        assertEquals(listOf(1, 1), lines.fields.map { it["linkAdded"] })
        assertEquals(listOf(true, false), lines.fields.map { it["linkAccess"] })
        assertEquals(listOf(false, true), lines.fields.map { it["linkFormOfWay"] })
    }

    @Test
    fun `the watcher reads the switch at each reroute`() {
        val lines = RecordingLog()
        var on = false
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { on }
        val lost = routeOptions(origin, placeWithStop)

        assertSame(lost, watcher.onRouteOptions(lost))
        on = true
        assertEquals(true, watcher.onRouteOptions(lost).intersectionLinkAccess())
        on = false
        assertSame(lost, watcher.onRouteOptions(lost))
        assertEquals(listOf(0, 2, 0), lines.fields.map { it["linkAdded"] })
    }

    @Test
    fun `a switch that cannot be read never fails the reroute`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { error("no switch can be read") }
        val request = routeOptions(origin, placeWithStop)

        assertSame(request, watcher.onRouteOptions(request))

        assertEquals(listOf("error", "reroute_options"), lines.types)
        assertEquals("reroute_options", lines.fields.first()["where"])
        assertEquals(0, lines.fields.last()["linkAdded"])
    }

    @Test
    fun `a request read from its address and written again is the same request`() {
        // The navigator hands a reroute's request to the watcher as an address, and takes what the
        // watcher returns as an address again. A request must come through that as it went in.
        for (options in listOf(routeOptions(origin, placeWithStop, originBearing = 44.0), routeOptions(origin, plainPlace))) {
            val address = options.toUrl("token")
            val read = RouteOptions.fromUrl(address)

            assertEquals(options.coordinates(), read.coordinates())
            assertEquals(options.waypointTargets(), read.waypointTargets())
            assertEquals(options.bearings(), read.bearings())
            assertEquals(options.approaches(), read.approaches())
            assertEquals(address.toString(), read.toUrl("token").toString())
        }
    }

    @Test
    fun `the watcher of reroutes hands the request back as it came`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { placeWithStop }) { false }
        val request = routeOptions(origin, placeWithStop)

        assertSame(request, watcher.onRouteOptions(request))

        assertEquals(listOf("reroute_options"), lines.types)
        val fields = lines.fields.single()
        assertEquals(2, fields["coordinates"])
        assertEquals(2, fields["targets"])
        assertEquals(true, fields["lastTargetSet"])
        assertEquals(true, fields["targetWanted"])
        assertEquals(0.0, fields["endMeters"])
        // Without the Debug switch nothing is asked of the side roads, and nothing is added.
        assertEquals(false, fields["linkAccess"])
        assertEquals(false, fields["linkFormOfWay"])
        assertEquals(0, fields["linkAdded"])
    }

    @Test
    fun `a reroute that lost its targets shows in the line`() {
        // What a reroute would look like if it kept the points of the request and dropped the targets.
        val request = routeOptions(origin, placeWithStop).toBuilder().waypointTargets(null).build()

        val fields = rerouteOptionsFields(request, placeWithStop)

        assertEquals(2, fields["coordinates"])
        assertNull(fields["targets"])
        assertEquals(false, fields["lastTargetSet"])
        assertEquals(true, fields["targetWanted"])
    }

    @Test
    fun `a reroute whose last target is empty shows in the line`() {
        val request = routeOptions(origin, placeWithStop).toBuilder().waypointTargetsList(listOf(null, null)).build()

        val fields = rerouteOptionsFields(request, placeWithStop)

        assertEquals(2, fields["targets"])
        assertEquals(false, fields["lastTargetSet"])
    }

    @Test
    fun `a reroute with more points than targets shows both counts`() {
        val midway = Point.fromLngLat(0.005, 0.01)
        val request = routeOptions(origin, placeWithStop).toBuilder()
            .coordinatesList(listOf(origin, midway, parking.toPoint()))
            .build()

        val fields = rerouteOptionsFields(request, placeWithStop)

        assertEquals(3, fields["coordinates"])
        assertEquals(2, fields["targets"])
        assertEquals(true, fields["lastTargetSet"])
    }

    @Test
    fun `a reroute to a place without a stop point wants no target`() {
        val fields = rerouteOptionsFields(routeOptions(origin, plainPlace), plainPlace)

        assertNull(fields["targets"])
        assertEquals(false, fields["lastTargetSet"])
        assertEquals(false, fields["targetWanted"])
        assertEquals(0.0, fields["endMeters"])
    }

    @Test
    fun `the line says how far a reroute ends from where routes to the place end`() {
        // 0.001 degrees of longitude east of the stop point: about 111 m.
        val moved = Point.fromLngLat(parking.longitude + 0.001, parking.latitude)
        val request = routeOptions(origin, placeWithStop).toBuilder().coordinatesList(listOf(origin, moved)).build()

        val meters = rerouteOptionsFields(request, placeWithStop)["endMeters"] as Double

        assertEquals(Geo.meters(parking, GeoPoint(parking.latitude, parking.longitude + 0.001)), meters, 0.05)
    }

    @Test
    fun `a reroute with no place known still gets its line`() {
        val fields = rerouteOptionsFields(routeOptions(origin, placeWithStop), destination = null)

        assertEquals(2, fields["coordinates"])
        assertEquals(2, fields["targets"])
        assertNull(fields["targetWanted"])
        assertNull(fields["endMeters"])
    }

    @Test
    fun `a line that cannot be written never fails the reroute`() {
        val lines = RecordingLog()
        val watcher = RerouteOptionsLogger(lines, { error("no place can be read") }) { false }
        val request = routeOptions(origin, placeWithStop)

        assertSame(request, watcher.onRouteOptions(request))

        assertEquals(listOf("error"), lines.types)
        assertEquals("reroute_options", lines.fields.single()["where"])
    }

    private class RecordingLog : DebugLog {
        val types = mutableListOf<String>()
        val fields = mutableListOf<Map<String, Any?>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            types += type
            this.fields += fields
        }
    }
}
