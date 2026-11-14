package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.LaneMeasure.End
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A measuring drive on a made-up clock, with made-up frame times; no map, no phone. */
class LaneMeasureTest {

    private class Map : LaneMeasure.Effects {
        val calls = mutableListOf<String>()
        val lines = mutableListOf<kotlin.collections.Map<String, Any?>>()
        val views = mutableListOf<LaneMeasureGate.View>()
        var shown = true
        var details = false
        var styleSays: Boolean? = false

        override fun startStats() {
            calls += "start"
        }

        override fun stopStats() {
            calls += "stop"
        }

        override fun showDrawing(shown: Boolean) {
            calls += if (shown) "show" else "hide"
            this.shown = shown
        }

        override fun checkDetails(on: Boolean) {
            calls += "details $on"
            details = on
            styleSays = on
        }

        override fun detailsShown(): Boolean? = styleSays

        override fun log(fields: kotlin.collections.Map<String, Any?>) {
            lines += fields
        }

        override fun report(view: LaneMeasureGate.View) {
            views += view
        }

        val viewLines get() = lines.filter { "view" in it }
    }

    private var now = 0L
    private val map = Map()
    private val measure = LaneMeasure(map) { now }

    /** The measuring switch on, the lanes drawn by the app, the map on screen and guidance started. */
    private fun measuring() {
        measure.setSwitch(true)
        measure.setAppDraws(true)
        measure.setOnScreen(true, End.BACKGROUND)
        measure.onGuidance(true)
    }

    private fun seconds(n: Int, frameMs: Double = 8.0, frames: Int = 60, missed: Long = 2, positions: Int = 60) {
        repeat(n) {
            now += SECOND
            measure.onSecond(List(frames) { frameMs }, missed, positions)
        }
    }

    /** A close view of [n] seconds that lets go. */
    private fun view(n: Int = 10, runUp: Boolean = true, look: Look = Look.NIGHT) {
        measure.onCloseView(true, runUp, look)
        seconds(n)
        measure.onCloseView(false, runUp, look)
        // Well apart from the next view.
        now += 30 * SECOND
    }

    @Test
    fun `with the switch off, or another choice, or no drive, nothing is measured and nothing is said`() {
        // Two of the three each time: the switch off, another choice of Lanes on the map, no drive.
        val setups = listOf<(LaneMeasure) -> Unit>(
            { it.setAppDraws(true); it.onGuidance(true) },
            { it.setSwitch(true); it.onGuidance(true) },
            { it.setSwitch(true); it.setAppDraws(true) },
        )
        for (setup in setups) {
            val fresh = Map()
            val off = LaneMeasure(fresh) { now }
            off.setOnScreen(true, End.BACKGROUND)
            setup(off)
            off.onCloseView(true, true, Look.NIGHT)
            off.onSecond(List(60) { 8.0 }, 1, 60)
            off.onSample(3.0, 1.0, 20)
            off.onWindowLoaded(100, 1_000)
            off.onCloseView(false, true, Look.NIGHT)
            off.onCameraStyle()
            off.setOnScreen(false, End.BACKGROUND)
            off.release()
            assertEquals(emptyList<String>(), fresh.calls)
            assertTrue(fresh.lines.isEmpty() && fresh.views.isEmpty())
            assertFalse(off.measuring)
            assertTrue(off.drawingShown)
        }
    }

    @Test
    fun `a measuring drive checks Mapbox's lane details under the drawing once and says so, and gives them back at its end`() {
        measuring()
        assertEquals(listOf("details true"), map.calls)
        assertEquals(listOf(mapOf("check" to LaneMeasure.CHECK_DETAILS, "on" to true)), map.lines)
        view()
        view()
        measure.onGuidance(false)
        assertEquals(1, map.lines.count { "check" in it })
        assertEquals(1, map.calls.count { it == "details true" })
        assertEquals("details false", map.calls.last())
        // The next drive checks again.
        measure.onGuidance(true)
        assertEquals(2, map.lines.count { "check" in it })
    }

    @Test
    fun `every other close view shows the drawing, the first one does, and it changes between the views`() {
        measuring()
        map.calls.clear()
        val drawn = mutableListOf<Boolean>()
        repeat(4) { i ->
            measure.onCloseView(true, i % 2 == 0, Look.NIGHT)
            // Nothing changes the drawing while a view is measured.
            assertEquals(listOf("start"), map.calls)
            seconds(5)
            measure.onCloseView(false, false, Look.NIGHT)
            drawn += map.viewLines.last()["drawn"] as Boolean
            // Changed right at the view's end, ready for the next one.
            assertEquals(listOf("start", "stop", if (i % 2 == 0) "hide" else "show"), map.calls)
            map.calls.clear()
            now += 30 * SECOND
        }
        assertEquals(listOf(true, false, true, false), drawn)
        assertEquals(listOf(1, 2, 3, 4), map.viewLines.map { it["view"] })
        assertEquals(listOf("run_up", "junction", "run_up", "junction"), map.viewLines.map { it["kind"] })
    }

    @Test
    fun `a view's render p95 and longest frame are worked out over all its frame times, never from each second's`() {
        measuring()
        measure.onCloseView(true, true, Look.DAY)
        // One second of 100 frames at 10 ms, one of 20 frames at 30 ms: each second's own p95 is 10 and 30, their mean 20.
        now += SECOND
        measure.onSecond(List(100) { 10.0 }, 1, 50)
        now += SECOND
        measure.onSecond(List(20) { 30.0 }, 5, 40)
        // Three seconds long, the camera line of its third not in yet: a whole view.
        now += SECOND
        measure.onCloseView(false, true, Look.DAY)
        val line = map.viewLines.single()
        // 95 % of 120 frames is the 114th, and the 101st on are 30 ms.
        assertEquals(30.0, line["p95Ms"])
        assertEquals(30.0, line["maxMs"])
        assertEquals(120, line["frames"])
        assertEquals(2, line["seconds"])
        assertEquals(3_000L, line["ms"])
        assertEquals(6L, line["missedVsync"])
        assertEquals(3.0, line["missedPerS"])
        assertEquals(90L, line["carPositions"])
        assertEquals(Look.DAY, line["look"])
        val view = map.views.single()
        assertEquals(30.0, view.renderP95Ms)
        assertEquals(3.0, view.missedPerSecond)
        assertEquals(Look.DAY, view.look)
        assertTrue(view.drawn && view.counts)
    }

    @Test
    fun `the statistics samples give the largest upload, the time making programs and the programs held`() {
        measuring()
        measure.onCloseView(true, true, Look.NIGHT)
        seconds(3)
        measure.onSample(2.5, 12.0, 40)
        measure.onSample(6.5, 0.5, 42)
        measure.onSample(null, null, null)
        measure.onWindowLoaded(120, 300_000)
        measure.onWindowLoaded(80, 410_000)
        measure.onCloseView(false, true, Look.NIGHT)
        val line = map.viewLines.single()
        assertEquals(6.5, line["uploadMaxMs"])
        assertEquals(12.5, line["programsMs"])
        assertEquals(42L, line["programs"])
        assertEquals(2, line["windows"])
        assertEquals(120L, line["windowLoadMaxMs"])
        assertEquals(410_000, line["windowBytesMax"])
        assertEquals(6.5, map.views.single().uploadMaxMs)
        // A view without samples or windows says so by leaving them out.
        measure.onCloseView(true, true, Look.NIGHT)
        seconds(1)
        measure.onCloseView(false, true, Look.NIGHT)
        val bare = map.viewLines.last()
        assertEquals(0, bare["windows"])
        assertTrue(listOf("uploadMaxMs", "programsMs", "programs", "windowLoadMaxMs", "windowBytesMax").none { it in bare })
        assertTrue(map.views.last().uploadMaxMs.isNaN())
    }

    /** Every way a view ends, each with what ends it. */
    private val endings = listOf<Pair<End, (LaneMeasure) -> Unit>>(
        End.LET_GO to { it.onCloseView(false, true, Look.NIGHT) },
        End.GUIDANCE_END to { it.onGuidance(false) },
        End.SWITCH to { it.setSwitch(false) },
        End.SETTING to { it.setAppDraws(false) },
        End.BACKGROUND to { it.setOnScreen(false, End.BACKGROUND) },
        End.COVERED to { it.setOnScreen(false, End.COVERED) },
        End.AWAY to { it.setOnScreen(false, End.AWAY) },
        End.CAMERA_STYLE to { it.onCameraStyle() },
        End.RELEASED to { it.release() },
    )

    /** A fresh measuring on [map]: the switch on, the lanes drawn by the app, the map on screen and guidance started. */
    private fun measuringOn(map: Map): LaneMeasure = LaneMeasure(map) { now }.apply {
        setSwitch(true)
        setAppDraws(true)
        setOnScreen(true, End.BACKGROUND)
        onGuidance(true)
    }

    @Test
    fun `the collection stops and the line says why on every way a view ends`() {
        assertEquals(End.entries.toSet(), endings.map { it.first }.toSet())
        for ((end, ending) in endings) {
            val fresh = Map()
            val one = measuringOn(fresh)
            one.onCloseView(true, true, Look.NIGHT)
            one.onSecond(List(60) { 8.0 }, 1, 60)
            ending(one)
            assertEquals(1, fresh.calls.count { it == "start" }, "$end")
            assertEquals(1, fresh.calls.count { it == "stop" }, "$end")
            assertEquals(end.logName, fresh.viewLines.single()["end"], "$end")
            assertFalse(one.viewOpen, "$end")
            // What comes after a view ended counts into none.
            one.onSecond(List(60) { 8.0 }, 1, 60)
            one.onCloseView(false, true, Look.NIGHT)
            assertEquals(1, fresh.viewLines.size, "$end")
        }
    }

    @Test
    fun `the measuring's end shows the drawing again, and a view cut short leaves the next to a close view of its own`() {
        measuring()
        view()
        // Hidden for the second view, which Settings cuts.
        assertFalse(map.shown)
        measure.onCloseView(true, false, Look.NIGHT)
        seconds(2)
        measure.setOnScreen(false, End.COVERED)
        measure.setOnScreen(true, End.COVERED)
        // Settings closed while the close view still holds: no view begins in it.
        measure.onCloseView(true, false, Look.NIGHT)
        seconds(2)
        assertFalse(measure.viewOpen)
        measure.onCloseView(false, false, Look.NIGHT)
        now += 30 * SECOND
        measure.onCloseView(true, true, Look.NIGHT)
        assertTrue(measure.viewOpen)
        assertEquals(2, map.viewLines.size)
        // Guidance ends in the third view: it ends, the drawing shows again, and the check of Mapbox's lane details ends.
        measure.onGuidance(false)
        assertTrue(map.shown)
        assertFalse(map.details)
        assertEquals(listOf("let_go", "covered", "guidance_end"), map.viewLines.map { it["end"] })
    }

    @Test
    fun `a close view under way when the measuring begins is not measured`() {
        measure.setSwitch(true)
        measure.setAppDraws(true)
        measure.setOnScreen(true, End.BACKGROUND)
        measure.onCloseView(true, true, Look.NIGHT)
        measure.onGuidance(true)
        measure.onCloseView(true, true, Look.NIGHT)
        assertFalse(measure.viewOpen)
        measure.onCloseView(false, true, Look.NIGHT)
        measure.onCloseView(true, true, Look.NIGHT)
        assertTrue(measure.viewOpen)
    }

    @Test
    fun `nothing is measured while the map is off screen, and a view begins when it is back`() {
        measuring()
        measure.setOnScreen(false, End.BACKGROUND)
        measure.onCloseView(true, true, Look.NIGHT)
        assertFalse(measure.viewOpen)
        measure.setOnScreen(true, End.BACKGROUND)
        measure.onCloseView(true, true, Look.NIGHT)
        assertTrue(measure.viewOpen)
    }

    @Test
    fun `a view that begins less than five seconds after the drawing changed, or has no frames, is not counted`() {
        assertEquals(5_000_000_000L, LaneMeasure.SETTLE_NANOS)
        measuring()
        view()
        // The drawing was hidden as the first view let go; the next view begins on the border, then a nanosecond early.
        now -= 30 * SECOND
        now += LaneMeasure.SETTLE_NANOS
        measure.onCloseView(true, true, Look.NIGHT)
        seconds(3)
        measure.onCloseView(false, true, Look.NIGHT)
        assertEquals(true, map.viewLines.last()["counts"])
        // The drawing shows again at that view's end.
        now += LaneMeasure.SETTLE_NANOS - 1
        measure.onCloseView(true, true, Look.NIGHT)
        seconds(3)
        measure.onCloseView(false, true, Look.NIGHT)
        assertEquals(false, map.viewLines.last()["counts"])
        assertFalse(map.views.last().counts)
        // No frames at all, though it lasted four seconds and its close view let it go: logged, not counted.
        now += 30 * SECOND
        measure.onCloseView(true, true, Look.NIGHT)
        now += 4 * SECOND
        measure.onCloseView(false, true, Look.NIGHT)
        assertEquals(0, map.viewLines.last()["frames"])
        assertEquals(4_000L, map.viewLines.last()["ms"])
        assertEquals("let_go", map.viewLines.last()["end"])
        assertNull(map.viewLines.last()["p95Ms"])
        assertEquals(false, map.viewLines.last()["counts"])
        assertFalse(map.views.last().counts)
    }

    @Test
    fun `a view counts only when its close view let go after three seconds or more`() {
        assertEquals(3_000_000_000L, LaneMeasure.MIN_VIEW_NANOS)
        measuring()
        // Two seconds, let go: too short to count.
        view(2)
        assertEquals("let_go", map.viewLines.last()["end"])
        assertEquals(2_000L, map.viewLines.last()["ms"])
        assertEquals(false, map.viewLines.last()["counts"])
        assertFalse(map.views.last().counts)
        // Three seconds, let go: a whole view.
        view(3)
        assertEquals(3_000L, map.viewLines.last()["ms"])
        assertEquals(true, map.viewLines.last()["counts"])
        assertTrue(map.views.last().counts)
        // A nanosecond short of three seconds, with three camera seconds in it: not.
        measure.onCloseView(true, true, Look.NIGHT)
        seconds(2)
        now += SECOND - 1
        measure.onSecond(List(60) { 8.0 }, 2, 60)
        measure.onCloseView(false, true, Look.NIGHT)
        assertEquals(3, map.viewLines.last()["seconds"])
        assertEquals(2_999L, map.viewLines.last()["ms"])
        assertEquals(false, map.viewLines.last()["counts"])
        assertEquals(listOf(false, true, false), map.views.map { it.counts })
    }

    @Test
    fun `a view that guidance ends after a second, or the list cuts after four, is logged but not counted`() {
        measuring()
        measure.onCloseView(true, true, Look.NIGHT)
        seconds(1)
        measure.onGuidance(false)
        val ended = map.viewLines.single()
        assertEquals("guidance_end", ended["end"])
        assertEquals(1, ended["seconds"])
        assertEquals(1_000L, ended["ms"])
        assertEquals(60, ended["frames"])
        assertEquals(false, ended["counts"])
        assertFalse(map.views.single().counts)
        // The close view lets go after the drive; in the next drive, a view the list cuts as the phone's map is put away.
        measure.onCloseView(false, true, Look.NIGHT)
        now += 30 * SECOND
        measure.onGuidance(true)
        measure.onCloseView(true, false, Look.NIGHT)
        seconds(4)
        measure.setOnScreen(false, End.AWAY)
        val away = map.viewLines.last()
        assertEquals("away", away["end"])
        assertEquals(4_000L, away["ms"])
        assertEquals(false, away["counts"])
        assertFalse(map.views.last().counts)
    }

    @Test
    fun `a whole close view cut short any other way than its letting go never counts, however long it was`() {
        for ((end, ending) in endings) {
            val fresh = Map()
            val one = measuringOn(fresh)
            one.onCloseView(true, true, Look.NIGHT)
            repeat(10) {
                now += SECOND
                one.onSecond(List(60) { 8.0 }, 1, 60)
            }
            ending(one)
            val line = fresh.viewLines.single()
            assertEquals(end.logName, line["end"], "$end")
            assertEquals(10_000L, line["ms"], "$end")
            assertEquals(end == End.LET_GO, line["counts"], "$end")
            assertEquals(end == End.LET_GO, fresh.views.single().counts, "$end")
            now += 30 * SECOND
        }
    }

    @Test
    fun `each view says whether Mapbox's lane details were on under the drawing, as the style said`() {
        measuring()
        view()
        assertEquals(true, map.viewLines.last()["hdRoads"])
        map.styleSays = null
        view()
        assertTrue("hdRoads" !in map.viewLines.last())
    }

    @Test
    fun `a released map measures nothing more`() {
        measuring()
        measure.release()
        measure.onCloseView(true, true, Look.NIGHT)
        assertFalse(measure.viewOpen)
        assertEquals(listOf("details true"), map.calls)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
