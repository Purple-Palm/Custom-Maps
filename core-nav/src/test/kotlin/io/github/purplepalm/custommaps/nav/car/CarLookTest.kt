package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The car map's two stages of day and night. Times are milliseconds on a monotonic clock that starts
 * at an invented 1,000,000; the host's mode flips as a tunnel or a cabin light sensor would flip it.
 */
class CarLookTest {

    private val t0 = 1_000_000L
    private val second = 1_000L

    @Test
    fun `the first look is the host's, theme and all`() {
        val day = CarLook(dark = false)
        assertEquals(LookOnMap(Look.DAY), day.request)
        assertEquals(mapOf("dark" to false, "cause" to "START"), day.start())
        assertNull(day.themeDueAtMillis)
        assertEquals(LookOnMap(Look.NIGHT), CarLook(dark = true).request)
    }

    @Test
    fun `a new mode writes the light and colours at once and lets the theme wait`() {
        val look = CarLook(dark = false)
        val step = look.onHost(dark = true, nowMillis = t0)
        assertEquals(LookOnMap(Look.NIGHT, theme = Look.DAY), step?.request)
        assertEquals(mapOf("dark" to true, "cause" to "HOST"), step?.fields)
        assertEquals(t0 + 60 * second, look.themeDueAtMillis)
    }

    @Test
    fun `the same mode again changes nothing`() {
        val look = CarLook(dark = true)
        assertNull(look.onHost(dark = true, nowMillis = t0))
        look.onHost(dark = false, nowMillis = t0)
        assertNull(look.onHost(dark = false, nowMillis = t0 + 5 * second))
        // The hold counts from the first report of the mode, not from a repeat of it.
        assertEquals(t0 + 60 * second, look.themeDueAtMillis)
    }

    @Test
    fun `a tunnel of 30 s writes no theme`() {
        val look = CarLook(dark = false)
        val requests = ArrayList<LookOnMap>()
        look.onHost(dark = true, nowMillis = t0)?.let { requests += it.request }
        assertNull(look.onTime(t0 + 30 * second - 1))
        look.onHost(dark = false, nowMillis = t0 + 30 * second)?.let { requests += it.request }
        // Back in the day look before the theme was due: nothing waits any more.
        assertNull(look.themeDueAtMillis)
        for (later in listOf(60L, 90L, 600L)) assertNull(look.onTime(t0 + later * second))
        assertEquals(listOf(LookOnMap(Look.NIGHT, theme = Look.DAY), LookOnMap(Look.DAY)), requests)
        // The basemap's theme was asked for as the day's throughout.
        assertEquals(listOf(Look.DAY, Look.DAY), requests.map { it.theme })
    }

    @Test
    fun `a mode held for 90 s takes the theme after 60 s`() {
        val look = CarLook(dark = false)
        look.onHost(dark = true, nowMillis = t0)
        assertNull(look.onTime(t0 + 60 * second - 1))
        val theme = look.onTime(t0 + 60 * second)
        assertEquals(LookOnMap(Look.NIGHT), theme?.request)
        assertEquals(mapOf("dark" to true, "cause" to "HOST", "themeHeldS" to 60L), theme?.fields)
        assertNull(look.themeDueAtMillis)
        // Once only.
        assertNull(look.onTime(t0 + 90 * second))
        assertEquals(LookOnMap(Look.NIGHT), look.request)
    }

    @Test
    fun `a late check of the time says how long the mode really held`() {
        val look = CarLook(dark = true)
        look.onHost(dark = false, nowMillis = t0)
        assertEquals(61L, look.onTime(t0 + 61_500L)?.fields?.get("themeHeldS"))
    }

    @Test
    fun `the way back from a theme that followed waits again`() {
        val look = CarLook(dark = false)
        look.onHost(dark = true, nowMillis = t0)
        look.onTime(t0 + 60 * second)
        val back = look.onHost(dark = false, nowMillis = t0 + 90 * second)
        assertEquals(LookOnMap(Look.DAY, theme = Look.NIGHT), back?.request)
        assertEquals(t0 + 150 * second, look.themeDueAtMillis)
        assertEquals(LookOnMap(Look.DAY), look.onTime(t0 + 150 * second)?.request)
    }

    @Test
    fun `a map that loads its style anew while the theme waits draws the whole look`() {
        val look = CarLook(dark = false)
        look.onHost(dark = true, nowMillis = t0)
        look.onNewMap()
        assertEquals(LookOnMap(Look.NIGHT), look.request)
        assertNull(look.themeDueAtMillis)
        assertNull(look.onTime(t0 + 60 * second))
        // Its theme is the night's now, so a flip back makes the day's theme wait.
        assertEquals(LookOnMap(Look.DAY, theme = Look.NIGHT), look.onHost(dark = false, nowMillis = t0 + 10 * second)?.request)
        assertEquals(t0 + 70 * second, look.themeDueAtMillis)
    }

    @Test
    fun `the hold is a setting`() {
        assertEquals(60_000L, CarLookTuning().themeHoldMillis)
        val look = CarLook(dark = false, CarLookTuning(themeHoldMillis = 5_000L))
        look.onHost(dark = true, nowMillis = t0)
        assertEquals(LookOnMap(Look.NIGHT), look.onTime(t0 + 5_000L)?.request)
    }

    @Test
    fun `the host's mode names its look`() {
        assertEquals(Look.NIGHT, CarLook.lookOf(dark = true))
        assertEquals(Look.DAY, CarLook.lookOf(dark = false))
    }
}
