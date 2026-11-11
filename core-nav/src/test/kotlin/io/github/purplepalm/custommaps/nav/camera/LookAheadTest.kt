package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.model.LaneZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The model's screen: a phone map 2,400 px high at 3 px a dp, with the padding of a card that has
 * a lane row, 838 px at the top and 459 px at the bottom. Made-up numbers of the model, at
 * latitude 0; the meters and the lane widths are the same at any latitude.
 */
class LookAheadTest {
    private val tuning = CameraTuning.PHONE
    private val height = 2_400.0
    private val carRow = 2_400.0 - 459.0
    private val top = 838.0
    private val ratio = 3.0
    private val lanes14 = MapScale.zoomForLaneDp(14.0, 0.0)

    private fun ahead(zoom: Double, pitch: Double, latitude: Double = 0.0, row: Double = top) =
        LookAhead.metersAhead(zoom, pitch, height, carRow, row, ratio, latitude)

    private fun capFor(meters: Double, pitch: Double, latitude: Double = 0.0) =
        LookAhead.zoomFor(meters, pitch, height, carRow, top, ratio, latitude)

    @Test
    fun `the field of view is the Maps SDK's default`() {
        assertEquals(36.87, Math.toDegrees(LookAhead.FIELD_OF_VIEW_RADIANS), 0.005)
    }

    @Test
    fun `with 14 dp lanes the clear map reaches about 198 m at 45 degrees and 301 m at 55`() {
        assertEquals(198.0, ahead(lanes14, 45.0), 1.0)
        assertEquals(301.0, ahead(lanes14, 55.0), 1.0)
        // The meters depend on the meters per dp only: any latitude at its own 14 dp zoom gives them.
        for (latitude in listOf(25.0, 45.0, -33.0)) {
            val zoom = MapScale.zoomForLaneDp(14.0, latitude)
            assertEquals(ahead(lanes14, 55.0), ahead(zoom, 55.0, latitude), 1e-6, "latitude $latitude")
        }
        // Further out the same rows show twice the road per level.
        assertEquals(2.0 * ahead(lanes14, 55.0), ahead(lanes14 - 1.0, 55.0), 1e-9)
        // Straight down a row shows what it shows on a flat map.
        assertEquals((carRow - top) / ratio * MapScale.metersPerDp(lanes14, 0.0), ahead(lanes14, 0.0), 1e-9)
    }

    @Test
    fun `the zoom for a distance is the inverse of the distance at a zoom`() {
        for (pitch in listOf(0.0, 30.0, 45.0, 55.0, 60.0)) {
            for (meters in listOf(150.0, 300.0, 348.0, 396.0, 1_000.0)) {
                for (latitude in listOf(0.0, 40.0)) {
                    val zoom = capFor(meters, pitch, latitude)
                    assertEquals(meters, ahead(zoom, pitch, latitude), 1e-9, "$meters m at $pitch degrees, latitude $latitude")
                }
            }
        }
    }

    @Test
    fun `the cap of 12 s keeps the design's lanes and levels from the 14 dp zoom`() {
        // Speed, tilt, levels below the 14 dp zoom, lane width at the car: the model of the design.
        val table = listOf(
            listOf(25.0, 55.0, 0.0, 14.0),
            listOf(29.0, 55.0, -0.21, 12.1),
            listOf(33.0, 55.0, -0.39, 10.6),
            listOf(25.0, 45.0, -0.60, 9.2),
            listOf(29.0, 45.0, -0.81, 8.0),
            listOf(33.0, 45.0, -1.00, 7.0),
        )
        for ((speed, pitch, levels, laneDp) in table) {
            val zoom = minOf(lanes14, capFor(speed * tuning.laneLookAheadSeconds, pitch))
            assertEquals(levels, zoom - lanes14, 0.01, "$speed m/s at $pitch degrees")
            assertEquals(laneDp, MapScale.laneDp(zoom, 0.0), 0.06, "$speed m/s at $pitch degrees")
        }
    }

    @Test
    fun `the cap shows at least 12 s of road and is never used under the road's zoom`() {
        for (speed in listOf(25.0, 29.0, 33.0)) {
            val cap = capFor(speed * tuning.laneLookAheadSeconds, tuning.lanePitch)
            for (road in listOf(13.0, 14.0, 15.5, 16.35)) {
                val zoom = ZoomPlanner(tuning).target(LaneZoom.LARGE, road, 0.0, 0.0, false, 1.0, roadFrame = true, lanesCap = cap)
                assertTrue(zoom >= road, "$speed m/s over a road at $road: $zoom")
                val seconds = ahead(zoom, tuning.lanePitch) / speed
                assertTrue(seconds >= tuning.laneLookAheadSeconds - 1e-9, "$speed m/s over a road at $road: $seconds s")
            }
        }
        // A speed whose cap lies under the road's zoom: the road's zoom stays, as without lanes.
        val fast = capFor(110.0 * tuning.laneLookAheadSeconds, tuning.lanePitch)
        assertTrue(fast < 16.35, "cap $fast")
        assertEquals(16.35, ZoomPlanner(tuning).target(LaneZoom.LARGE, 16.6, 0.0, 0.0, false, 1.0, roadFrame = true, lanesCap = fast), 1e-9)
    }

    @Test
    fun `nothing to go by caps nothing`() {
        val inf = Double.POSITIVE_INFINITY
        // No speed, or one that is no speed.
        for (meters in listOf(0.0, -5.0, Double.NaN)) assertEquals(inf, capFor(meters, 55.0), "$meters m")
        // A row at or above the horizon shows road as far as there is any.
        assertEquals(inf, LookAhead.zoomFor(300.0, 89.0, height, carRow, top, ratio, 0.0))
        assertEquals(inf, ahead(lanes14, 89.0))
        // A padding that leaves no map between the car and the row.
        assertEquals(inf, LookAhead.zoomFor(300.0, 55.0, height, 900.0, 900.0, ratio, 0.0))
        assertEquals(inf, LookAhead.zoomFor(300.0, 55.0, height, 800.0, 900.0, ratio, 0.0))
        assertEquals(0.0, LookAhead.metersAhead(lanes14, 55.0, height, 800.0, 900.0, ratio, 0.0))
        // A map that is not laid out yet, or a screen not known.
        for ((h, r) in listOf(0.0 to ratio, Double.NaN to ratio, height to 0.0, height to Double.NaN)) {
            assertEquals(inf, LookAhead.zoomFor(300.0, 55.0, h, carRow, top, r, 0.0), "$h px at $r")
            assertTrue(LookAhead.metersAhead(lanes14, 55.0, h, carRow, top, r, 0.0).isNaN())
        }
        assertEquals(inf, LookAhead.zoomFor(300.0, 55.0, height, Double.NaN, top, ratio, 0.0))
        assertEquals(inf, LookAhead.zoomFor(300.0, Double.NaN, height, carRow, top, ratio, 0.0))
        assertEquals(inf, capFor(300.0, 55.0, latitude = Double.NaN))
    }

    @Test
    fun `the peak speed is the highest of the last 10 s`() {
        val peak = PeakSpeed(tuning)
        assertEquals(25.0, peak.onSpeed(0.0, 25.0))
        // Up at once.
        assertEquals(30.0, peak.onSpeed(1.0, 30.0))
        // Down only 10 s later.
        for (t in 2..11) assertEquals(30.0, peak.onSpeed(t.toDouble(), 25.0), "at $t s")
        assertEquals(25.0, peak.onSpeed(12.0, 25.0))
        assertEquals(26.0, peak.onSpeed(13.0, 26.0))
    }

    @Test
    fun `no speed adds nothing to the peak`() {
        val peak = PeakSpeed(tuning)
        assertNull(peak.onSpeed(0.0, null))
        assertNull(peak.onSpeed(1.0, 0.0))
        assertNull(peak.onSpeed(2.0, -3.0))
        assertNull(peak.onSpeed(3.0, Double.NaN))
        assertNull(peak.onSpeed(4.0, Double.POSITIVE_INFINITY))
        // A speed seen lasts its 10 s while no new one comes.
        assertEquals(20.0, peak.onSpeed(5.0, 20.0))
        assertEquals(20.0, peak.onSpeed(15.0, null))
        assertNull(peak.onSpeed(15.5, null))
    }

    @Test
    fun `a reset forgets every speed`() {
        val peak = PeakSpeed(tuning)
        peak.onSpeed(0.0, 33.0)
        peak.reset()
        assertNull(peak.onSpeed(1.0, null))
        assertEquals(20.0, peak.onSpeed(2.0, 20.0))
    }

    @Test
    fun `updates faster than once a second keep their 10 s`() {
        val peak = PeakSpeed(tuning)
        // Ten updates a second for 30 s, the speed rising by 0.1 m/s with each.
        var last: Double? = null
        for (i in 0 until 300) last = peak.onSpeed(i * 0.1, 10.0 + i * 0.1)
        assertEquals(10.0 + 299 * 0.1, last!!, 1e-9)
        // And the last 10 s of a falling speed: its highest is the one 10 s back.
        val falling = PeakSpeed(tuning)
        for (i in 0 until 300) last = falling.onSpeed(i * 0.1, 40.0 - i * 0.1)
        assertEquals(40.0 - 199 * 0.1, last!!, 0.11)
    }
}
