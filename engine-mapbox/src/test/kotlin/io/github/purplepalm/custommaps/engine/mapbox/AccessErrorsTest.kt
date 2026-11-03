package io.github.purplepalm.custommaps.engine.mapbox

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessErrorsTest {

    @Test
    fun `a refused token is recognised by its status code`() {
        assertTrue(AccessErrors.isAccessDenied(401))
        assertTrue(AccessErrors.isAccessDenied(403))
        assertFalse(AccessErrors.isAccessDenied(404))
        assertFalse(AccessErrors.isAccessDenied(500))
    }

    @Test
    fun `a refused token is recognised in a map error message`() {
        assertTrue(AccessErrors.isAccessDenied("HTTP status code 401. Unauthorized"))
        assertTrue(AccessErrors.isAccessDenied("Forbidden"))
        assertTrue(AccessErrors.isAccessDenied("Not Authorized - Invalid Token"))
    }

    @Test
    fun `other failures are not taken for a refused token`() {
        assertFalse(AccessErrors.isAccessDenied(null))
        assertFalse(AccessErrors.isAccessDenied("HTTP status code 404"))
        assertFalse(AccessErrors.isAccessDenied("Connection timed out"))
        // Tile numbers are not status codes.
        assertFalse(AccessErrors.isAccessDenied("Tile 14/401/6331 failed: HTTP status code 503"))
    }
}
