package io.github.purplepalm.custommaps.nav.debug

/**
 * Last line of defence for the debug log: Mapbox tokens and `access_token` query parameters are
 * masked in every line, whatever an SDK error message or URL happens to contain.
 */
object Redaction {

    private val ACCESS_TOKEN_PARAMETER = Regex("""(access_token=)[^&\s"\\]+""")
    private val MAPBOX_TOKEN = Regex("""\b(pk|sk|tk)\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    fun redact(text: String): String = text
        .replace(ACCESS_TOKEN_PARAMETER, "$1[redacted]")
        .replace(MAPBOX_TOKEN, "$1.[redacted]")
}
