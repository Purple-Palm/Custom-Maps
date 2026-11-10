package io.github.purplepalm.custommaps.nav.debug

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val firstCrashWaitMillis: Long = FIRST_CRASH_WAIT_MILLIS,
) : Thread.UncaughtExceptionHandler {

    /**
     * What the app was doing, written with the crash. Runs on the crashing thread, which can be any
     * thread, so it may only read values that are safe to read from anywhere, such as
     * `StateFlow.value` or volatile fields.
     */
    @Volatile
    var context: () -> Map<String, Any?> = { emptyMap() }

    private val recording = AtomicBoolean(false)

    /** Opens once the first crash is written, or its write has failed. */
    private val firstCrashDone = CountDownLatch(1)

    @Volatile
    private var firstCrashThread: Thread? = null

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // A thread can die with its interrupt flag set, and a wait on such a thread gives up at once:
        // the write's wait for the files and the wait for the first crash's line alike. The flag is
        // taken off for the waits and put back for the previous handler.
        val wasInterrupted = Thread.interrupted()
        try {
            // Only the first crash is written. A second thread failing at the same moment waits until
            // that line is in the file, because the previous handler ends the process; the thread
            // writing it never waits for itself, so a failure inside this handler goes straight on.
            if (recording.compareAndSet(false, true)) {
                firstCrashThread = Thread.currentThread()
                try {
                    log.logNow(EVENT, describe(thread, error) + ("context" to safeContext()), WRITE_TIMEOUT_MILLIS)
                } finally {
                    firstCrashDone.countDown()
                }
            } else if (firstCrashThread !== Thread.currentThread()) {
                firstCrashDone.await(firstCrashWaitMillis, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ignored: Throwable) {
            // Nothing more can be saved; the crash still has to reach the previous handler.
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
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

        /**
         * How long a second crashing thread waits for the first crash's line: the write's wait for
         * the files, then the events still queued and the line itself.
         */
        const val FIRST_CRASH_WAIT_MILLIS = 2 * WRITE_TIMEOUT_MILLIS

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
