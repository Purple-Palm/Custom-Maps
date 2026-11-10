package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.engine.mapbox.map.RouteLineWidths.Stop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteLineWidthsTest {

    private fun List<Stop>.pairs() = map { it.zoom to it.width }

    @Test
    fun `a width is the stop's own at a stop and the nearest stop's outside them`() {
        for (stop in RouteLineWidths.LINE) assertEquals(stop.width, RouteLineWidths.widthAt(RouteLineWidths.LINE, stop.zoom), 1e-12)
        assertEquals(3.0, RouteLineWidths.widthAt(RouteLineWidths.LINE, 1.0))
        assertEquals(18.0, RouteLineWidths.widthAt(RouteLineWidths.LINE, 24.0))
        assertEquals(15.5, RouteLineWidths.widthAt(RouteLineWidths.CASING, 16.5), 1e-12)
    }

    @Test
    fun `between two stops the width grows by half again for each zoom level`() {
        // From 10 at zoom 16 to 14 at zoom 19: the steps of the three levels are as 1 to 1.5 to 2.25.
        val at17 = RouteLineWidths.widthAt(RouteLineWidths.LINE, 17.0)
        val at18 = RouteLineWidths.widthAt(RouteLineWidths.LINE, 18.0)
        assertEquals(1.5, (at18 - at17) / (at17 - 10.0), 1e-9)
        assertEquals(1.5, (14.0 - at18) / (at18 - at17), 1e-9)
        assertEquals(10.0 + 4.0 / 4.75, at17, 1e-9)
    }

    @Test
    fun `held, the line is as wide close in as at the zoom it is held from`() {
        val held = RouteLineWidths.heldFrom(RouteLineWidths.LINE, RouteLineWidths.HOLD_ZOOM)
        val atHold = RouteLineWidths.widthAt(RouteLineWidths.LINE, 16.5)
        assertEquals(10.378, atHold, 0.001)
        assertEquals(listOf(4.0 to 3.0, 10.0 to 4.0, 13.0 to 6.0, 16.0 to 10.0, 16.5 to atHold, 22.0 to atHold), held.pairs())
        for (zoom in listOf(16.5, 17.0, 18.9, 20.0, 22.0, 23.0)) assertEquals(atHold, RouteLineWidths.widthAt(held, zoom), 1e-12, "zoom $zoom")
        // The SDK's own line is nearly half as wide again at the junction zoom of Large.
        assertTrue(RouteLineWidths.widthAt(RouteLineWidths.LINE, 18.9) > atHold * 1.3)
    }

    @Test
    fun `held, the line is what it was up to the zoom it is held from`() {
        for (stops in listOf(RouteLineWidths.LINE, RouteLineWidths.CASING)) {
            val held = RouteLineWidths.heldFrom(stops, RouteLineWidths.HOLD_ZOOM)
            var zoom = 3.0
            while (zoom <= 16.5) {
                assertEquals(RouteLineWidths.widthAt(stops, zoom), RouteLineWidths.widthAt(held, zoom), 1e-9, "zoom $zoom")
                zoom += 0.125
            }
        }
    }

    @Test
    fun `the casing is held at its own stop`() {
        val held = RouteLineWidths.heldFrom(RouteLineWidths.CASING, RouteLineWidths.HOLD_ZOOM)
        assertEquals(listOf(10.0 to 7.0, 14.0 to 10.5, 16.5 to 15.5, 22.0 to 15.5), held.pairs())
        assertEquals(15.5, RouteLineWidths.widthAt(held, 19.0))
        assertEquals(24.0, RouteLineWidths.widthAt(RouteLineWidths.CASING, 19.0))
    }

    @Test
    fun `stops stay in rising order when held past the last of them`() {
        val held = RouteLineWidths.heldFrom(RouteLineWidths.LINE, 22.0)
        assertEquals(listOf(4.0, 10.0, 13.0, 16.0, 19.0, 22.0, 23.0), held.map { it.zoom })
        assertEquals(18.0, held.last().width)
        assertTrue(held.zipWithNext().all { (a, b) -> a.zoom < b.zoom })
    }

    /** The stops of one of the navigation SDK's own width expressions, each written there as a product of two numbers. */
    private fun sdkStops(json: String): List<Pair<Double, Double>> {
        assertTrue(json.startsWith("""["interpolate",["exponential",1.5],["zoom"],"""), json)
        return Regex("""([0-9.]+),\["\*",([0-9.]+),([0-9.]+)]""").findAll(json)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() * it.groupValues[3].toDouble() }
            .toList()
    }

    @Test
    fun `the stops are the navigation SDK's own`() {
        // A new SDK with other widths would leave the held line at the old ones: this says so.
        val sdk = RouteLineWidths.sdk
        assertEquals(RouteLineWidths.LINE.pairs(), sdkStops(sdk.routeLineScaleExpression.toJson()))
        assertEquals(RouteLineWidths.LINE.pairs(), sdkStops(sdk.routeTrafficLineScaleExpression.toJson()))
        assertEquals(RouteLineWidths.LINE.pairs(), sdkStops(sdk.alternativeRouteLineScaleExpression.toJson()))
        assertEquals(RouteLineWidths.LINE.pairs(), sdkStops(sdk.alternativeRouteTrafficLineScaleExpression.toJson()))
        assertEquals(RouteLineWidths.CASING.pairs(), sdkStops(sdk.routeCasingLineScaleExpression.toJson()))
        assertEquals(RouteLineWidths.CASING.pairs(), sdkStops(sdk.alternativeRouteCasingLineScaleExpression.toJson()))
    }

    @Test
    fun `the expression has the curve and the stops in the order the map reads them`() {
        assertEquals(
            """["interpolate",["exponential",1.5],["zoom"],10.0,7.0,14.0,10.5,16.5,15.5,22.0,15.5]""",
            RouteLineWidths.expression(RouteLineWidths.heldFrom(RouteLineWidths.CASING, 16.5)).toJson(),
        )
    }

    @Test
    fun `with lane details every line and casing is held, and the SDK's widths are untouched`() {
        val held = RouteLineWidths.heldCloseIn
        val line = RouteLineWidths.expression(RouteLineWidths.heldFrom(RouteLineWidths.LINE, 16.5)).toJson()
        val casing = RouteLineWidths.expression(RouteLineWidths.heldFrom(RouteLineWidths.CASING, 16.5)).toJson()
        assertEquals(line, held.routeLineScaleExpression.toJson())
        assertEquals(line, held.routeTrafficLineScaleExpression.toJson())
        assertEquals(line, held.alternativeRouteLineScaleExpression.toJson())
        assertEquals(line, held.alternativeRouteTrafficLineScaleExpression.toJson())
        assertEquals(casing, held.routeCasingLineScaleExpression.toJson())
        assertEquals(casing, held.alternativeRouteCasingLineScaleExpression.toJson())
        assertNotEquals(RouteLineWidths.sdk, held)
        assertEquals(RouteLineWidths.sdk.routeBlurScaleExpression.toJson(), held.routeBlurScaleExpression.toJson())
    }
}
