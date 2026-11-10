package io.github.purplepalm.custommaps.nav.debug

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes an uncaught exception, with its stack and causes, into the debug log on the crashing
 * thread, then hands it to the handler that was installed before: on Android the one that shows
 * the crash dialog and ends the process. Exceptions that escape a coroutine end up here too.
 *
 * A crash in native code (the Mapbox renderer and navigator are C++) or an ANR never reaches a
 * Java handler; the next start reads those from Android's exit records instead.
 */
class CrashRecorder internal constructor(
    private val log: UrgentDebugLog,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    /**
     * What the app was doing, written with the crash. Runs on the crashing thread, which can be any
     * thread, so it may only read values that are safe to read from anywhere, such as
     * `StateFlow.value` or volatile fields.
     */
    @Volatile
    var context: () -> Map<String, Any?> = { emptyMap() }

    private val recording = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            // Only the first crash is written: a second thread failing at the same moment, or this
            // handler failing, goes straight on to the previous handler.
            if (recording.compareAndSet(false, true)) {
                log.logNow(EVENT, describe(thread, error) + ("context" to safeContext()), WRITE_TIMEOUT_MILLIS)
            }
        } catch (ignored: Throwable) {
            // Nothing more can be saved; the crash still has to reach the previous handler.
        } finally {
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
    }

    private fun safeContext(): Map<String, Any?> =
        try {
            context()
        } catch (e: Throwable) {
            mapOf("contextError" to e.toString())
        }

    companion object {
        const val EVENT = "crash"

        /** How long a crashing thread waits for a write already in progress. */
        const val WRITE_TIMEOUT_MILLIS = 500L

        private const val MAX_FRAMES = 40
        private const val MAX_CAUSES = 5
        private const val MAX_CAUSE_FRAMES = 15
        private const val MAX_SUPPRESSED = 3
        private const val MAX_MESSAGE_CHARS = 2_000

        /**
         * Makes a [CrashRecorder] the default handler for every thread, passing crashes on to the
         * handler that was the default until now. Call once, as early as the log exists.
         */
        fun install(log: UrgentDebugLog): CrashRecorder {
            val recorder = CrashRecorder(log, Thread.getDefaultUncaughtExceptionHandler())
            Thread.setDefaultUncaughtExceptionHandler(recorder)
            return recorder
        }

        internal fun describe(thread: Thread, error: Throwable): Map<String, Any?> {
            val frames = error.stackTrace
            return mapOf(
                "thread" to thread.name,
                "error" to error.toString().take(MAX_MESSAGE_CHARS),
                "stack" to frames.take(MAX_FRAMES).map { it.toString() },
                "framesLeftOut" to (frames.size - MAX_FRAMES).coerceAtLeast(0),
                // Coroutines add the context they ran in (dispatcher, job) as a suppressed exception.
                "suppressed" to error.suppressed.take(MAX_SUPPRESSED).map { it.toString().take(MAX_MESSAGE_CHARS) },
                "causes" to causes(error).map { cause ->
                    mapOf(
                        "error" to cause.toString().take(MAX_MESSAGE_CHARS),
                        "stack" to cause.stackTrace.take(MAX_CAUSE_FRAMES).map { it.toString() },
                    )
                },
            )
        }

        /** The chain of causes below [error], nearest first; stops where the chain loops. */
        private fun causes(error: Throwable): List<Throwable> {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            seen.add(error)
            val chain = ArrayList<Throwable>()
            var cause = error.cause
            while (cause != null && chain.size < MAX_CAUSES && seen.add(cause)) {
                chain.add(cause)
                cause = cause.cause
            }
            return chain
        }
    }
}
