package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.trip.TripKind
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonLinesTest {

    @Test
    fun `an event starts with its time and type`() {
        val line = JsonLines.event(Instant.parse("2026-11-03T17:00:00Z"), "route_request", mapOf("alternatives" to true))
        assertEquals("""{"t":"2026-11-03T17:00:00.000Z","type":"route_request","alternatives":true}""", line)
    }

    @Test
    fun `a field cannot replace the event time or type`() {
        val line = JsonLines.event(Instant.parse("2026-11-03T17:00:00Z"), "map_error", mapOf("type" to "STYLE", "t" to 1))
        assertEquals("""{"t":"2026-11-03T17:00:00.000Z","type":"map_error","field_type":"STYLE","field_t":1}""", line)
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\\u0001\"", JsonLines.encode("a\"b\\c\nd\te\u0001"))
    }

    @Test
    fun `numbers JSON cannot hold become null`() {
        assertEquals(
            "[1,2,1.5,0.25,null,null]",
            JsonLines.encode(listOf(1, 2L, 1.5, 0.25f, Double.NaN, Float.POSITIVE_INFINITY)),
        )
    }

    @Test
    fun `maps, lists, enums and null nest`() {
        val value = mapOf(
            "steps" to listOf(mapOf("road" to "Market St", "meters" to 120.5), mapOf("road" to null)),
            "kind" to TripKind.ACTIVE_GUIDANCE,
            "lanes" to arrayOf("left", "straight"),
        )
        assertEquals(
            """{"steps":[{"road":"Market St","meters":120.5},{"road":null}],"kind":"ACTIVE_GUIDANCE","lanes":["left","straight"]}""",
            JsonLines.encode(value),
        )
    }

    @Test
    fun `other values are written as text`() {
        assertEquals("\"30s\"", JsonLines.encode(kotlin.time.Duration.parse("30s")))
        // A time is text too, to the millisecond.
        assertEquals("\"2026-11-03T17:00:00.000Z\"", JsonLines.encode(Instant.parse("2026-11-03T17:00:00Z")))
    }

    @Test
    fun `every time has three digits of the second`() {
        assertEquals("2000-01-01T00:00:00.000Z", JsonLines.time(Instant.parse("2000-01-01T00:00:00Z")))
        assertEquals("2000-01-01T00:00:00.120Z", JsonLines.time(Instant.parse("2000-01-01T00:00:00.12Z")))
        // Finer than a millisecond is cut, never rounded up into the next one.
        assertEquals("2000-01-01T00:00:00.123Z", JsonLines.time(Instant.parse("2000-01-01T00:00:00.123456789Z")))
        assertEquals("2000-01-01T00:00:00.999Z", JsonLines.time(Instant.parse("2000-01-01T00:00:00.999999Z")))
        assertEquals(
            """{"t":"2000-01-01T00:00:00.500Z","type":"process_exit","diedAt":"1999-12-31T23:59:59.000Z"}""",
            JsonLines.event(Instant.parse("2000-01-01T00:00:00.5Z"), "process_exit", mapOf("diedAt" to Instant.parse("1999-12-31T23:59:59Z"))),
        )
    }
}
