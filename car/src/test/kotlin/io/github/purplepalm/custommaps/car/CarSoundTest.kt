package io.github.purplepalm.custommaps.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the car's sessions hand the app's one player of tones: a car connected, and one taken by another app. */
class CarSoundTest {

    private val told = mutableListOf<Pair<Boolean, Boolean>>()
    private val link = CarSoundLink { connected, yielded -> told += connected to yielded }

    @Test
    fun `a session connects, is taken by another app, is taken back, and ends`() {
        val session = Any()
        link.onSession(session, connected = true)
        link.onYielded(session, yielded = true)
        link.onYielded(session, yielded = false)
        link.onYielded(session, yielded = true)
        link.onSession(session, connected = false)
        assertEquals(listOf(true to false, true to true, true to false, true to true, false to false), told)
    }

    @Test
    fun `with two sessions the car stays connected until both end, and one taken over silences the tones`() {
        val first = Any()
        val second = Any()
        link.onSession(first, connected = true)
        link.onSession(second, connected = true)
        link.onYielded(second, yielded = true)
        link.onSession(first, connected = false)
        assertEquals(true to true, told.last())
        link.onSession(second, connected = false)
        assertEquals(false to false, told.last())
    }

    @Test
    fun `a session that ended or never connected says nothing, and a second start keeps its state`() {
        val session = Any()
        link.onYielded(session, yielded = true)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), told)
        link.onSession(session, connected = true)
        link.onYielded(session, yielded = true)
        link.onSession(session, connected = true)
        assertEquals(true to true, told.last())
        link.onSession(session, connected = false)
        link.onYielded(session, yielded = true)
        assertEquals(false to false, told.last())
        assertEquals(4, told.size)
    }

    @Test
    fun `sessions are told apart by identity, not by equality`() {
        val first = "session"
        val second = String(charArrayOf('s', 'e', 's', 's', 'i', 'o', 'n'))
        link.onSession(first, connected = true)
        link.onSession(second, connected = true)
        link.onSession(first, connected = false)
        assertEquals(true to false, told.last())
    }
}
