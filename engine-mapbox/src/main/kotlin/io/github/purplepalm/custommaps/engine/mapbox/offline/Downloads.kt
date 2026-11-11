package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.Cancelable
import java.util.concurrent.atomic.AtomicInteger

/**
 * One download, from the call that starts it to the SDK's answer. The SDK answers on a thread of
 * its own, so what both sides touch is written on one thread and read on the other. [number] and
 * [routes] are those of a load of the tile region; [P] is the SDK's progress report.
 */
internal class Download<P>(val startedNanos: Long, val number: Int = 0, val routes: Int = 0) {

    /** The latest progress report, written where the SDK answers. */
    @Volatile var progress: P? = null

    /** Who ended the download, when the app did: written on the main thread, before the cancel. */
    @Volatile var endedBy: EndedBy? = null
        private set

    /** True once the SDK has answered, and for a download that could not be started. */
    @Volatile var finished = false

    /** Cancels the download at the SDK. Main thread only. */
    var cancelable: Cancelable? = null

    /**
     * Ends the download if it is still under way, and returns whether it did. Who ended it is
     * noted before the cancel: the SDK answers a cancelled download where it likes, at once or
     * later, and the line written then says why it ended. A download that was ended before keeps
     * its first reason, and one that is over is left alone. Main thread only.
     */
    fun end(by: EndedBy): Boolean {
        if (finished || endedBy != null) return false
        endedBy = by
        cancelable?.cancel()
        return true
    }
}

/**
 * The downloads of something only one load at a time fills, as the one tile region: each new
 * download takes the place of the one before it. Main thread only.
 */
internal class LatestDownload<P> {

    /** The download started last; it may have finished. */
    var current: Download<P>? = null
        private set

    /**
     * Makes [next] the current download and ends the one before it, if that is still under way, as
     * replaced by a newer preview. Call it before [next] is handed to the SDK: the store ends a load
     * of the same region by itself when the next one starts, and its answer for the old one is then
     * known for what it is. A download ended before keeps its first reason.
     */
    fun replace(next: Download<P>) {
        val previous = current
        current = next
        previous?.end(EndedBy.NEWER_PREVIEW)
    }

    /** Ends the current download, if it is still under way, for [by]. */
    fun stop(by: EndedBy) {
        current?.end(by)
    }
}

/**
 * Something that is loaded once in a run of the app: asked for until one load has succeeded, never
 * by two loads at a time, and not again after that. Safe to call from any thread.
 */
internal class LoadedOnce {

    private val state = AtomicInteger(MISSING)

    /** True when a load should start now; the caller then starts one and reports its end with [ended]. */
    fun begin(): Boolean = state.compareAndSet(MISSING, LOADING)

    /** The load that [begin] allowed is over. One that did not succeed is asked for again next time. */
    fun ended(loaded: Boolean) {
        state.set(if (loaded) LOADED else MISSING)
    }

    private companion object {
        const val MISSING = 0
        const val LOADING = 1
        const val LOADED = 2
    }
}
