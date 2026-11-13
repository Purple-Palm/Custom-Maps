package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.LaneMeasureGate.Verdict
import io.github.purplepalm.custommaps.nav.lanes.LaneMeasureGate.View
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Made-up views of a measuring drive: render p95s, missed frames and uploads in milliseconds, none measured on a phone. */
class LaneMeasureGateTest {

    private fun view(drawn: Boolean, p95: Double = 8.0, missed: Double = 4.0, upload: Double = 2.0, look: Look = Look.NIGHT, counts: Boolean = true) =
        View(look, drawn, p95, missed, upload, counts)

    /** [n] views of each kind: the drawing views as [drawn] makes them, the others plain at 8 ms and 4 missed a second. */
    private fun drive(n: Int = LaneMeasureGate.MIN_VIEWS, look: Look = Look.NIGHT, drawn: (Int) -> View = { view(true, look = look) }): List<View> =
        (0 until n).map(drawn) + (0 until n).map { view(false, look = look) }

    @Test
    fun `the gate's numbers are the design's`() {
        assertEquals(6, LaneMeasureGate.MIN_VIEWS)
        assertEquals(1.0, LaneMeasureGate.RENDER_P95_SLACK_MS)
        assertEquals(0.10, LaneMeasureGate.MISSED_VSYNC_SLACK)
        assertEquals(8.0, LaneMeasureGate.UPLOAD_MAX_MS)
    }

    @Test
    fun `at least six counted views of each kind in one look, else too few`() {
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive(6)))
        assertEquals(Verdict.TOO_FEW_VIEWS, LaneMeasureGate.verdict(drive(5)))
        // Six of one kind and five of the other.
        assertEquals(Verdict.TOO_FEW_VIEWS, LaneMeasureGate.verdict(drive(6).drop(1)))
        assertEquals(Verdict.TOO_FEW_VIEWS, LaneMeasureGate.verdict(drive(6).dropLast(1)))
        // A view that does not count is no view of its kind, however good its numbers.
        assertEquals(Verdict.TOO_FEW_VIEWS, LaneMeasureGate.verdict(drive(6) { i -> view(true, counts = i > 0) }))
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive(6) + view(true, p95 = 99.0, counts = false)))
    }

    @Test
    fun `the drawing views' median render p95 may lie up to one millisecond above the others', not more`() {
        // The others' median is 8 ms: 9 ms is on the border, a sixteenth more is past it.
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive { view(true, p95 = 9.0) }))
        assertEquals(Verdict.FAIL, LaneMeasureGate.verdict(drive { view(true, p95 = 9.0625) }))
        // The median, not the worst: one view far above with the rest at 9 ms passes.
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive { i -> view(true, p95 = if (i == 0) 30.0 else 9.0) }))
    }

    @Test
    fun `the drawing views may miss up to a tenth more display frames a second, not more`() {
        // The others' median is 4 a second: 4.4 is on the border, 4.5 past it.
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive { view(true, missed = 4.4) }))
        assertEquals(Verdict.FAIL, LaneMeasureGate.verdict(drive { view(true, missed = 4.5) }))
        // None missed without the drawing leaves no room for one with it.
        val none = (0 until 6).map { view(true, missed = 0.0) } + (0 until 6).map { view(false, missed = 0.0) }
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(none))
        assertEquals(Verdict.FAIL, LaneMeasureGate.verdict(none.mapIndexed { i, v -> if (i < 6) v.copy(missedPerSecond = 0.5) else v }))
    }

    @Test
    fun `no upload of a drawing view may take more than eight milliseconds`() {
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive { view(true, upload = 8.0) }))
        assertEquals(Verdict.FAIL, LaneMeasureGate.verdict(drive { i -> view(true, upload = if (i == 3) 8.0625 else 1.0) }))
        // The views without the drawing are not its uploads; a view without a statistics sample has none.
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive().map { if (it.drawn) it else it.copy(uploadMaxMs = 20.0) }))
        assertEquals(Verdict.PASS, LaneMeasureGate.verdict(drive { view(true, upload = Double.NaN) }))
    }

    @Test
    fun `a drive's verdict fails where one look fails, passes where one passes, else has too few views`() {
        val night = drive(look = Look.NIGHT)
        val dayFails = drive(look = Look.DAY) { view(true, p95 = 12.0, look = Look.DAY) }
        assertEquals(Verdict.PASS, LaneMeasureGate.overall(night))
        assertEquals(Verdict.FAIL, LaneMeasureGate.overall(night + dayFails))
        // Too few by day does not undo a pass at night.
        assertEquals(Verdict.PASS, LaneMeasureGate.overall(night + view(true, look = Look.DAY)))
        // The looks are never mixed: three of each kind by day and at night are too few in either.
        assertEquals(Verdict.TOO_FEW_VIEWS, LaneMeasureGate.overall(drive(3, Look.DAY) + drive(3, Look.NIGHT)))
        assertEquals(Verdict.TOO_FEW_VIEWS, LaneMeasureGate.overall(emptyList()))
    }

    @Test
    fun `the trip summary has the verdict, the counts and each look's medians`() {
        val views = drive { i -> view(true, p95 = 8.5 + i * 0.1, upload = 3.0 + i) } + view(false, counts = false)
        val summary = LaneMeasureGate.summary(views)
        assertEquals("pass", summary["gate"])
        assertEquals(13, summary["views"])
        assertEquals(12, summary["counted"])
        @Suppress("UNCHECKED_CAST")
        val night = summary["NIGHT"] as Map<String, Any?>
        assertEquals(
            mapOf(
                "gate" to "pass",
                "drawn" to 6,
                "notDrawn" to 6,
                "drawnP95Ms" to 8.8,
                "notDrawnP95Ms" to 8.0,
                "drawnMissedPerS" to 4.0,
                "notDrawnMissedPerS" to 4.0,
                "uploadMaxMs" to 8.0,
            ),
            night,
        )
        assertTrue("DAY" !in summary, "a look without a view is left out")
    }

    @Test
    fun `the median is the middle value or the mean of the two middle ones`() {
        assertEquals(2.0, LaneMeasureGate.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, LaneMeasureGate.median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertTrue(LaneMeasureGate.median(emptyList()).isNaN())
    }

    @Test
    fun `the percentile is the nearest rank of the values given`() {
        val sorted = DoubleArray(20) { it + 1.0 }
        // 95 % of 20 is the 19th value; a share just above takes the 20th.
        assertEquals(19.0, LaneMeasureGate.percentile(sorted, 20, 0.95))
        assertEquals(20.0, LaneMeasureGate.percentile(sorted, 20, 0.951))
        assertEquals(1.0, LaneMeasureGate.percentile(sorted, 1, 0.95))
        assertTrue(LaneMeasureGate.percentile(sorted, 0, 0.95).isNaN())
    }
}
