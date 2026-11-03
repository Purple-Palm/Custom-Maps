package io.github.purplepalm.custommaps.engine.mapbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MapboxAccessTokensTest {

    // Shaped like a real token, but not one.
    private val publicToken = "pk.eyJ1IjoiZXhhbXBsZSIsImEiOiJ0ZXN0In0.c2lnbmF0dXJlX29ubHlfZm9yX3Rlc3Rz"

    @Test
    fun `accepts a public token and ignores surrounding whitespace`() {
        assertEquals(AccessTokenCheck.VALID, MapboxAccessTokens.check(publicToken))
        assertEquals(AccessTokenCheck.VALID, MapboxAccessTokens.check("  $publicToken\n"))
    }

    @Test
    fun `rejects an empty token`() {
        assertEquals(AccessTokenCheck.EMPTY, MapboxAccessTokens.check("   "))
    }

    @Test
    fun `rejects a secret token`() {
        assertEquals(AccessTokenCheck.SECRET_TOKEN, MapboxAccessTokens.check("sk.eyJ1IjoiZXhhbXBsZSJ9.abc"))
    }

    @Test
    fun `rejects tokens that are not public`() {
        assertEquals(AccessTokenCheck.NOT_PUBLIC_TOKEN, MapboxAccessTokens.check("tk.eyJ1IjoiZXhhbXBsZSJ9.abc"))
        assertEquals(AccessTokenCheck.NOT_PUBLIC_TOKEN, MapboxAccessTokens.check("hello"))
    }

    @Test
    fun `rejects malformed public tokens`() {
        assertEquals(AccessTokenCheck.MALFORMED, MapboxAccessTokens.check("pk."))
        assertEquals(AccessTokenCheck.MALFORMED, MapboxAccessTokens.check("pk.onlyonepart"))
        assertEquals(AccessTokenCheck.MALFORMED, MapboxAccessTokens.check("pk.abc def.ghi"))
    }

    @Test
    fun `mask shows only the start and the end`() {
        val masked = MapboxAccessTokens.mask(publicToken)
        assertEquals("pk.eyJ1…" + publicToken.takeLast(4), masked)
        assertFalse(masked.contains(publicToken.substring(10, 30)))
    }

    @Test
    fun `mask never reveals a short value`() {
        assertEquals("pk.…", MapboxAccessTokens.mask("pk.abc"))
    }
}
