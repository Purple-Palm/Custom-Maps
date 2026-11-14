package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The map the driver holds, and its count back. */
class MapHoldTest {
    private val tuning = DriveViewTuning.PHONE

    @Test
    fun `the count runs from the later of the last finger lifted and the last move of the driver's own`() {
        val hold = MapHold(tuning)
        // A drag: the finger goes down before the gesture takes the map.
        hold.onFinger(true, 0.0)
        hold.onTaken(0.1)
        var t = 0.2
        while (t < 1.0) {
            hold.onMapMoved(t)
            t += 0.05
        }
        hold.onFinger(false, 1.2)
        assertEquals(10, hold.secondsLeft(1.2, 10))
        assertEquals(10, hold.secondsLeft(2.1, 10))
        assertEquals(9, hold.secondsLeft(2.2, 10))
        assertEquals(1, hold.secondsLeft(11.1, 10))
        assertFalse(hold.isOver(11.19, 10))
        assertTrue(hold.isOver(11.21, 10))
        assertEquals(0, hold.secondsLeft(11.21, 10))
        // A fling goes on after the finger: its end starts the count again.
        val fling = MapHold(tuning)
        fling.onFinger(true, 0.0)
        fling.onTaken(0.1)
        fling.onFinger(false, 1.0)
        fling.onMapMoved(1.0)
        fling.onMapMoved(2.6)
        assertEquals(10, fling.secondsLeft(2.6, 10))
        assertFalse(fling.isOver(12.5, 10))
        assertTrue(fling.isOver(12.61, 10))
        // 5 and 20 s as set.
        assertTrue(fling.isOver(7.61, 5))
        assertFalse(fling.isOver(22.5, 20))
    }

    @Test
    fun `while a finger rests on the map the count stands at its full value`() {
        val hold = MapHold(tuning)
        hold.onFinger(true, 0.0)
        hold.onTaken(0.0)
        hold.onFinger(false, 2.0)
        hold.onFinger(true, 5.0)
        for (t in listOf(5.0, 9.0, 20.0, 34.9)) {
            assertEquals(10, hold.secondsLeft(t, 10), "at $t")
            assertFalse(hold.isOver(t, 10))
        }
        hold.onFinger(false, 30.0)
        assertEquals(10, hold.secondsLeft(30.0, 10))
        assertTrue(hold.isOver(40.01, 10))
        assertEquals(1, hold.restarts, "the second touch started the count again")
    }

    @Test
    fun `a finger reported down with nothing moving for 30 s counts as lifted`() {
        val hold = MapHold(tuning)
        hold.onFinger(true, 0.0)
        hold.onTaken(0.5)
        assertEquals(10, hold.secondsLeft(30.4, 10))
        assertFalse(hold.isOver(30.4, 10))
        // Lifted at 30.5 s: the count runs from there.
        assertEquals(10, hold.secondsLeft(30.5, 10))
        assertEquals(5, hold.secondsLeft(35.5, 10))
        assertFalse(hold.isOver(40.4, 10))
        assertTrue(hold.isOver(40.51, 10))
    }

    @Test
    fun `near a point the count is at most 3 s after the last touch`() {
        val hold = MapHold(tuning)
        hold.onTaken(0.0)
        hold.onFinger(false, 1.0)
        assertEquals(9, hold.secondsLeft(2.0, 10))
        // The approach opens: 3 s after the last touch, 1 s from here.
        hold.onNear(true)
        assertEquals(2, hold.secondsLeft(2.0, 10))
        assertFalse(hold.isOver(3.9, 10))
        assertTrue(hold.isOver(4.01, 10))
        // Begun while near, and with a finger down: the full count is 3.
        val near = MapHold(tuning)
        near.onNear(true)
        near.onFinger(true, 0.0)
        near.onTaken(0.1)
        assertEquals(3, near.secondsLeft(10.0, 10))
        near.onFinger(false, 10.0)
        assertTrue(near.isOver(13.01, 10))
        // Recenter by itself at 5 s: 3 near a turn; never more than it is set to.
        assertTrue(near.isOver(13.01, 5))
        val short = MapHold(tuning)
        short.onNear(true)
        short.onTaken(0.0)
        assertFalse(short.isOver(1.9, 2))
        assertTrue(short.isOver(2.01, 2))
        // Away from the point again: the full count.
        hold.onNear(false)
        assertFalse(hold.isOver(10.9, 10))
        assertTrue(hold.isOver(11.01, 10))
    }

    @Test
    fun `with Recenter by itself off a moved map never goes back by itself`() {
        val hold = MapHold(tuning)
        hold.onTaken(0.0)
        hold.onFinger(false, 1.0)
        for (near in listOf(false, true)) {
            hold.onNear(near)
            for (t in listOf(1.0, 4.0, 11.0, 300.0)) {
                assertNull(hold.secondsLeft(t, 0))
                assertFalse(hold.isOver(t, 0))
            }
        }
        assertEquals(MapHold.Kind.MAP_MOVED, hold.kind)
    }

    @Test
    fun `a look lasts 10 s whatever Recenter by itself says, and a gesture turns it into an ordinary hold`() {
        for (recenter in listOf(0, 5, 10, 20)) {
            val look = MapHold(tuning)
            look.onLook(0.0)
            assertEquals(MapHold.Kind.LOOK, look.kind)
            assertEquals(10, look.secondsLeft(0.0, recenter))
            assertEquals(4, look.secondsLeft(6.2, recenter))
            assertFalse(look.isOver(9.9, recenter))
            assertTrue(look.isOver(10.01, recenter))
        }
        val look = MapHold(tuning)
        look.onLook(0.0)
        look.onFinger(true, 3.0)
        look.onTaken(3.1)
        assertEquals(MapHold.Kind.MAP_MOVED, look.kind)
        assertEquals(0.0, look.since, "the hold began with the look")
        look.onFinger(false, 4.0)
        assertFalse(look.isOver(13.9, 10))
        assertTrue(look.isOver(14.01, 10))
    }

    @Test
    fun `a touch before the hold is no restart, a new one during it is, and release drops it`() {
        val hold = MapHold(tuning)
        hold.onFinger(true, 0.0)
        hold.onTaken(0.1)
        hold.onFinger(false, 1.0)
        assertEquals(0, hold.restarts)
        hold.onFinger(true, 3.0)
        hold.onFinger(false, 3.5)
        hold.onFinger(true, 6.0)
        hold.onFinger(false, 6.2)
        assertEquals(2, hold.restarts)
        hold.release()
        assertEquals(MapHold.Kind.NONE, hold.kind)
        assertNull(hold.secondsLeft(7.0, 10))
        assertFalse(hold.isOver(100.0, 10))
        // Moves without a hold change nothing.
        hold.onMapMoved(8.0)
        assertEquals(MapHold.Kind.NONE, hold.kind)
    }
}
