package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.geometry.DrawingWindows.Window
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The drawing windows of invented routes, in metres along them: which stretch the map holds, and when the next one is
 * sent, never inside a card window and never while a new route loads (the own lanes' design 9).
 */
class DrawingWindowsTest {

    /** A long card window, as a run-up with lane points well before its exit, and a short one, as a street junction's. */
    private val runUp = 3_200.0..5_800.0
    private val junction = 7_000.0..7_180.0
    private val windows = DrawingWindows(listOf(runUp, junction), routeLength = 12_000.0)

    @Test
    fun `a window runs from 50 m behind the car to 300 m past the next card window where that fits`() {
        assertEquals(Window(5_950.0, 7_480.0), windows.windowAt(6_000.0))
        assertEquals(Window(6_900.0, 7_480.0), windows.windowAt(6_950.0))
        // In the card window, all of it and what follows it.
        assertEquals(Window(7_050.0, 7_480.0), windows.windowAt(7_100.0))
    }

    @Test
    fun `with the next card window farther than the 3 km take, a window is 3 km of road`() {
        assertEquals(Window(0.0, 3_000.0), windows.windowAt(0.0))
        assertEquals(Window(950.0, 3_950.0), windows.windowAt(1_000.0))
        assertEquals(Window(2_750.0, 5_750.0), windows.windowAt(2_800.0))
        // Past the last card window, and at the route's end.
        assertEquals(Window(7_450.0, 10_450.0), windows.windowAt(7_500.0))
        assertEquals(Window(10_950.0, 12_000.0), windows.windowAt(11_000.0))
    }

    @Test
    fun `a card window the car is about to enter is taken whole, longer than 3 km where it must`() {
        assertEquals(Window(2_950.0, 6_100.0), windows.windowAt(3_000.0))
        assertEquals(3_150.0, windows.windowAt(3_000.0).meters, 1e-9)
        // In it too: a route that begins inside a card window takes it whole with its first window.
        assertEquals(Window(4_350.0, 6_100.0), windows.windowAt(4_400.0))
    }

    @Test
    fun `no window inside a card window, and none while a new route loads`() {
        val held = Window(2_950.0, 6_100.0)
        for (car in listOf(3_200.0, 4_000.0, 5_100.0, 5_200.0, 5_800.0)) assertFalse(windows.due(car, held, changing = false), "at $car")
        assertTrue(windows.inCard(3_200.0) && windows.inCard(5_800.0) && !windows.inCard(5_801.0))
        // Out of it, 1 km before the window's end: due.
        assertTrue(windows.due(5_900.0, held, changing = false))
        // The same, while a new route's path and window load: never.
        assertFalse(windows.due(5_900.0, held, changing = true))
        assertFalse(windows.due(0.0, null, changing = true))
    }

    @Test
    fun `the next window is due 1 km before the current one's end`() {
        val held = Window(0.0, 3_000.0)
        assertFalse(windows.due(1_999.0, held, changing = false))
        assertTrue(windows.due(2_000.0, held, changing = false))
    }

    @Test
    fun `the window that takes the next card window whole is due as soon as the car is 200 m from it`() {
        val held = Window(1_950.0, 4_950.0)
        // Not yet: the window from here would not hold the card window and the road past it.
        assertFalse(windows.due(2_999.0, held, changing = false))
        assertEquals(Window(2_949.0, 5_949.0), windows.windowAt(2_999.0))
        assertTrue(windows.due(3_000.0, held, changing = false))
    }

    @Test
    fun `a window that holds the card window ahead and the road past it waits for the 1 km rule`() {
        val held = Window(5_950.0, 7_480.0)
        // From 6,480 on the window from the car would reach no farther: nothing more to draw.
        for (car in listOf(6_000.0, 6_480.0, 6_900.0)) assertFalse(windows.due(car, held, changing = false), "at $car")
        // Past the junction's window, the road beyond it.
        assertTrue(windows.due(7_200.0, held, changing = false))
    }

    @Test
    fun `where nothing is drawn at the car the next window is due at once, wherever it is`() {
        assertTrue(windows.due(120.0, null, changing = false))
        // After a seek past the window held, into a card window.
        assertTrue(windows.due(4_000.0, Window(0.0, 3_000.0), changing = false))
        assertTrue(windows.due(2_000.0, Window(2_500.0, 5_500.0), changing = false))
    }

    @Test
    fun `card windows that meet or overlap are one`() {
        val merged = DrawingWindows(listOf(2_000.0..2_500.0, 1_000.0..2_000.0, 2_400.0..2_990.0, 6_250.0..6_400.0), 9_000.0)
        assertEquals(listOf(1_000.0..2_990.0, 6_250.0..6_400.0), merged.cards)
    }

    @Test
    fun `card windows that begin within 500 m of the one before are taken together, farther apart one at a time`() {
        val near = DrawingWindows(listOf(2_000.0..3_100.0, 3_599.0..3_800.0, 4_299.0..4_400.0), routeLength = 9_000.0)
        assertEquals(2_000.0..4_400.0, near.nextGroup(1_000.0))
        // Each window that takes the first takes them all, and 300 m past the last.
        assertEquals(Window(1_750.0, 4_700.0), near.windowAt(1_800.0))
        assertEquals(Window(1_850.0, 4_700.0), near.windowAt(1_900.0))
        // From inside one of them, or between two, the rest of them.
        assertEquals(3_599.0..4_400.0, near.nextGroup(3_300.0))
        assertEquals(Window(3_250.0, 4_700.0), near.windowAt(3_300.0))
        assertEquals(4_299.0..4_400.0, near.nextGroup(4_000.0))
        assertNull(near.nextGroup(4_400.0))
        // Just over 500 m apart: one at a time.
        val far = DrawingWindows(listOf(2_000.0..3_100.0, 3_601.0..3_800.0), routeLength = 9_000.0)
        assertEquals(2_000.0..3_100.0, far.nextGroup(1_000.0))
        assertEquals(Window(1_750.0, 3_400.0), far.windowAt(1_800.0))
        assertEquals(3_601.0..3_800.0, far.nextGroup(3_100.0))
        // Merged card windows stay as they were: the groups are the window rule's alone.
        assertEquals(listOf(2_000.0..3_100.0, 3_599.0..3_800.0, 4_299.0..4_400.0), near.cards)
        assertFalse(near.inCard(3_300.0))
    }

    @Test
    fun `through card windows a few metres apart nothing is sent inside one, and each holds the drawing to 300 m past them`() {
        // The gap between a fork's card window and the next one's, at one progress update a second from 27 to 34 m/s: a
        // gap no update falls in, gaps an update falls in, and gaps on both sides of 500 m.
        for (gap in listOf(3.0, 6.0, 15.0, 40.0, 120.0, 260.0, 499.0, 501.0, 800.0)) {
            val first = 2_000.0..4_500.0
            val second = (4_500.0 + gap)..7_000.0
            val route = DrawingWindows(listOf(first, second), routeLength = 9_000.0)
            for (step in 27..34) {
                for (start in listOf(0.0, 11.0, 23.0)) {
                    var car = start
                    var held = route.windowAt(car)
                    while (car <= 9_000.0) {
                        if (route.due(car, held, changing = false)) {
                            assertFalse(route.inCard(car), "sent at $car inside a card window, gap $gap m, $step m/s")
                            held = route.windowAt(car)
                        }
                        assertTrue(held.holds(car), "nothing drawn at $car, gap $gap m, $step m/s: $held")
                        for (card in route.cards) {
                            if (car >= card.start && car <= card.endInclusive) {
                                val need = minOf(card.endInclusive + DrawingWindows.PAST_CARD_METERS, 9_000.0)
                                assertTrue(held.to >= need - 1e-9, "at $car in $card held $held, gap $gap m, $step m/s")
                            }
                        }
                        car += step
                    }
                }
            }
        }
    }

    @Test
    fun `each constant on both sides of its border`() {
        assertEquals(50.0, DrawingWindows.BEHIND_METERS)
        assertEquals(3_000.0, DrawingWindows.MAX_METERS)
        assertEquals(1_000.0, DrawingWindows.SEND_AHEAD_METERS)
        assertEquals(300.0, DrawingWindows.PAST_CARD_METERS)
        assertEquals(200.0, DrawingWindows.ENTRY_METERS)
        assertEquals(500.0, DrawingWindows.TOGETHER_METERS)
        // 500 m between two card windows: together just under it, one at a time from it on.
        assertEquals(1_000.0..2_900.0, DrawingWindows(listOf(1_000.0..2_000.0, 2_499.9..2_900.0), 9_000.0).nextGroup(0.0))
        assertEquals(1_000.0..2_000.0, DrawingWindows(listOf(1_000.0..2_000.0, 2_500.0..2_900.0), 9_000.0).nextGroup(0.0))
        // 50 m behind the car, not before the route's start.
        assertEquals(950.0, windows.windowAt(1_000.0).from)
        assertEquals(0.0, windows.windowAt(30.0).from)
        // 3 km, and the card window that fits in it to 300 m past its end.
        val short = DrawingWindows(listOf(4_100.0..4_400.0), 9_000.0)
        assertEquals(Window(1_350.0, 4_350.0), short.windowAt(1_400.0))
        assertEquals(Window(1_650.0, 4_650.0), short.windowAt(1_700.0))
        assertEquals(Window(1_700.0, 4_700.0), short.windowAt(1_750.0))
        assertEquals(Window(1_750.0, 4_700.0), short.windowAt(1_800.0))
        // 1 km before the end.
        assertFalse(windows.due(1_999.9, Window(0.0, 3_000.0), changing = false))
        assertTrue(windows.due(2_000.0, Window(0.0, 3_000.0), changing = false))
        // 200 m before a card window that does not fit.
        assertEquals(5_949.0, windows.windowAt(2_999.0).to, 1e-9)
        assertEquals(6_100.0, windows.windowAt(3_000.0).to, 1e-9)
    }

    @Test
    fun `a drive holds the drawing at the car all along, sends nothing inside a card window, enters each one held whole`() {
        val cards = listOf(1_800.0..4_400.0, 6_250.0..6_400.0, 8_800.0..9_100.0)
        val route = DrawingWindows(cards, routeLength = 12_000.0)
        for (speed in listOf(20.0, 27.0, 34.0, 40.0)) {
            var held: Window? = null
            var sends = 0
            var car = 0.0
            // One progress update a second; the first window comes with the route's path.
            while (car <= 12_000.0) {
                if (held == null) {
                    held = route.windowAt(car)
                    sends++
                } else if (route.due(car, held, changing = false)) {
                    assertFalse(route.inCard(car), "a window sent at $car inside a card window at $speed m/s")
                    held = route.windowAt(car)
                    sends++
                }
                val window = requireNotNull(held)
                assertTrue(window.holds(car), "nothing drawn at $car at $speed m/s: $window")
                for (card in route.cards) {
                    if (car >= card.start && car - speed < card.start) {
                        assertTrue(window.to >= card.endInclusive + DrawingWindows.PAST_CARD_METERS - 1e-9, "entered $card held $window")
                    }
                }
                assertTrue(window.meters <= DrawingWindows.MAX_METERS + 1e-9 || route.inCard(car + DrawingWindows.ENTRY_METERS) || route.inCard(car), "$window at $car")
                car += speed
            }
            assertTrue(sends in 5..12, "$sends windows at $speed m/s")
        }
    }
}
