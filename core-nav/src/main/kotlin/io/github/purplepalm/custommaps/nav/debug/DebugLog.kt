package io.github.purplepalm.custommaps.nav.debug

import java.io.File

/**
 * Detailed diagnostics for the owner: every route with its roads and maneuvers, every second of
 * guidance, searches, trip sessions and errors. The log stays on the phone until the owner shares it.
 *
 * Never pass the access token or a request URL (Mapbox request URLs carry the token).
 */
interface DebugLog {

    /** Records one event. Returns at once; writing happens in the background. */
    fun log(type: String, fields: Map<String, Any?> = emptyMap())

    companion object {
        /** Records nothing; for tests and previews. */
        val NONE: DebugLog = object : DebugLog {
            override fun log(type: String, fields: Map<String, Any?>) = Unit
        }
    }
}

/** Records [error] with a short stack trace. */
fun DebugLog.error(where: String, error: Throwable) {
    log(
        "error",
        mapOf(
            "where" to where,
            "error" to error.toString(),
            "stack" to error.stackTrace.take(STACK_FRAMES).map { it.toString() },
        ),
    )
}

private const val STACK_FRAMES = 12

/** The stored log, as Settings shows, shares and clears it. */
interface DebugLogArchive {

    suspend fun sizeBytes(): Long

    /**
     * Copies the log into [target] as plain text files, one per day, oldest first, each opened by
     * a line with [header]. Earlier exports in [target] are replaced.
     */
    suspend fun export(target: File, header: Map<String, Any?>): List<File>

    suspend fun clear()
}
