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

    @Test
    fun `the route line's top layer is the navigation SDK's`() {
        assertEquals("mapbox-top-level-route-layer", LayerOrder.ROUTE_LINE_TOP)
    }
}
