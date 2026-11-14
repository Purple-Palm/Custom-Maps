package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.cos
import kotlin.math.log2
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The overview that stands while a new fit still lies inside it. */
class OverviewHoldTest {
    private val width = 1_080.0
    private val height = 2_000.0
    private val ratio = 3.0

    /** The free half of the view: (1,080 - 2 x 42) / 2 and (2,000 - 300 - 500) / 2 pixels. */
    private val halfWidth = 498.0
    private val halfHeight = 600.0
    private val kept = OverviewFit(lat = 0.0, lon = 0.0, zoom = 12.0, top = 300.0, left = 42.0, bottom = 500.0, right = 42.0)

    private fun keeps(fit: OverviewFit, from: OverviewFit = kept, w: Double = width, h: Double = height, r: Double = ratio) =
        OverviewHold.keeps(from, fit, w, h, r)

    private fun metersPerPixel(fit: OverviewFit) = MapScale.metersPerDp(fit.zoom, fit.lat) / ratio

    /** [fit] with its center moved east by [share] of the kept view's free half width. */
    private fun east(fit: OverviewFit, share: Double, from: OverviewFit = kept) =
        fit.copy(lon = fit.lon + share * halfWidth * metersPerPixel(from) / (METERS_PER_DEGREE * cos(Math.toRadians(from.lat))))

    /** [fit] with its center moved north by [share] of the kept view's free half height. */
    private fun north(fit: OverviewFit, share: Double) = fit.copy(lat = fit.lat + share * halfHeight * metersPerPixel(kept) / METERS_PER_DEGREE)

    @Test
    fun `the same fit is kept, and so is one less than 0,25 levels closer`() {
        assertTrue(keeps(kept))
        assertTrue(keeps(kept.copy(zoom = 12.1)))
        assertTrue(keeps(kept.copy(zoom = 12.24)))
        assertFalse(keeps(kept.copy(zoom = 12.25)))
        assertFalse(keeps(kept.copy(zoom = 13.0)))
    }

    @Test
    fun `a fit wider by more than 0,02 levels is taken at once`() {
        assertTrue(keeps(kept.copy(zoom = 11.985)))
        assertFalse(keeps(kept.copy(zoom = 11.975)))
        assertFalse(keeps(kept.copy(zoom = 10.0)))
    }

    @Test
    fun `a center out of the kept view by more than 3 percent of half of it is taken at once`() {
        // The same zoom: the frame may sit only the slack off.
        assertTrue(keeps(east(kept, 0.029)))
        assertFalse(keeps(east(kept, 0.031)))
        assertTrue(keeps(east(kept, -0.029)))
        assertFalse(keeps(east(kept, -0.031)))
        assertTrue(keeps(north(kept, 0.029)))
        assertFalse(keeps(north(kept, 0.031)))
        // 0.2 levels closer: the smaller frame may sit 1 - 2^-0.2 more off center, about 13 percent.
        val closer = kept.copy(zoom = 12.2)
        assertTrue(keeps(east(closer, 0.155)))
        assertFalse(keeps(east(closer, 0.165)))
        assertTrue(keeps(north(closer, -0.155)))
        assertFalse(keeps(north(closer, -0.165)))
        // Measured on the ground: at 40 degrees a degree of longitude is shorter.
        val north40 = kept.copy(lat = 40.0)
        assertTrue(keeps(east(north40, 0.029, from = north40), from = north40))
        assertFalse(keeps(east(north40, 0.031, from = north40), from = north40))
    }

    @Test
    fun `any change of the padding takes the new fit`() {
        assertTrue(keeps(kept.copy(top = 300.4)))
        assertFalse(keeps(kept.copy(top = 301.0)))
        assertFalse(keeps(kept.copy(left = 43.0)))
        assertFalse(keeps(kept.copy(bottom = 436.0)))
        assertFalse(keeps(kept.copy(right = 41.0)))
    }

    @Test
    fun `a value that is not a number, or a map with no room, keeps nothing`() {
        val nan = Double.NaN
        for (fit in listOf(
            kept.copy(lat = nan), kept.copy(lon = nan), kept.copy(zoom = nan), kept.copy(top = nan),
            kept.copy(left = nan), kept.copy(bottom = nan), kept.copy(right = nan),
        )) {
            assertFalse(keeps(fit), "$fit")
            assertFalse(keeps(kept, from = fit), "kept $fit")
        }
        assertFalse(keeps(kept, w = 84.0))
        assertFalse(keeps(kept, h = 800.0))
        assertFalse(keeps(kept, r = 0.0))
        assertFalse(keeps(kept, r = nan))
    }

    @Test
    fun `along a straight route the overview stands, holds the rest of the route, and glides a few times`() {
        // A made-up 20 km route along the equator; the overview fits the rest of it, the car to the end, into the free width.
        val length = 20_000.0
        fun fitAt(carMeters: Double): OverviewFit {
            val rest = length - carMeters
            val metersPerDp = rest / (2.0 * halfWidth) * ratio
            val zoom = log2(MapScale.metersPerDp(0.0, 0.0) / metersPerDp)
            return kept.copy(lon = (carMeters + rest / 2.0) / METERS_PER_DEGREE, zoom = zoom)
        }
        var shown = fitAt(0.0)
        var glides = 0
        var car = 0.0
        while (car < length - 1_000.0) {
            car += 20.0
            val fit = fitAt(car)
            if (!keeps(fit, from = shown)) {
                // Only ever closer: the rest of a route gets shorter.
                assertTrue(fit.zoom - shown.zoom >= OverviewHold.REFIT_ZOOM - 1e-9, "glide at $car m")
                shown = fit
                glides += 1
            }
            // The kept view still holds the car and the end of the route.
            val half = halfWidth * MapScale.metersPerDp(shown.zoom, 0.0) / ratio
            val center = shown.lon * METERS_PER_DEGREE
            assertTrue(car >= center - half * (1 + OverviewHold.EDGE_SLACK) && length <= center + half * (1 + OverviewHold.EDGE_SLACK), "at $car m")
        }
        // From 20 km to 1 km left, a glide each time the rest is about a sixth shorter.
        assertTrue(glides in 15..20, "$glides glides")
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_319.49
    }
}
