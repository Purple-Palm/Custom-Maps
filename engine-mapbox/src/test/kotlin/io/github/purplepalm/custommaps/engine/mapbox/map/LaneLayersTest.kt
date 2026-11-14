package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The layers of the app's own lanes: what each draws, at which width and zoom, in which order and slot (on a fake style
 * that keeps its layers in a list, as the map draws them), and that the own line adds them, empty, after its own at
 * every style load and when either Debug switch is turned on, in the look's colours.
 */
class LaneLayersTest {

    private val under = listOf(
        LaneLayers.ASPHALT, LaneLayers.TINT, LaneLayers.LIT, LaneLayers.LIT_EDGES, LaneLayers.ERASER,
        LaneLayers.PAINT, LaneLayers.PAINT_WIDE, LaneLayers.SHAPES, LaneLayers.STOP,
    )
    private val over = listOf(LaneLayers.ARROWS, LaneLayers.ARROW_EDGES)
    private val ownLine = OwnRouteLine.LAYERS.map { it.id }

    @Test
    fun `the drawing's layers in the design's order, from the bottom`() {
        assertEquals(under + over, LaneLayers.LAYERS.map { it.id })
        assertEquals(under, LaneLayers.LAYERS.filter { it.group == LaneLayers.Group.UNDER_ROUTE }.map { it.id })
        assertEquals(over, LaneLayers.LAYERS.filter { it.group == LaneLayers.Group.OVER_ROUTE }.map { it.id })
    }

    @Test
    fun `every kind of feature is drawn, a polygon by one fill and a line by one line layer of its width`() {
        for (kind in LaneDrawing.Kind.entries) {
            val fills = LaneLayers.LAYERS.filter { it.fill && kind in it.kinds }
            assertEquals(if (kind.polygon) 1 else 0, fills.size, "fills of $kind")
        }
        // Lines of paint by their width, one layer each; the arrows' outline is their edge.
        for (kind in listOf(LaneDrawing.Kind.LANE_LINE, LaneDrawing.Kind.EDGE_LINE, LaneDrawing.Kind.CHANNEL_LINE)) {
            assertEquals(LaneLayers.PAINT, LaneLayers.LAYERS.single { kind in it.kinds && it.width == LaneDrawing.Width.NORMAL }.id)
            assertEquals(LaneLayers.PAINT_WIDE, LaneLayers.LAYERS.single { kind in it.kinds && it.width == LaneDrawing.Width.WIDE }.id)
        }
        assertEquals(LaneLayers.LIT_EDGES, LaneLayers.LAYERS.single { !it.fill && LaneDrawing.Kind.LIT_EDGE in it.kinds }.id)
        assertEquals(LaneLayers.SHAPES, LaneLayers.LAYERS.single { !it.fill && LaneDrawing.Kind.DIAMOND in it.kinds }.id)
        assertEquals(LaneLayers.ARROW_EDGES, LaneLayers.LAYERS.single { !it.fill && LaneDrawing.Kind.ARROW in it.kinds }.id)
        // A street's stop line by one line layer of its own width, in white paint as the lines are.
        assertEquals(LaneLayers.STOP, LaneLayers.LAYERS.single { !it.fill && LaneDrawing.Kind.STOP_LINE in it.kinds }.id)
        assertEquals(listOf(LaneDrawing.Kind.STOP_LINE), LaneLayers.LAYERS.single { it.id == LaneLayers.STOP }.kinds)
    }

    @Test
    fun `lines are drawn at their ground width, the arrows' edge at 1 dp, every layer emissive`() {
        val byId = LaneLayers.LAYERS.associateBy { it.id }
        assertEquals(LaneDrawing.Width.NORMAL, byId.getValue(LaneLayers.PAINT).width)
        assertEquals(LaneDrawing.Width.WIDE, byId.getValue(LaneLayers.PAINT_WIDE).width)
        assertEquals(LaneDrawing.Width.EDGE, byId.getValue(LaneLayers.LIT_EDGES).width)
        assertEquals(LaneDrawing.Width.NORMAL, byId.getValue(LaneLayers.SHAPES).width)
        assertEquals(LaneDrawing.Width.STOP, byId.getValue(LaneLayers.STOP).width)
        assertEquals(1.0, byId.getValue(LaneLayers.ARROW_EDGES).fixedDp)
        for (spec in LaneLayers.LAYERS) {
            assertEquals(1.0, spec.emissiveStrength, spec.id)
            if (!spec.fill) assertTrue(spec.width != null || spec.fixedDp != null || spec.meters != null, spec.id)
        }
    }

    @Test
    fun `asphalt and paint from 15_5, symbols from 16_5, the lit layers from 16_85, at latitude 0`() {
        val byId = LaneLayers.LAYERS.associateBy { it.id }
        for (id in listOf(LaneLayers.ASPHALT, LaneLayers.PAINT, LaneLayers.PAINT_WIDE)) assertEquals(LaneDrawing.ASPHALT_MIN_ZOOM, byId.getValue(id).minZoom, id)
        for (id in listOf(LaneLayers.SHAPES, LaneLayers.STOP, LaneLayers.ARROWS, LaneLayers.ARROW_EDGES)) assertEquals(LaneDrawing.SYMBOL_MIN_ZOOM, byId.getValue(id).minZoom, id)
        for (id in listOf(LaneLayers.TINT, LaneLayers.LIT, LaneLayers.LIT_EDGES, LaneLayers.ERASER)) assertEquals(LaneDrawing.LIT_MIN_ZOOM, byId.getValue(id).minZoom, id)
    }

    @Test
    fun `the eraser is the own line's path one lane and 0_3 m wide, over the lit lane and its edges and under the paint`() {
        val eraser = LaneLayers.LAYERS.single { it.id == LaneLayers.ERASER }
        assertEquals(OwnRouteLine.SOURCE, eraser.source)
        assertEquals(3.96, eraser.meters!!, 1e-9)
        assertEquals(LaneDrawing.LIT_MIN_ZOOM, eraser.minZoom)
        assertTrue(eraser.kinds.isEmpty() && !eraser.fill)
        val ids = LaneLayers.LAYERS.map { it.id }
        assertEquals(ids.indexOf(LaneLayers.LIT_EDGES) + 1, ids.indexOf(LaneLayers.ERASER))
        assertEquals(ids.indexOf(LaneLayers.PAINT) - 1, ids.indexOf(LaneLayers.ERASER))
        // Every other layer draws the drawing's own source; the eraser goes with what is lit.
        assertTrue(LaneLayers.LAYERS.filter { it.id != LaneLayers.ERASER }.all { it.source == LaneLayers.SOURCE })
        assertTrue(LaneLayers.ERASER in LaneLayers.LIT_LAYERS)
    }

    /** A style as the map keeps its layers: in one list, each with its slot, drawn from the first to the last. */
    private class FakeStyle(initial: List<Pair<String, String>>) {
        val layers = initial.toMutableList()

        fun add(position: LaneLayers.Position) {
            val entry = position.id to position.slot
            when {
                position.below != null -> layers.add(index(position.below), entry)
                position.above != null -> layers.add(index(position.above) + 1, entry)
                else -> {
                    // At the end of its slot: after the slot's own layer and every layer in it so far.
                    var at = index(position.slot) + 1
                    while (at < layers.size && layers[at].second == position.slot) at++
                    layers.add(at, entry)
                }
            }
        }

        fun remove(id: String) {
            layers.removeAt(index(id))
        }

        fun index(id: String): Int = layers.indexOfFirst { it.first == id }.also { require(it >= 0) { "no layer $id" } }

        val ids: List<String> get() = layers.map { it.first }
    }

    private val sdkLine = listOf(
        "mapbox-bottom-level-route-layer",
        "mapbox-layerGroup-1-casing",
        "mapbox-layerGroup-1-main",
        "mapbox-layerGroup-1-traffic",
        "mapbox-top-level-route-layer",
    )
    private val sdkArrow = listOf("mapbox-navigation-arrow-shaft-casing-layer", "mapbox-navigation-arrow-head-layer")

    /** Standard's roads below the middle slot, the slot with the SDK's route line, the own route line and the arrow, and the top slot. */
    private fun standard() = FakeStyle(
        listOf("road-street" to "", "middle" to "") +
            (sdkLine + ownLine + sdkArrow).map { it to "middle" } +
            listOf("top" to "", "building" to "top", "road-label" to "top"),
    )

    @Test
    fun `chained positions put the layers under the route line and the arrows over the own line, below the maneuver arrow`() {
        val style = standard()
        for (p in LaneLayers.positions(LaneLayers.Placement.CHAINED, ownLine.last())) style.add(p)
        assertEquals(
            listOf("road-street", "middle") + under + sdkLine + ownLine + over + sdkArrow + listOf("top", "building", "road-label"),
            style.ids,
        )
        assertEquals(true, LayerOrder.lanesInPlace(style.ids, under, over, ownLine))
        // Every layer in the middle slot.
        assertTrue(LaneLayers.positions(LaneLayers.Placement.CHAINED, ownLine.last()).all { it.slot == "middle" && !it.marker })
    }

    @Test
    fun `own slots put the same layers in the same places`() {
        val style = standard()
        val positions = LaneLayers.positions(LaneLayers.Placement.SLOTS, ownLine.last())
        // The two slots first, in the middle slot; every layer in one of them.
        assertEquals(listOf(LaneLayers.UNDER_SLOT, LaneLayers.OVER_SLOT), positions.filter { it.marker }.map { it.id })
        assertTrue(positions.filter { it.marker }.all { it.slot == "middle" })
        assertEquals(under, positions.filter { it.slot == LaneLayers.UNDER_SLOT }.map { it.id })
        assertEquals(over, positions.filter { it.slot == LaneLayers.OVER_SLOT }.map { it.id })
        for (p in positions) style.add(p)
        assertEquals(
            listOf("road-street", "middle", LaneLayers.UNDER_SLOT) + under + sdkLine + ownLine + LaneLayers.OVER_SLOT + over + sdkArrow +
                listOf("top", "building", "road-label"),
            style.ids,
        )
        assertEquals(true, LayerOrder.lanesInPlace(style.ids, under, over, ownLine))
    }

    @Test
    fun `the own line's layers added again keep the lane arrows above them, either way`() {
        for (placement in LaneLayers.Placement.entries) {
            val style = standard()
            for (p in LaneLayers.positions(placement, ownLine.last())) style.add(p)
            // As the own line does when it is turned off and on again: its layers out, and back right above the SDK's top.
            for (id in ownLine) style.remove(id)
            style.add(LaneLayers.Position(ownLine[0], "middle", above = "mapbox-top-level-route-layer"))
            style.add(LaneLayers.Position(ownLine[1], "middle", above = ownLine[0]))
            assertEquals(true, LayerOrder.lanesInPlace(style.ids, under, over, ownLine), "$placement")
        }
    }

    @Test
    fun `the chained positions are the choice until slots inside a slot are seen to work`() {
        assertEquals(LaneLayers.Placement.CHAINED, LaneLayers.PLACEMENT)
    }

    @Test
    fun `features name their paint and width as the layers read them`() {
        assertEquals("white", LaneLayers.paintName(LaneDrawing.Paint.WHITE))
        assertEquals("yellow", LaneLayers.paintName(LaneDrawing.Paint.YELLOW))
        assertEquals("normal", LaneLayers.widthName(LaneDrawing.Width.NORMAL))
        assertEquals("wide", LaneLayers.widthName(LaneDrawing.Width.WIDE))
        assertEquals("edge", LaneLayers.widthName(LaneDrawing.Width.EDGE))
    }

    // ------------------------------------------------------------------ added and coloured by the own line

    private fun lanesOn(scene: OwnLineScene) {
        scene.line.setLanes(true)
        scene.line.setEnabled(true)
        scene.line.onStyleLoaded(Look.NIGHT)
    }

    @Test
    fun `at a style load the lanes' layers are added empty after the own line's, in the look's colours`() {
        val scene = OwnLineScene()
        lanesOn(scene)
        val calls = scene.style.calls
        assertTrue(calls.indexOf("lanes.add") > calls.lastIndexOf("add"), "after the own line's layers: $calls")
        // With the lanes the own line has its second pair of layers, and the lane arrows lie over it.
        assertEquals(scene.style.added?.map { it.id }, ownLine + OwnRouteLine.LIT_LAYERS.map { it.id })
        assertEquals(scene.line.layerIds, ownLine + OwnRouteLine.LIT_LAYERS.map { it.id })
        assertEquals(LaneLayers.positions(LaneLayers.PLACEMENT, OwnRouteLine.LIT_LINE), scene.lanes.positions)
        assertEquals(MapPalette.lanes(Look.NIGHT), scene.lanes.shownColours)
        assertEquals(0.0, scene.lanes.latitude)
        // A new style has none of them: they come again with it, after the own line's.
        scene.reload()
        scene.style.calls.clear()
        scene.line.onStyleLoaded(Look.DAY)
        assertEquals(listOf("show", "lanes.remove", "add", "lanes.add"), scene.style.calls)
        assertEquals(MapPalette.lanes(Look.DAY), scene.lanes.shownColours)
    }

    @Test
    fun `without the lanes switch nothing of them is added, and with the own line off neither`() {
        val off = OwnLineScene()
        off.line.setEnabled(true)
        off.line.onStyleLoaded(Look.NIGHT)
        assertFalse(off.style.calls.any { it.startsWith("lanes.") }, "${off.style.calls}")
        assertNull(off.lanes.positions)
        val lineOff = OwnLineScene()
        lineOff.line.setLanes(true)
        lineOff.line.onStyleLoaded(Look.NIGHT)
        assertTrue(lineOff.style.calls.isEmpty(), "${lineOff.style.calls}")
    }

    @Test
    fun `the lanes switch adds and removes them while the own line is on, the own line's layers made again with them`() {
        val scene = OwnLineScene()
        scene.line.setEnabled(true)
        scene.line.onStyleLoaded(Look.NIGHT)
        scene.style.calls.clear()
        scene.line.setLanes(true)
        // The SDK's line while the own line is made again with its second pair, and the lanes' layers over it.
        assertEquals(listOf("show", "setVisible", "lanes.setLitVisible", "lanes.remove", "add", "lanes.add"), scene.style.calls)
        assertTrue(scene.lanes.exist())
        assertEquals(ownLine + OwnRouteLine.LIT_LAYERS.map { it.id }, scene.style.added?.map { it.id })
        scene.style.calls.clear()
        scene.line.setLanes(false)
        assertEquals(listOf("show", "setVisible", "lanes.remove", "add"), scene.style.calls)
        assertFalse(scene.lanes.exist())
        assertEquals(ownLine, scene.style.added?.map { it.id })
        // The own line turned off takes them with its own, theirs first.
        scene.line.setLanes(true)
        scene.style.calls.clear()
        scene.line.setEnabled(false)
        assertEquals(listOf("show", "setVisible", "lanes.setLitVisible", "lanes.remove", "remove"), scene.style.calls)
        assertFalse(scene.lanes.exist())
    }

    @Test
    fun `a look switch colours the lanes' layers with or without a route`() {
        val scene = OwnLineScene()
        lanesOn(scene)
        scene.style.calls.clear()
        scene.line.setLook(Look.DAY)
        assertEquals(listOf("lanes.setColours"), scene.style.calls)
        assertEquals(MapPalette.lanes(Look.DAY), scene.lanes.shownColours)
        // The same look again writes nothing.
        scene.line.setLook(Look.DAY)
        assertEquals(listOf("lanes.setColours"), scene.style.calls)
        // Without the lanes switch nothing of them is written.
        scene.line.setLanes(false)
        scene.style.calls.clear()
        scene.line.setLook(Look.NIGHT)
        assertTrue(scene.style.calls.none { it.startsWith("lanes.") })
    }

    @Test
    fun `a style that refuses the lanes' layers costs the own line its route, never the drive`() {
        val scene = OwnLineScene()
        scene.lanes.refuse = "add"
        lanesOn(scene)
        // The SDK's line shows, and one error line says where.
        assertTrue(scene.style.primaryShown)
        assertTrue(scene.log.types().contains("error"))
    }
}
