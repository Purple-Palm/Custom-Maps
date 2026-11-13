package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CarFailureTest {

    @Test
    fun `the line names the call, the class and the message`() {
        assertEquals(
            mapOf("call" to "navigationStarted", "error" to "java.lang.IllegalStateException", "message" to "No callback has been set"),
            CarFailure.fields("navigationStarted", IllegalStateException("No callback has been set")),
        )
    }

    @Test
    fun `an exception without a message has no message field`() {
        assertEquals(mapOf("call" to "onAttached", "error" to "java.lang.NullPointerException"), CarFailure.fields("onAttached", NullPointerException()))
    }

    @Test
    fun `no position reaches the log through a message`() {
        // An invented point near 0, 0, as a library might quote it.
        assertEquals("Bad point #,# at zoom 2.5", CarFailure.masked("Bad point 0.12345,-0.54321 at zoom 2.5"))
        assertEquals("lat=# lng=#", CarFailure.masked("lat=1.0001 lng=-0.12345678"))
        // Short decimals and whole numbers stay.
        assertEquals("width 800 height 480 scale 1.25", CarFailure.masked("width 800 height 480 scale 1.25"))
    }

    @Test
    fun `a long message is cut`() {
        val long = "x".repeat(500)
        assertEquals(CarFailure.MAX_MESSAGE, CarFailure.fields("getTemplate", RuntimeException(long))["message"].toString().length)
    }
}
