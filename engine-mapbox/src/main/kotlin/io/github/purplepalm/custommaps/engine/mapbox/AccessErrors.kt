package io.github.purplepalm.custommaps.engine.mapbox

/** Recognises failures caused by an access token that Mapbox refuses. */
internal object AccessErrors {

    private val httpCodes = setOf(401, 403)
    private val messagePattern = Regex(
        """status code (401|403)\b|\bunauthori[sz]ed\b|\bforbidden\b|\binvalid token\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isAccessDenied(httpCode: Int): Boolean = httpCode in httpCodes

    /** Map loading errors carry no status code, only the message, such as "HTTP status code 401". */
    fun isAccessDenied(message: String?): Boolean = message != null && messagePattern.containsMatchIn(message)
}
