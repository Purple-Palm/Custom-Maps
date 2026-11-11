package io.github.purplepalm.custommaps.nav.debug

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a flood of the same event out of the debug log, such as one tile error for every tile
 * while the phone is offline. The first event with a key is logged in full. Later ones are only
 * counted, and every [windowMillis] one "<type>_repeats" line gives their number, the fields of
 * the first of them, where they came from and when the first and last arrived. A key with no
 * repeats for a whole window is forgotten, so its next event is logged in full again.
 *
 * Safe to call from any thread; the windows close on [scope].
 */
class RepeatFolder(
    private val log: DebugLog,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {

    private class Window(val type: String) {
        var count = 0
        var first: Map<String, Any?>? = null
        var from: Instant? = null
        var to: Instant? = null
        val origins = LinkedHashMap<String, Int>()
        var timer: Job? = null
    }

    private val lock = Any()
    private val windows = HashMap<String, Window>()

    /**
     * Logs [fields] as a [type] event, or only counts it if an event with the same [key] came
     * within the current window. [origin], such as the map source of a tile error, is counted
     * separately in the repeats line.
     */
    fun record(type: String, key: String, fields: Map<String, Any?>, origin: String? = null) {
        val logNow = synchronized(lock) {
            val window = windows[key]
            if (window == null) {
                windows[key] = Window(type).also { it.timer = closeLater(key, it) }
                true
            } else {
                val now = clock.instant()
                if (window.count == 0) {
                    window.first = fields
                    window.from = now
                }
                window.count += 1
                window.to = now
                if (origin != null) window.origins[origin] = (window.origins[origin] ?: 0) + 1
                false
            }
        }
        if (logNow) log.log(type, fields)
    }

    /** Writes the counts of every open window at once, for example before the event source goes away. */
    fun flush() {
        val closed = synchronized(lock) {
            val all = windows.values.toList()
            windows.clear()
            all.onEach { it.timer?.cancel() }.mapNotNull(::takeRepeats)
        }
        closed.forEach { (type, fields) -> log.log(type, fields) }
    }

    private fun closeLater(key: String, window: Window): Job = scope.launch {
        delay(windowMillis)
        val repeats = synchronized(lock) {
            if (windows[key] !== window) return@synchronized null
            val repeats = takeRepeats(window)
            // Still flooding: keep counting in a new window. Quiet for a whole window: forget the key.
            if (repeats == null) windows.remove(key) else window.timer = closeLater(key, window)
            repeats
        }
        if (repeats != null) log.log(repeats.first, repeats.second)
    }

    /** The repeats line for [window], or null if nothing was repeated; resets the window's counts. */
    private fun takeRepeats(window: Window): Pair<String, Map<String, Any?>>? {
        if (window.count == 0) return null
        val fields = mapOf(
            "count" to window.count,
            "from" to window.from,
            "to" to window.to,
            "origins" to window.origins.takeIf { it.isNotEmpty() }?.let { LinkedHashMap(it) },
            "first" to window.first,
        )
        window.count = 0
        window.first = null
        window.from = null
        window.to = null
        window.origins.clear()
        return "${window.type}$REPEATS_SUFFIX" to fields
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val REPEATS_SUFFIX = "_repeats"

        private val NUMBERS = Regex("""\d+""")

        /**
         * A key from [parts] with every number replaced by #, so messages that differ only in
         * error codes, tile coordinates or ids count as the same.
         */
        fun keyOf(vararg parts: String?): String = parts.joinToString("|") { it.orEmpty().replace(NUMBERS, "#") }
    }
}
