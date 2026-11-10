package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlacePinTest {

    /** A style with room for the pin's source and layer, and every change to it recorded. */
    private class Style : PlacePin.Layers {
        override var usable = true

        /** Whether the source and the layer are in the style. */
        var added = false

        /** Where the pin is drawn; null when nothing is. */
        var drawn: GeoPoint? = null
        val calls = ArrayList<String>()
        var failAdd = false

        override fun exist(): Boolean = added

        override fun add(point: GeoPoint) {
            calls += "add"
            if (failAdd) throw IllegalStateException("the style refused")
            added = true
            drawn = point
        }

        override fun set(point: GeoPoint?) {
            calls += "set"
            drawn = point
        }

        override fun remove() {
            calls += "remove"
            added = false
            drawn = null
        }

        /** What a style load does: the new style has nothing of the pin. */
        fun reload() {
            added = false
            drawn = null
        }
    }

    private val style = Style()
    private val errors = ArrayList<Map<String, Any?>>()
    private val log = object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) {
            assertEquals("error", type)
            errors += fields
        }
    }
    private val pin = PlacePin(style, log)

    private val store = GeoPoint(0.001, 0.002)
    private val cafe = GeoPoint(0.003, 0.004)

    @Test
    fun `a style load with no place adds nothing`() {
        pin.onStyleLoaded()

        assertTrue(style.calls.isEmpty())
        assertFalse(style.added)
    }

    @Test
    fun `a place shown before the style is there gets its pin when the style has loaded`() {
        pin.show(store)
        assertTrue(style.calls.isEmpty(), "no style yet")

        pin.onStyleLoaded()

        assertEquals(listOf("add"), style.calls)
        assertEquals(store, style.drawn)
    }

    @Test
    fun `a place shown on a loaded style gets its pin at once`() {
        pin.onStyleLoaded()

        pin.show(store)

        assertEquals(listOf("add"), style.calls)
        assertEquals(store, style.drawn)
    }

    @Test
    fun `another place moves the pin and adds nothing`() {
        pin.onStyleLoaded()
        pin.show(store)

        pin.show(cafe)

        assertEquals(listOf("add", "set"), style.calls)
        assertEquals(cafe, style.drawn)
    }

    @Test
    fun `the same place again changes nothing`() {
        pin.onStyleLoaded()
        pin.show(store)

        pin.show(GeoPoint(store.latitude, store.longitude))

        assertEquals(listOf("add"), style.calls)
    }

    @Test
    fun `no place takes the pin off the map and leaves the layer`() {
        pin.onStyleLoaded()
        pin.show(store)

        pin.show(null)

        assertEquals(listOf("add", "set"), style.calls)
        assertNull(style.drawn)
        assertTrue(style.added)
    }

    @Test
    fun `a place after none is drawn again without a new layer`() {
        pin.onStyleLoaded()
        pin.show(store)
        pin.show(null)

        pin.show(cafe)

        assertEquals(listOf("add", "set", "set"), style.calls)
        assertEquals(cafe, style.drawn)
    }

    @Test
    fun `no place on a style without the pin does nothing`() {
        pin.onStyleLoaded()
        pin.show(store)
        style.reload()
        pin.show(null)

        assertEquals(listOf("add"), style.calls)
        assertFalse(style.added)
    }

    @Test
    fun `the pin is added again after a style load`() {
        pin.onStyleLoaded()
        pin.show(store)
        style.reload()

        pin.onStyleLoaded()

        assertEquals(listOf("add", "add"), style.calls)
        assertEquals(store, style.drawn)
    }

    @Test
    fun `release takes the pin out of the style`() {
        pin.onStyleLoaded()
        pin.show(store)

        pin.release()

        assertEquals(listOf("add", "remove"), style.calls)
        assertFalse(style.added)
    }

    @Test
    fun `nothing is set after release`() {
        pin.onStyleLoaded()
        pin.release()
        style.calls.clear()

        pin.show(store)
        pin.onStyleLoaded()
        pin.show(null)
        pin.release()

        assertTrue(style.calls.isEmpty())
    }

    @Test
    fun `release before the style has loaded touches nothing, and a late style load adds nothing`() {
        pin.show(store)

        pin.release()
        pin.onStyleLoaded()

        assertTrue(style.calls.isEmpty())
    }

    @Test
    fun `a map that is gone is left alone`() {
        pin.onStyleLoaded()
        pin.show(store)
        style.usable = false
        style.calls.clear()

        pin.show(cafe)
        pin.release()

        assertTrue(style.calls.isEmpty())
    }

    @Test
    fun `a style that refuses the pin costs only the pin`() {
        pin.onStyleLoaded()
        style.failAdd = true

        pin.show(store)

        assertFalse(style.added)
        assertEquals("placePin", errors.single()["where"])

        // The next place is tried afresh.
        style.failAdd = false
        pin.show(cafe)
        assertEquals(cafe, style.drawn)
    }
}
