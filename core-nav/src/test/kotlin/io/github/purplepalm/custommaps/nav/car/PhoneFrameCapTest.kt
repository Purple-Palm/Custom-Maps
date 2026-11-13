package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone map's frame rate cap while a car's surface shows the map: the engine's count of car
 * surfaces and each phone map's cap, wired as the engine and the phone's scene wire them.
 */
class PhoneFrameCapTest {

    @Test
    fun `with no car nothing is set on the phone's map`() {
        val rig = Rig()
        val phone = rig.phoneMap()
        assertEquals(emptyList<Int>(), phone.set)
        assertFalse(phone.cap.capped)
        assertFalse(rig.carShown)
    }

    @Test
    fun `a car's surface caps the phone's map at 60, and the surface going lifts the cap`() {
        val rig = Rig()
        val phone = rig.phoneMap()
        rig.onCarSurface(shown = true)
        assertEquals(listOf(60), phone.set)
        assertTrue(phone.cap.capped)
        rig.onCarSurface(shown = false)
        assertEquals(listOf(60, Int.MAX_VALUE), phone.set)
        assertFalse(phone.cap.capped)
        // And again for the next surface.
        rig.onCarSurface(shown = true)
        rig.onCarSurface(shown = false)
        assertEquals(listOf(60, Int.MAX_VALUE, 60, Int.MAX_VALUE), phone.set)
    }

    @Test
    fun `two car maps at once keep the cap until both surfaces go`() {
        val rig = Rig()
        val phone = rig.phoneMap()
        // A session's map not yet let go of, and the next session's surface.
        rig.onCarSurface(shown = true)
        rig.onCarSurface(shown = true)
        assertEquals(2, rig.surfaces.count)
        rig.onCarSurface(shown = false)
        assertTrue(rig.carShown)
        assertEquals(listOf(60), phone.set)
        rig.onCarSurface(shown = false)
        assertEquals(listOf(60, Int.MAX_VALUE), phone.set)
        assertEquals(0, rig.surfaces.count)
    }

    @Test
    fun `a surface that goes without having come counts for nothing`() {
        val rig = Rig()
        val phone = rig.phoneMap()
        rig.onCarSurface(shown = false)
        rig.onCarSurface(shown = false)
        assertEquals(0, rig.surfaces.count)
        assertEquals(emptyList<Int>(), phone.set)
        rig.onCarSurface(shown = true)
        assertEquals(listOf(60), phone.set)
        rig.onCarSurface(shown = false)
        assertEquals(listOf(60, Int.MAX_VALUE), phone.set)
    }

    @Test
    fun `a phone map made while a car's surface shows the map is capped at once`() {
        val rig = Rig()
        rig.onCarSurface(shown = true)
        val phone = rig.phoneMap()
        assertEquals(listOf(60), phone.set)
        rig.onCarSurface(shown = false)
        assertEquals(listOf(60, Int.MAX_VALUE), phone.set)
    }

    @Test
    fun `what is set is not set again, and a cap that could not be set is asked for again`() {
        val cap = PhoneFrameCap()
        assertNull(cap.capFor(carShown = false))
        assertEquals(PhoneFrameCap.CAPPED_FPS, cap.capFor(carShown = true))
        // Not set: the next look asks for it again.
        assertEquals(PhoneFrameCap.CAPPED_FPS, cap.capFor(carShown = true))
        cap.onSet(carShown = true)
        assertNull(cap.capFor(carShown = true))
        assertEquals(PhoneFrameCap.UNCAPPED_FPS, cap.capFor(carShown = false))
        cap.onSet(carShown = false)
        assertNull(cap.capFor(carShown = false))
    }

    /** The engine's count of car surfaces, what its flow says, and the phone maps that follow it. */
    private class Rig {
        val surfaces = CarSurfaces()
        var carShown = false
            private set
        private val maps = mutableListOf<PhoneMapRecord>()

        /** A car map's surface came or went: the flow's new value reaches every phone map. */
        fun onCarSurface(shown: Boolean) {
            carShown = surfaces.onCarSurface(shown)
            maps.forEach { it.onCarShown(carShown) }
        }

        /** A phone map made now: it hears the flow's value at once, as a collector does. */
        fun phoneMap(): PhoneMapRecord = PhoneMapRecord().also {
            maps += it
            it.onCarShown(carShown)
        }
    }

    /** A phone map's cap, and every frame rate set on the map, in order. */
    private class PhoneMapRecord {
        val cap = PhoneFrameCap()
        val set = mutableListOf<Int>()

        fun onCarShown(carShown: Boolean) {
            val fps = cap.capFor(carShown) ?: return
            set += fps
            cap.onSet(carShown)
        }
    }
}
