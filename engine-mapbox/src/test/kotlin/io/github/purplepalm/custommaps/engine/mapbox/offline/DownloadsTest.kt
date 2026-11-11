package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.Cancelable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadsTest {

    /** Counts how often the SDK was told to cancel, and remembers what the download said then. */
    private class Cancels(private val download: () -> Download<*>) : Cancelable {
        var count = 0
        var endedByAtCancel: EndedBy? = null

        override fun cancel() {
            count += 1
            endedByAtCancel = download().endedBy
        }
    }

    private lateinit var download: Download<Long>
    private val cancels = Cancels { download }

    private fun started(): Download<Long> = Download<Long>(startedNanos = 0L, number = 1, routes = 2).also {
        download = it
        it.cancelable = cancels
    }

    @Test
    fun `a download under way is cancelled once, with who ended it noted first`() {
        val download = started()

        assertTrue(download.end(EndedBy.NEWER_PREVIEW))

        assertEquals(1, cancels.count)
        // Already there when the SDK hears of the cancel: its answer may come at once.
        assertEquals(EndedBy.NEWER_PREVIEW, cancels.endedByAtCancel)
        assertEquals(EndedBy.NEWER_PREVIEW, download.endedBy)
    }

    @Test
    fun `a download that was ended keeps its first reason`() {
        val download = started()
        download.end(EndedBy.TRIP_END)

        // A new preview opens before the SDK has answered the cancel.
        assertFalse(download.end(EndedBy.NEWER_PREVIEW))

        assertEquals(EndedBy.TRIP_END, download.endedBy)
        assertEquals(1, cancels.count)
    }

    @Test
    fun `a download that is over is left alone`() {
        val download = started()
        download.finished = true

        assertFalse(download.end(EndedBy.TRIP_END))

        assertNull(download.endedBy)
        assertEquals(0, cancels.count)
    }

    @Test
    fun `a download nobody ended has no one to name`() {
        assertNull(started().endedBy)
        assertFalse(started().finished)
        assertNull(started().progress)
    }

    @Test
    fun `the latest progress is the one kept`() {
        val download = started()
        download.progress = 10L
        download.progress = 25L

        assertEquals(25L, download.progress)
    }

    /** A load handed to the SDK, which notes who had ended it each time it is cancelled. */
    private class Load(number: Int) {
        val download = Download<Long>(startedNanos = 0L, number = number)
        val endedByAtCancels = mutableListOf<EndedBy?>()

        init {
            download.cancelable = Cancelable { endedByAtCancels += download.endedBy }
        }
    }

    @Test
    fun `the first load of the region replaces nothing`() {
        val loads = LatestDownload<Long>()
        val first = Load(1)

        loads.replace(first.download)

        assertSame(first.download, loads.current)
        assertEquals(emptyList<EndedBy?>(), first.endedByAtCancels)
    }

    @Test
    fun `a newer preview's load ends the one before, which then reads as replaced`() {
        val loads = LatestDownload<Long>()
        val first = Load(1)
        val second = Load(2)
        loads.replace(first.download)

        loads.replace(second.download)

        // Cancelled once, and the reason was there before the store heard of it.
        assertEquals(listOf(EndedBy.NEWER_PREVIEW), first.endedByAtCancels)
        assertEquals(OfflineLines.REPLACED, OfflineLines.outcome("CANCELED", first.download.endedBy).result)
        assertSame(second.download, loads.current)
        assertEquals(emptyList<EndedBy?>(), second.endedByAtCancels)
        assertNull(second.download.endedBy)
    }

    @Test
    fun `a load that had finished is left alone by the next one`() {
        val loads = LatestDownload<Long>()
        val first = Load(1)
        loads.replace(first.download)
        first.download.finished = true

        loads.replace(Load(2).download)

        assertEquals(emptyList<EndedBy?>(), first.endedByAtCancels)
        assertNull(first.download.endedBy)
    }

    @Test
    fun `a load stopped at the end of a trip keeps that reason when a preview comes after it`() {
        val loads = LatestDownload<Long>()
        val first = Load(1)
        loads.replace(first.download)

        loads.stop(EndedBy.TRIP_END)
        // Back in a preview before the store has answered the cancel.
        loads.replace(Load(2).download)

        assertEquals(listOf(EndedBy.TRIP_END), first.endedByAtCancels)
        assertEquals(OfflineLines.Outcome(OfflineLines.STOPPED, "trip_end"), OfflineLines.outcome("CANCELED", first.download.endedBy))
    }

    @Test
    fun `stopping with no load started does nothing`() {
        val loads = LatestDownload<Long>()

        loads.stop(EndedBy.TRIP_END)

        assertNull(loads.current)
    }

    @Test
    fun `what is loaded once is asked for first, and not while that load runs`() {
        val pack = LoadedOnce()

        assertTrue(pack.begin())
        assertFalse(pack.begin())
        assertFalse(pack.begin())
    }

    @Test
    fun `a load that failed is asked for again`() {
        val pack = LoadedOnce()
        pack.begin()
        pack.ended(loaded = false)

        assertTrue(pack.begin())
        assertFalse(pack.begin())
    }

    @Test
    fun `after one load that succeeded nothing is asked for again`() {
        val pack = LoadedOnce()
        pack.begin()
        pack.ended(loaded = false)
        pack.begin()
        pack.ended(loaded = true)

        assertFalse(pack.begin())
        assertFalse(pack.begin())
    }
}
