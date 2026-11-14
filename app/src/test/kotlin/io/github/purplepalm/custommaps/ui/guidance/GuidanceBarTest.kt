package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the guidance bar puts its numbers on a 360 dp phone, at one pixel a dp: the bar is 336 dp
 * wide, 12 dp in from each side of the screen, and 304 dp inside its padding. The widths are the
 * bar's own texts measured in Roboto Flex at the sizes Compose gives them at each font scale
 * (Android's own curve above 1.0, so 12 sp is 15.6 dp at 1.3 and 24 dp at 2.0), rounded: each
 * button with 16 dp of padding a side and at least Material's 58 dp, and 8 dp between the parts.
 */
class GuidanceBarTest {

    /** The two buttons and the gaps beside them. */
    private fun buttons(toggle: Int, end: Int) = 8 + toggle + 8 + end

    @Test
    fun `with the phone's own font and a large one the numbers stand beside the buttons`() {
        // At 1.0, Overview (91) and End (58) leave 139: the widest clock time at 16 sp takes 70.
        assertEquals(BarRows.ONE, barRows(width = 304, buttons = buttons(toggle = 91, end = 58), numbers = 70))
        // At 1.3, Overview (111) and End (65) leave 112, and the numbers take 88.
        assertEquals(BarRows.ONE, barRows(width = 304, buttons = buttons(toggle = 111, end = 65), numbers = 88))
    }

    @Test
    fun `with the largest font the numbers take a row of their own above the buttons`() {
        // At 2.0, Overview (141) and End (77) leave 70; the clock time at 16 sp takes 123 and a time
        // left of hours at 12 sp 126. Beside the buttons both were cut.
        assertEquals(BarRows.TWO, barRows(width = 304, buttons = buttons(toggle = 141, end = 77), numbers = 126))
        // At 1.5 already: 94 left, and the clock time takes 101.
        assertEquals(BarRows.TWO, barRows(width = 304, buttons = buttons(toggle = 124, end = 70), numbers = 101))
    }

    @Test
    fun `numbers exactly as wide as the room left stay beside the buttons`() {
        assertEquals(BarRows.ONE, barRows(width = 304, buttons = 165, numbers = 139))
        assertEquals(BarRows.TWO, barRows(width = 304, buttons = 165, numbers = 140))
        // A bar too narrow even for its buttons gives the numbers a row of their own.
        assertEquals(BarRows.TWO, barRows(width = 150, buttons = 165, numbers = 1))
    }

    @Test
    fun `in a row of their own the numbers have the whole bar, and both fit on one line`() {
        // At 2.0 a long drive's "1 hr 15 min · 48 mi" takes 210 at 12 sp (225 at 14 sp) of the 304.
        assertEquals(RemainingForm.BOTH, remainingForm(room = 304, both = 210, time = 126))
        // A short drive at 1.0 beside the buttons: "18 min · 12.3 mi" takes 91 of the 139.
        assertEquals(RemainingForm.BOTH, remainingForm(room = 139, both = 91, time = 38))
    }

    @Test
    fun `the time left shows alone only where it fits, and otherwise the two take a line each`() {
        // At 1.3 beside the buttons a long drive's both take 136 at 12 sp of the 112; the time alone 82.
        assertEquals(RemainingForm.TIME, remainingForm(room = 112, both = 136, time = 82))
        // At 2.0 beside the buttons, where this bar no longer puts them: not even the time alone fits the 70.
        assertEquals(RemainingForm.STACKED, remainingForm(room = 70, both = 210, time = 126))
        // At the edges: what is exactly as wide as the room fits.
        assertEquals(RemainingForm.BOTH, remainingForm(room = 100, both = 100, time = 50))
        assertEquals(RemainingForm.TIME, remainingForm(room = 100, both = 101, time = 100))
        assertEquals(RemainingForm.STACKED, remainingForm(room = 100, both = 150, time = 101))
    }

    @Test
    fun `a List button stays beside Overview and End while the three fit, and takes a row of its own when they do not`() {
        // While a car's screen shows the map: the three buttons and their gaps, exactly as wide as the
        // bar inside its padding, stay in one row. Overview always says Overview, so it keeps no room
        // for another word.
        assertFalse(listRowOfItsOwn(width = 304, buttonsRow = 304))
        assertTrue(listRowOfItsOwn(width = 304, buttonsRow = 305))
        // At 1.3 List (63), Overview (111) and End (65) with their gaps take 255 of the 304.
        assertFalse(listRowOfItsOwn(width = 304, buttonsRow = 63 + 8 + 111 + 8 + 65))
        // At the largest font Overview (141) and End (77) with their gap take 226: List (75) and its
        // gap would need 83 of the 78 left.
        assertTrue(listRowOfItsOwn(width = 304, buttonsRow = 75 + 8 + 141 + 8 + 77))
    }

    @Test
    fun `Recenter in the numbers' place keeps the rows the numbers take, at every font scale`() {
        // Recenter's label with its widest count, "Recenter 20", at 11 sp (60 at 1.0, 69 at 1.15, 78 at
        // 1.3, 91 at 1.5, 109 at 1.8, 121 at 2.0), with 4 dp of padding a side, against the numbers at
        // their smallest (70, 79, 88, 101, 114, 126), beside Overview and End.
        class Scale(val toggle: Int, val end: Int, val list: Int, val numbers: Int, val recenterLabel: Int)
        val scales = listOf(
            Scale(toggle = 91, end = 58, list = 58, numbers = 70, recenterLabel = 60),
            Scale(toggle = 101, end = 61, list = 59, numbers = 79, recenterLabel = 69),
            Scale(toggle = 111, end = 65, list = 63, numbers = 88, recenterLabel = 78),
            Scale(toggle = 124, end = 70, list = 68, numbers = 101, recenterLabel = 91),
            Scale(toggle = 134, end = 75, list = 72, numbers = 114, recenterLabel = 109),
            Scale(toggle = 141, end = 77, list = 75, numbers = 126, recenterLabel = 121),
        )
        val rows = scales.map { scale ->
            val slot = maxOf(scale.numbers, recenterMinWidth(scale.recenterLabel, padding = 4, minWidth = 58))
            val alone = buttons(scale.toggle, scale.end)
            val withList = scale.list + 8 + alone
            assertEquals(barRows(304, alone, scale.numbers), barRows(304, alone, slot))
            assertEquals(barRows(304, withList, scale.numbers), barRows(304, withList, slot))
            barRows(304, alone, slot) to barRows(304, withList, slot)
        }
        // Beside the buttons up to 1.3, and with List at the phone's own font; in a row of their own
        // from 1.5 on, where Recenter has the whole bar.
        assertEquals(listOf(BarRows.ONE, BarRows.ONE, BarRows.ONE, BarRows.TWO, BarRows.TWO, BarRows.TWO), rows.map { it.first })
        assertEquals(listOf(BarRows.ONE, BarRows.TWO, BarRows.TWO, BarRows.TWO, BarRows.TWO, BarRows.TWO), rows.map { it.second })
    }

    @Test
    fun `Recenter keeps room for its label at the smallest with its padding, never less than Material's least button`() {
        assertEquals(68, recenterMinWidth(labelWidth = 60, padding = 4, minWidth = 58))
        assertEquals(58, recenterMinWidth(labelWidth = 40, padding = 4, minWidth = 58))
        assertEquals(58, recenterMinWidth(labelWidth = 50, padding = 4, minWidth = 58))
        assertEquals(59, recenterMinWidth(labelWidth = 51, padding = 4, minWidth = 58))
    }

    @Test
    fun `the bar keeps room for every clock time and for the time left of any drive under ten hours`() {
        val digits = Regex("\\d")
        // With the digits all as wide, a clock time is as wide as the widest the bar keeps room for,
        // or one digit narrower.
        val widest = WIDEST_ARRIVALS.map { it.replace(digits, "0") }.toSet()
        val start = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        for (minute in 0 until 24 * 60) {
            val shape = ImperialFormat.arrivalTime(start, minute.minutes).replace(digits, "0")
            assertTrue(shape in widest || "0$shape" in widest, shape)
        }
        // A time left under ten hours never has more characters or more digits than the bar's wide one.
        val wide = WIDE_TIME_LEFT
        assertEquals(wide, ImperialFormat.duration(599.minutes))
        for (minutes in 1 until 600) {
            val left = ImperialFormat.duration(minutes.minutes)
            assertTrue(left.length <= wide.length && digits.findAll(left).count() <= digits.findAll(wide).count(), left)
        }
    }
}
