package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.Cancelable
import com.mapbox.common.NetworkRestriction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the download of a preview's routes ends before it is done. */
class PreviewDownloadRulesTest {

    @Test
    fun `Start lets the download go on into the trip`() {
        assertNull(PreviewDownloadRules.endedBy(PreviewEvent.START))
    }

    @Test
    fun `a preview closed without a trip lets its download finish`() {
        assertNull(PreviewDownloadRules.endedBy(PreviewEvent.CLOSED))
    }

    @Test
    fun `a newer preview takes the region over`() {
        assertEquals(EndedBy.NEWER_PREVIEW, PreviewDownloadRules.endedBy(PreviewEvent.NEWER_PREVIEW))
        assertEquals(OfflineLines.Outcome("replaced"), OfflineLines.outcome("CANCELED", PreviewDownloadRules.endedBy(PreviewEvent.NEWER_PREVIEW)))
    }

    @Test
    fun `the end of the trip, by End or at the destination, ends it`() {
        assertEquals(EndedBy.TRIP_END, PreviewDownloadRules.endedBy(PreviewEvent.TRIP_ENDED))
        assertEquals(OfflineLines.Outcome("stopped", "trip_end"), OfflineLines.outcome("CANCELED", PreviewDownloadRules.endedBy(PreviewEvent.TRIP_ENDED)))
    }

    @Test
    fun `the network rule is any network, and no change of network ends a download`() {
        // Mobile data included; without a network the store waits by itself.
        assertEquals(NetworkRestriction.NONE, PreviewDownload.NETWORK)
        // No event about the network exists: only these end a download.
        assertEquals(
            setOf(EndedBy.NEWER_PREVIEW, EndedBy.TRIP_END),
            PreviewEvent.entries.mapNotNull(PreviewDownloadRules::endedBy).toSet(),
        )
    }

    @Test
    fun `a download that goes on after Start ends once, at the end of the trip`() {
        val loads = LatestDownload<Long>()
        val download = Download<Long>(startedNanos = 0L, number = 1, routes = 3)
        var cancels = 0
        download.cancelable = Cancelable { cancels += 1 }
        loads.replace(download)

        PreviewDownloadRules.endedBy(PreviewEvent.START)?.let(loads::stop)
        assertEquals(0, cancels)
        assertNull(download.endedBy)

        PreviewDownloadRules.endedBy(PreviewEvent.TRIP_ENDED)?.let(loads::stop)
        assertEquals(1, cancels)
        assertEquals(EndedBy.TRIP_END, download.endedBy)
    }

    @Test
    fun `a download done during the trip is left alone at its end`() {
        val loads = LatestDownload<Long>()
        val download = Download<Long>(startedNanos = 0L, number = 1, routes = 1)
        var cancels = 0
        download.cancelable = Cancelable { cancels += 1 }
        loads.replace(download)
        download.finished = true

        PreviewDownloadRules.endedBy(PreviewEvent.TRIP_ENDED)?.let(loads::stop)

        assertEquals(0, cancels)
        assertNull(download.endedBy)
    }

    @Test
    fun `a download that ended during a trip says so, and only then`() {
        val during = OfflineLines.region(1, 2, errorType = null, message = null, endedBy = null, counts = null, seconds = 30.0, inTrip = true)
        val before = OfflineLines.region(1, 2, errorType = null, message = null, endedBy = null, counts = null, seconds = 30.0)

        assertEquals(true, during["inTrip"])
        assertFalse("inTrip" in before)
        assertTrue(OfflineLines.stylePack(null, null, null, null, 1.0, inTrip = true)["inTrip"] == true)
    }
}
