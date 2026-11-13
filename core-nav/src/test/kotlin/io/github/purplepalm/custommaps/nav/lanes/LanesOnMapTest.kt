package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanesOnMapTest {

    @Test
    fun `a phone that never chose has no lanes on the map, and the app's drawing is offered, not given`() {
        assertEquals(LanesOnMap.OFF, LanesOnMap.DEFAULT)
        assertEquals(listOf(LanesOnMap.OFF, LanesOnMap.MAPBOX, LanesOnMap.APP), LanesOnMap.entries)
    }

    @Test
    fun `Mapbox's lane details show with their choice only, and not under the app's drawing yet`() {
        assertFalse(LanesOnMap.OFF.mapboxDetails)
        assertTrue(LanesOnMap.MAPBOX.mapboxDetails)
        assertFalse(LanesOnMap.DETAILS_UNDER_DRAWING, "until a drive with the measuring switch shows they draw below the app's asphalt")
        assertEquals(LanesOnMap.DETAILS_UNDER_DRAWING, LanesOnMap.APP.mapboxDetails)
    }

    @Test
    fun `only the app's choice draws the app's lanes`() {
        assertEquals(listOf(false, false, true), LanesOnMap.entries.map { it.appDraws })
    }

    @Test
    fun `the old switch stands for Mapbox's lane details when on and for Off when off`() {
        assertEquals(LanesOnMap.MAPBOX, LanesOnMap.fromLaneDetails(true))
        assertEquals(LanesOnMap.OFF, LanesOnMap.fromLaneDetails(false))
    }

    @Test
    fun `the old switch is carried over once, first written, then its key removed, and a file without it is left alone`() {
        // After an update: the old switch alone, either value.
        assertEquals(LanesOnMap.Migration.WRITE_CHOICE, LanesOnMap.Migration.of(null, true, null))
        assertEquals(LanesOnMap.Migration.WRITE_CHOICE, LanesOnMap.Migration.of(null, false, null))
        assertEquals(LanesOnMap.Migration.WRITE_CHOICE, LanesOnMap.Migration.of(null, true, true))
        // After the first write, or a process that died before the second: the choice stands, the old keys go.
        assertEquals(LanesOnMap.Migration.REMOVE_OLD_KEYS, LanesOnMap.Migration.of("mapbox", true, null))
        assertEquals(LanesOnMap.Migration.REMOVE_OLD_KEYS, LanesOnMap.Migration.of("off", false, false))
        // A choice next to a switch an older version wrote again: the choice wins.
        assertEquals(LanesOnMap.Migration.REMOVE_OLD_KEYS, LanesOnMap.Migration.of("app", false, null))
        // The old Debug switch beside a choice goes too, and alone it is never carried over.
        assertEquals(LanesOnMap.Migration.REMOVE_OLD_KEYS, LanesOnMap.Migration.of("app", null, true))
        assertEquals(LanesOnMap.Migration.NONE, LanesOnMap.Migration.of(null, null, true))
        // Nothing stored, or the choice alone: nothing is written.
        assertEquals(LanesOnMap.Migration.NONE, LanesOnMap.Migration.of(null, null, null))
        assertEquals(LanesOnMap.Migration.NONE, LanesOnMap.Migration.of("app", null, null))
    }
}
