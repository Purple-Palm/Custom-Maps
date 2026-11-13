package io.github.purplepalm.custommaps.nav.debug

import java.io.File

/**
 * Detailed diagnostics for the owner: every route with its roads and maneuvers, every second of
 * guidance, searches, trip sessions and errors. The log stays on the phone until the owner shares it.
 *
 * Never pass the access token or a request URL (Mapbox request URLs carry the token).
 */
interface DebugLog {

    /**
     * Records one event. Returns at once; encoding and writing happen in the background, so
     * [fields] must not change after the call: pass read-only values (mapOf, listOf, strings,
     * numbers), never a collection the caller keeps changing.
     */
    fun log(type: String, fields: Map<String, Any?> = emptyMap())

    companion object {
        /** Records nothing; for tests and previews. */
        val NONE: DebugLog = object : DebugLog {
            override fun log(type: String, fields: Map<String, Any?>) = Unit
        }
    }
}

/**
 * A [DebugLog] that can also write on the calling thread, for the last moments of a process that is
 * about to die, such as a thread with an uncaught exception. See [CrashRecorder].
 */
interface UrgentDebugLog : DebugLog {

    /**
     * Writes every event still queued and then this one, and returns once they have been handed to
     * the operating system, so they outlive the process. Waits at most [timeoutMillis] for a write
     * already in progress. Returns false if the event could not be written.
     */
    fun logNow(type: String, fields: Map<String, Any?>, timeoutMillis: Long): Boolean
}

/**
 * This log with [fields] added to every line written through it, ahead of the line's own fields, as
 * the car map's lines carry `screen=car` beside the phone's. A line's own field of the same name wins.
 */
fun DebugLog.withFields(fields: Map<String, Any?>): DebugLog {
    val base = this
    val extra = fields.toMap()
    return object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) = base.log(type, extra + fields)
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
interface DebugLogArchive : DebugLog {

    suspend fun sizeBytes(): Long

    /**
     * Copies the log into plain text files for sharing, one per day, oldest first, each opened by
     * a line with [header]. Earlier copies are replaced.
     */
    suspend fun export(header: Map<String, Any?>): List<File>

    /** Deletes the log and every copy made for sharing. */
    suspend fun clear()
}
