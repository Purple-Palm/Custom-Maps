package io.github.purplepalm.custommaps.ui.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The phone's map as the main screen keeps it: made only in a composition that shows it, so its view
 * is in the window from its first composition on, and then kept, also while the list shows in its
 * place. Each call stands for one composition of the screen, with the list shown or the map.
 */
class PhoneMapSlotTest {

    private var made = 0
    private val slot = PhoneMapSlot<Any>()

    private fun compose(mapShown: Boolean): Any? = slot.take(shown = mapShown) {
        made += 1
        Any()
    }

    @Test
    fun `a screen that starts on the list makes no map while the list shows`() {
        repeat(3) { assertNull(compose(mapShown = false)) }
        assertEquals(0, made)
        assertNull(slot.map)
    }

    @Test
    fun `the map is made in the first composition that shows it, and only then`() {
        compose(mapShown = false)
        compose(mapShown = false)
        val map = compose(mapShown = true)
        assertNotNull(map)
        assertEquals(1, made)
        assertSame(map, slot.map)
    }

    @Test
    fun `a screen that starts on the map makes it at once`() {
        val map = compose(mapShown = true)
        assertNotNull(map)
        assertEquals(1, made)
        assertSame(map, compose(mapShown = true))
        assertEquals(1, made)
    }

    @Test
    fun `the map made is kept while the list shows and is the same when the map shows again`() {
        val map = compose(mapShown = true)
        repeat(3) { assertSame(map, compose(mapShown = false)) }
        assertSame(map, compose(mapShown = true))
        assertSame(map, compose(mapShown = false))
        assertEquals(1, made)
    }
}
