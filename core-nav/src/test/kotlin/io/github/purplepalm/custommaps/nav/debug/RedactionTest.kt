package io.github.purplepalm.custommaps.nav.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RedactionTest {

    @Test
    fun `access token query parameters are masked`() {
        assertEquals(
            "https://api.mapbox.com/directions?alternatives=true&access_token=[redacted]&language=en",
            Redaction.redact("https://api.mapbox.com/directions?alternatives=true&access_token=pk.dGVzdC1wYXlsb2Fk.dGVzdC1zaWduYXR1cmU&language=en"),
        )
    }

    @Test
    fun `tokens anywhere in a message are masked`() {
        assertEquals(
            "HTTP 401 for token pk.[redacted] and sk.[redacted]",
            Redaction.redact("HTTP 401 for token pk.dGVzdC1wYXlsb2Fk.dGVzdC1zaWduYXR1cmU and sk.c2VjcmV0LXZhbHVl.c2lnbmF0dXJlLXg"),
        )
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = """{"road":"Park Ave","instruction":"Turn left onto pk. Lane","speedLimit":35}"""
        assertEquals(text, Redaction.redact(text))
    }
}
