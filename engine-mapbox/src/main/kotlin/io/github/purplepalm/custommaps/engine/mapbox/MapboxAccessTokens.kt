package io.github.purplepalm.custommaps.engine.mapbox

import com.mapbox.common.MapboxOptions

/** Outcome of checking a Mapbox access token before it is stored or used. */
enum class AccessTokenCheck {
    VALID,
    EMPTY,

    /** A secret token (sk.). The app never stores or uses one. */
    SECRET_TOKEN,

    /** Not a public token (pk.). */
    NOT_PUBLIC_TOKEN,

    /** Starts with pk. but does not have the shape of a Mapbox token. */
    MALFORMED,
}

/**
 * Rules for the Mapbox public access token. The token is never compiled into a build:
 * the driver enters it on the phone and the app hands it to the SDK at runtime.
 */
object MapboxAccessTokens {

    private const val PUBLIC_PREFIX = "pk."
    private const val SECRET_PREFIX = "sk."
    private val PUBLIC_TOKEN_SHAPE = Regex("""^pk\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")

    fun check(token: String): AccessTokenCheck {
        val trimmed = token.trim()
        return when {
            trimmed.isEmpty() -> AccessTokenCheck.EMPTY
            trimmed.startsWith(SECRET_PREFIX) -> AccessTokenCheck.SECRET_TOKEN
            !trimmed.startsWith(PUBLIC_PREFIX) -> AccessTokenCheck.NOT_PUBLIC_TOKEN
            !PUBLIC_TOKEN_SHAPE.matches(trimmed) -> AccessTokenCheck.MALFORMED
            else -> AccessTokenCheck.VALID
        }
    }

    /** Short form for display, for example `pk.eyJ1…a1b2`. Never shows the whole token. */
    fun mask(token: String): String {
        val trimmed = token.trim()
        if (trimmed.length <= MASK_HEAD + MASK_TAIL) return trimmed.take(PUBLIC_PREFIX.length) + "…"
        return trimmed.take(MASK_HEAD) + "…" + trimmed.takeLast(MASK_TAIL)
    }

    /** Passes a valid token to every Mapbox SDK. Call before the first map, route or search request. */
    fun apply(token: String) {
        val trimmed = token.trim()
        require(check(trimmed) == AccessTokenCheck.VALID) { "Refusing to apply an invalid Mapbox token" }
        MapboxOptions.accessToken = trimmed
    }

    private const val MASK_HEAD = 7
    private const val MASK_TAIL = 4
}
