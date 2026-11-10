package io.github.purplepalm.custommaps.nav.debug

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateSummaryTest {

    private enum class Guidance { IDLE, ACTIVE }

    @Test
    fun `fields are written in order as key=value pairs`() {
        val bytes = StateSummary.encode(
            mapOf("guidance" to Guidance.ACTIVE, "visible" to true, "since" to Instant.parse("2000-01-01T00:00:00Z")),
        )

        assertEquals("guidance=ACTIVE;visible=true;since=2000-01-01T00:00:00Z", StateSummary.decode(bytes))
    }

    @Test
    fun `null values are left out`() {
        val bytes = StateSummary.encode(mapOf("guidance" to Guidance.IDLE, "visible" to null))

        assertEquals("guidance=IDLE", StateSummary.decode(bytes))
    }

    @Test
    fun `nothing to say is an empty summary`() {
        assertEquals(0, StateSummary.encode(emptyMap()).size)
    }

    @Test
    fun `a long value is cut to the limit instead of throwing`() {
        val bytes = StateSummary.encode(mapOf("guidance" to Guidance.ACTIVE, "note" to "x".repeat(1_000)))

        assertEquals(StateSummary.MAX_BYTES, bytes.size)
        assertTrue(StateSummary.decode(bytes).startsWith("guidance=ACTIVE;note=xxx"))
    }

    @Test
    fun `a summary of exactly the limit is kept whole`() {
        val text = "k=" + "v".repeat(StateSummary.MAX_BYTES - 2)

        assertEquals(text, StateSummary.decode(StateSummary.encode(mapOf("k" to "v".repeat(StateSummary.MAX_BYTES - 2)))))
    }

    @Test
    fun `characters of two, three and four bytes are never split`() {
        for (character in listOf("é", "€", "🚗")) {
            val bytes = StateSummary.encode(mapOf("k" to character.repeat(200)))
            val decoded = StateSummary.decode(bytes)

            assertTrue(bytes.size <= StateSummary.MAX_BYTES, character)
            assertTrue(bytes.size > StateSummary.MAX_BYTES - 4, character)
            assertFalse(decoded.contains('�'), character)
            assertArrayEquals(bytes, decoded.toByteArray(Charsets.UTF_8), character)
        }
    }
}
