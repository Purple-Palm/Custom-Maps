package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.lanes.SideRoadsLine.Fields
import io.github.purplepalm.custommaps.nav.lanes.SideRoadsLine.Node
import io.github.purplepalm.custommaps.nav.lanes.SideRoadsLine.Reason
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Made-up routes only: intersections as plain values, an invented route id, no place at all. */
class SideRoadsLineTest {

    private val single = "single_carriageway"
    private val ramp = "ramp"
    private val freeway = "freeway"

    private fun maneuver(type: ManeuverType, modifier: TurnModifier? = null, viewStaysUp: Boolean = false) = NodeManeuver(type, modifier, viewStaysUp)

    /**
     * Depart on a two-way street; past a one-way side road; turn right onto a one-way street; an on ramp; merge onto a
     * freeway; a freeway intersection the answer gave no fields for; off at an off ramp; turn left at the ramp's end onto a
     * two-way street; an intersection with a value the models do not list; the arrival.
     */
    private val route = listOf(
        Node(RoadKind.STREET, maneuver(ManeuverType.DEPART), roads = 1, access = listOf(2), formOfWay = listOf(single), outIndex = 0),
        Node(RoadKind.STREET, null, roads = 3, access = listOf(2, 2, -1), formOfWay = listOf(single, single, single), inIndex = 0, outIndex = 1),
        Node(
            RoadKind.STREET,
            maneuver(ManeuverType.TURN, TurnModifier.RIGHT),
            roads = 4,
            access = listOf(2, 2, -1, 0),
            formOfWay = listOf(single, single, single, "service_road"),
            inIndex = 0,
            outIndex = 2,
        ),
        Node(RoadKind.RAMP, maneuver(ManeuverType.ON_RAMP, TurnModifier.SLIGHT_RIGHT, viewStaysUp = true), roads = 2, access = listOf(1, -1), formOfWay = listOf(single, ramp), inIndex = 0, outIndex = 1),
        Node(RoadKind.FREEWAY, maneuver(ManeuverType.MERGE, TurnModifier.SLIGHT_LEFT, viewStaysUp = true), roads = 2, access = listOf(1, -1), formOfWay = listOf(ramp, freeway), inIndex = 0, outIndex = 1),
        Node(RoadKind.FREEWAY, null, roads = 3, access = null, formOfWay = null, inIndex = 0, outIndex = 1),
        Node(RoadKind.RAMP, maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, viewStaysUp = true), roads = 3, access = listOf(1, -1, -1), formOfWay = listOf(freeway, freeway, ramp), inIndex = 0, outIndex = 2),
        Node(
            RoadKind.STREET,
            maneuver(ManeuverType.TURN, TurnModifier.LEFT),
            roads = 3,
            access = listOf(1, 2, 2),
            formOfWay = listOf(ramp, "multiple_carriageway", "multiple_carriageway"),
            inIndex = 0,
            outIndex = 2,
        ),
        Node(RoadKind.STREET, null, roads = 3, access = listOf(2, 7, null), formOfWay = listOf("multiple_carriageway", "boardwalk", null), inIndex = 0, outIndex = 1),
        Node(RoadKind.STREET, maneuver(ManeuverType.ARRIVE), roads = 1, access = listOf(2), formOfWay = listOf("multiple_carriageway"), inIndex = 0),
    )

    private fun fields(nodes: List<Node> = route, jsonBytes: Long? = 100_000, asked: SideRoadsLine.Asked? = SideRoadsLine.Asked(true, true)) =
        SideRoadsLine.fields("route-sr#0", Reason.PREVIEW, asked, nodes, jsonBytes)

    @Test
    fun `the line counts how many intersections came with each field`() {
        val line = fields()
        assertEquals("route-sr#0", line[Fields.ROUTE_ID])
        assertEquals("preview", line[Fields.REASON])
        assertEquals(true, line[Fields.LINK_ACCESS])
        assertEquals(true, line[Fields.LINK_FORM_OF_WAY])
        assertEquals(10, line[Fields.INTERSECTIONS])
        assertEquals(9, line[Fields.WITH_ACCESS])
        assertEquals(9, line[Fields.WITH_FORM_OF_WAY])
    }

    @Test
    fun `every road at every intersection is counted by its access`() {
        // 25 roads: 2 both at the depart, the side road and the turn; forward and backward at the ramps and the freeway;
        // the freeway intersection without the field and the gap at the last but one are missing; 7 is no value listed.
        assertEquals(
            mapOf("none" to 1, "forward" to 6, "backward" to 4, "both" to 9, "other" to 1, "missing" to 4),
            fields()[Fields.ACCESS],
        )
    }

    @Test
    fun `every road is counted by the word of its form of way, never by a word the models do not list`() {
        assertEquals(
            mapOf(
                "freeway" to 3,
                "multiple_carriageway" to 4,
                "single_carriageway" to 8,
                "roundabout" to 0,
                "ramp" to 4,
                "service_road" to 1,
                "pedestrian_zone" to 0,
                "unknown" to 0,
                "other" to 1,
                "missing" to 4,
            ),
            fields()[Fields.FORM_OF_WAY],
        )
        assertEquals("other", SideRoadsLine.formOfWayName("Oak Street"))
        assertEquals("missing", SideRoadsLine.formOfWayName(null))
        for (word in SideRoadsLine.FORM_OF_WAY_WORDS) assertEquals(word, SideRoadsLine.formOfWayName(word))
    }

    @Test
    fun `street maneuvers are those the camera frames as a junction, counted with an access value on their approach`() {
        // The right turn and the left turn at the ramp's end; never the departure, the ramps, the merge or the arrival.
        assertEquals(mapOf("total" to 2, "withAccess" to 2), fields()[Fields.STREET_MANEUVERS])

        // A turn reached from a freeway is no street junction; one whose approach has no value counts in the total only.
        val fromFreeway = listOf(
            Node(RoadKind.FREEWAY, maneuver(ManeuverType.DEPART), roads = 1, access = listOf(-1), formOfWay = listOf(freeway), outIndex = 0),
            Node(RoadKind.STREET, maneuver(ManeuverType.TURN, TurnModifier.RIGHT), roads = 2, access = listOf(-1, 2), formOfWay = null, inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, maneuver(ManeuverType.TURN, TurnModifier.LEFT), roads = 3, access = listOf(null, 2, 2), formOfWay = null, inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, maneuver(ManeuverType.TURN, TurnModifier.LEFT), roads = 3, access = null, formOfWay = null, inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, maneuver(ManeuverType.TURN, TurnModifier.RIGHT), roads = 2, access = listOf(2, 2), formOfWay = null, inIndex = null, outIndex = 1),
        )
        assertEquals(mapOf("total" to 3, "withAccess" to 0), fields(fromFreeway)[Fields.STREET_MANEUVERS])
    }

    @Test
    fun `the route's own road leaving each street intersection is one-way or two-way by its access`() {
        // Two-way at the depart, past the side road and after the left turn; one-way after the right turn; 7 is no value;
        // the arrival leaves on no road; ramps and freeways are not streets.
        assertEquals(mapOf("oneWay" to 1, "twoWay" to 3, "none" to 0, "other" to 1, "missing" to 0), fields()[Fields.OWN_ROAD])

        val unknown = listOf(
            Node(RoadKind.STREET, null, roads = 2, access = null, formOfWay = null, inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, null, roads = 2, access = listOf(2), formOfWay = null, inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, null, roads = 2, access = listOf(2, 0), formOfWay = null, inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, null, roads = 2, access = listOf(2, 1), formOfWay = null, inIndex = 0, outIndex = 1),
        )
        assertEquals(mapOf("oneWay" to 1, "twoWay" to 0, "none" to 1, "other" to 0, "missing" to 2), fields(unknown)[Fields.OWN_ROAD])
    }

    @Test
    fun `a route that came without the fields says so in every count`() {
        val without = route.map { Node(it.road, it.maneuver, it.roads, null, null, it.inIndex, it.outIndex) }
        val line = fields(without, jsonBytes = 80_000, asked = SideRoadsLine.Asked(false, false))
        assertEquals(false, line[Fields.LINK_ACCESS])
        assertEquals(false, line[Fields.LINK_FORM_OF_WAY])
        assertEquals(0, line[Fields.WITH_ACCESS])
        assertEquals(0, line[Fields.WITH_FORM_OF_WAY])
        assertEquals(mapOf("none" to 0, "forward" to 0, "backward" to 0, "both" to 0, "other" to 0, "missing" to 25), line[Fields.ACCESS])
        assertEquals(25, (line[Fields.FORM_OF_WAY] as Map<*, *>)["missing"])
        assertEquals(mapOf("total" to 2, "withAccess" to 0), line[Fields.STREET_MANEUVERS])
        assertEquals(mapOf("oneWay" to 0, "twoWay" to 0, "none" to 0, "other" to 0, "missing" to 5), line[Fields.OWN_ROAD])
        assertEquals(0L, line[Fields.FIELDS_BYTES])
        assertEquals(0.0, line[Fields.GROWTH_PCT])
    }

    @Test
    fun `a route without options says nothing of what was asked`() {
        val line = fields(asked = null)
        assertNull(line[Fields.LINK_ACCESS])
        assertNull(line[Fields.LINK_FORM_OF_WAY])
    }

    @Test
    fun `more values than bearings are all counted, and roads without a value are missing`() {
        val nodes = listOf(
            Node(RoadKind.STREET, null, roads = 2, access = listOf(2, 2, -1), formOfWay = listOf(single), inIndex = 0, outIndex = 1),
            Node(RoadKind.STREET, null, roads = 0, access = listOf(2), formOfWay = emptyList(), inIndex = 0, outIndex = 0),
        )
        val line = fields(nodes)
        assertEquals(mapOf("none" to 0, "forward" to 1, "backward" to 0, "both" to 3, "other" to 0, "missing" to 0), line[Fields.ACCESS])
        assertEquals(1, (line[Fields.FORM_OF_WAY] as Map<*, *>)["single_carriageway"])
        assertEquals(1, (line[Fields.FORM_OF_WAY] as Map<*, *>)["missing"])
        // An empty list came with no value: it is no intersection with the field.
        assertEquals(1, line[Fields.WITH_FORM_OF_WAY])
    }

    // ------------------------------------------------------------------ what the fields take of the route's size

    @Test
    fun `the fields' bytes are their members as the models write them, with the comma before each`() {
        assertEquals(",\"access\":[-1,2,0]".length.toLong(), SideRoadsLine.fieldBytes(listOf(-1, 2, 0), null))
        assertEquals(",\"access\":[2,null]".length.toLong(), SideRoadsLine.fieldBytes(listOf(2, null), null))
        assertEquals(",\"access\":[]".length.toLong(), SideRoadsLine.fieldBytes(emptyList(), null))
        assertEquals(
            ",\"form_of_way\":[\"ramp\",\"single_carriageway\",null]".length.toLong(),
            SideRoadsLine.fieldBytes(null, listOf("ramp", "single_carriageway", null)),
        )
        assertEquals(
            ",\"access\":[2]".length.toLong() + ",\"form_of_way\":[\"freeway\"]".length,
            SideRoadsLine.fieldBytes(listOf(2), listOf("freeway")),
        )
        assertEquals(0L, SideRoadsLine.fieldBytes(null, null))
    }

    @Test
    fun `a word is counted as the models escape it`() {
        assertEquals(2L, SideRoadsLine.jsonStringBytes(""))
        assertEquals(6L, SideRoadsLine.jsonStringBytes("ramp"))
        // A quote and a backslash take two characters each; a tab too; other control characters six.
        assertEquals(2L + 2 + 2 + 2, SideRoadsLine.jsonStringBytes("\"\\\t"))
        assertEquals(2L + 6, SideRoadsLine.jsonStringBytes("\u0001"))
        // The models escape the characters HTML gives meaning to, and the line and paragraph separators.
        assertEquals(2L + 5 * 6, SideRoadsLine.jsonStringBytes("<>&='"))
        assertEquals(2L + 2 * 6, SideRoadsLine.jsonStringBytes("  "))
        // Everything else in UTF-8: two bytes, three bytes, and four for a pair of surrogates.
        assertEquals(2L + 2 + 3 + 4, SideRoadsLine.jsonStringBytes("é€🚗"))
    }

    @Test
    fun `utf-8 bytes are counted without making them`() {
        for (text in listOf("", "Office", "Gym é", "€ 2000", "🚗 car", "lone \uD83D surrogate", "\uDE97")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), SideRoadsLine.utf8Bytes(text), text)
        }
    }

    @Test
    fun `the line says the fields' share of the route and how much larger they make it`() {
        val line = fields()
        val fieldsBytes = route.sumOf { SideRoadsLine.fieldBytes(it.access, it.formOfWay) }
        assertEquals(fieldsBytes, line[Fields.FIELDS_BYTES])
        assertEquals(100_000L, line[Fields.JSON_BYTES])
        assertEquals(SideRoadsLine.growthPercent(100_000, fieldsBytes), line[Fields.GROWTH_PCT])

        // 2,000 bytes of 22,000: the route is 20,000 without them, 10 % more with them; to one decimal.
        assertEquals(10.0, SideRoadsLine.growthPercent(22_000, 2_000))
        assertEquals(33.3, SideRoadsLine.growthPercent(4_000, 1_000))
        assertEquals(0.0, SideRoadsLine.growthPercent(4_000, 0))
        // Without a size, or one the fields do not fit in, there is none.
        assertNull(SideRoadsLine.growthPercent(null, 1_000))
        assertNull(SideRoadsLine.growthPercent(1_000, 1_000))
        assertNull(fields(jsonBytes = null)[Fields.GROWTH_PCT])
        assertNull(fields(jsonBytes = null)[Fields.JSON_BYTES])
    }

    @Test
    fun `access values are named as the models list them`() {
        assertEquals(listOf("none", "forward", "backward", "both"), listOf(0, -1, 1, 2).map(SideRoadsLine::accessName))
        assertEquals("other", SideRoadsLine.accessName(3))
        assertEquals("other", SideRoadsLine.accessName(-2))
        assertEquals("missing", SideRoadsLine.accessName(null))
    }

    @Test
    fun `each reason has its own word, and a route change is a reroute or a new route`() {
        assertEquals(listOf("preview", "start", "reroute", "new_route"), Reason.entries.map { it.logName })
        assertEquals(Reason.REROUTE, Reason.ofRouteChange(reroute = true))
        assertEquals(Reason.NEW_ROUTE, Reason.ofRouteChange(reroute = false))
    }

    @Test
    fun `the line is a few hundred bytes of counts and kinds`() {
        // A route id as long as the navigator's: a response id of 56 characters and the route's index.
        val routeId = "Abc0Def1Ghi2Jkl3Mno4Pqr5Stu6Vwx7Yz89Abc0Def1Ghi2Jkl3Mno4#0"
        // A long route: the scene a hundred times over, as many intersections as a long drive across town.
        val long = List(100) { route }.flatten()
        val fields = SideRoadsLine.fields(routeId, Reason.NEW_ROUTE, SideRoadsLine.Asked(true, true), long, 2_345_678)
        val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), SideRoadsLine.TYPE, fields)
        val bytes = line.toByteArray(Charsets.UTF_8).size
        println("side_roads line: $bytes bytes: $line")
        assertTrue(bytes in 550..800, "$bytes bytes")
        // Whole numbers only, but for the growth's one decimal: no coordinate, no fraction of a place.
        assertFalse(line.substringAfter(SideRoadsLine.TYPE).replace(Regex("\"growthPct\":[0-9.]+"), "").contains("."), line)
        // Never a word the route gave of a road: only the models' words and the line's own.
        assertFalse("boardwalk" in line, line)
    }
}
