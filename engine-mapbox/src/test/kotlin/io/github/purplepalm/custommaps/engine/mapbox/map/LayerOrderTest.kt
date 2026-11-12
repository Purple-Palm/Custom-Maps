package io.github.purplepalm.custommaps.engine.mapbox.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The order of the route line's and the maneuver arrow's layers in the style's middle slot, first drawn first. */
class LayerOrderTest {

    private val line = listOf(
        "mapbox-bottom-level-route-layer",
        "mapbox-layerGroup-1-casing",
        "mapbox-layerGroup-1-main",
        "mapbox-layerGroup-1-traffic",
        "mapbox-masking-layer-main",
        "mapbox-top-level-route-layer",
    )
    private val arrow = listOf(
        "mapbox-navigation-arrow-shaft-casing-layer",
        "mapbox-navigation-arrow-head-casing-layer",
        "mapbox-navigation-arrow-shaft-layer",
        "mapbox-navigation-arrow-head-layer",
    )

    @Test
    fun `the arrow made after the route line lies above it`() {
        assertEquals(true, LayerOrder.arrowAboveLine(line + arrow))
    }

    @Test
    fun `the arrow made before the route line lies under it`() {
        // As on a map whose arrow got its layers a moment before the route line did.
        assertEquals(false, LayerOrder.arrowAboveLine(arrow + line))
    }

    @Test
    fun `one arrow layer under the line is enough to count as under it`() {
        val mixed = listOf(arrow[0]) + line + arrow.drop(1)
        assertFalse(LayerOrder.arrowAboveLine(mixed)!!)
    }

    @Test
    fun `other layers of the slot change nothing`() {
        val ids = listOf("custommaps-other") + line + listOf("custommaps-between") + arrow + listOf("custommaps-last")
        assertTrue(LayerOrder.arrowAboveLine(ids)!!)
    }

    @Test
    fun `without a route line or without an arrow there is no order to tell`() {
        assertNull(LayerOrder.arrowAboveLine(line))
        assertNull(LayerOrder.arrowAboveLine(arrow))
        assertNull(LayerOrder.arrowAboveLine(emptyList()))
    }

    private val own = listOf("custommaps-own-route-casing", "custommaps-own-route-line")

    @Test
    fun `the own route line lies above the SDK's route line and below the arrow, its casing first`() {
        assertEquals(true, LayerOrder.ownLineInPlace(line + own + arrow, own))
        assertEquals(false, LayerOrder.ownLineInPlace(line + own.reversed() + arrow, own), "the line under its casing")
        assertEquals(false, LayerOrder.ownLineInPlace(line + arrow + own, own), "over the arrow")
        assertEquals(false, LayerOrder.ownLineInPlace(own + line + arrow, own), "under the SDK's line")
        assertEquals(false, LayerOrder.ownLineInPlace(listOf(arrow[0]) + line + own + arrow.drop(1), own), "one arrow layer under it")
        assertEquals(true, LayerOrder.ownLineInPlace(line + own, own), "no arrow yet")
        assertEquals(false, LayerOrder.ownLineInPlace(own + arrow, own), "no route line")
    }

    @Test
    fun `without the own route line there is no place to tell`() {
        assertNull(LayerOrder.ownLineInPlace(line + arrow, own))
        assertNull(LayerOrder.ownLineInPlace(line + own.take(1) + arrow, own))
        assertEquals(own, OwnRouteLine.LAYERS.map { it.id })
    }

    @Test
    fun `the route line's top layer is the navigation SDK's`() {
        assertEquals("mapbox-top-level-route-layer", LayerOrder.ROUTE_LINE_TOP)
        assertEquals("mapbox-bottom-level-route-layer", LayerOrder.ROUTE_LINE_BOTTOM)
    }

    private val under = listOf("custommaps-lanes-asphalt", "custommaps-lanes-lit", "custommaps-lanes-paint")
    private val over = listOf("custommaps-lanes-arrows", "custommaps-lanes-arrow-edges")

    @Test
    fun `the own lanes lie under the SDK's route line, their arrows over the own line and under the maneuver arrow`() {
        assertEquals(true, LayerOrder.lanesInPlace(under + line + own + over + arrow, under, over, own))
        // Without the own route line in the style the arrows still have to lie over the SDK's line.
        assertEquals(true, LayerOrder.lanesInPlace(under + line + over + arrow, under, over, own))
        assertEquals(true, LayerOrder.lanesInPlace(under + line + own + over, under, over, own), "no maneuver arrow yet")
    }

    @Test
    fun `any layer of the own lanes out of its place is out of place`() {
        val cases = mapOf(
            "the asphalt over the paint" to listOf(under[2], under[1], under[0]) + line + own + over + arrow,
            "a layer inside the SDK's route line" to under.take(2) + line.take(2) + under.drop(2) + line.drop(2) + own + over + arrow,
            "the arrows under the own line" to under + line + over + own + arrow,
            "the arrows' edge under the arrows" to under + line + own + over.reversed() + arrow,
            "the arrows over the maneuver arrow" to under + line + own + arrow + over,
            "an arrow layer under the lane arrows" to under + line + own + listOf(arrow[0]) + over + arrow.drop(1),
            "no SDK route line" to under + own + over + arrow,
        )
        for ((what, ids) in cases) assertEquals(false, LayerOrder.lanesInPlace(ids, under, over, own), what)
    }

    @Test
    fun `without every layer of the own lanes there is no place to tell`() {
        assertNull(LayerOrder.lanesInPlace(line + own + arrow, under, over, own))
        assertNull(LayerOrder.lanesInPlace(under + line + own + over.take(1) + arrow, under, over, own))
    }
}
