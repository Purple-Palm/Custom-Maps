package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraSettlingTest {

    private val still = CameraSettling(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun `a camera on its targets and still is at rest`() {
        assertTrue(still.atRest)
        assertTrue(still.copy(zoomGap = 0.009, pitchGap = -0.4, bearingGap = 0.49, paddingGap = 0.9).atRest)
    }

    @Test
    fun `a zoom that has some way to go is not at rest`() {
        // What a flight leaves the zoom spring with when its path is done.
        assertFalse(still.copy(zoomGap = 0.08).atRest)
        assertFalse(still.copy(zoomSpeed = 0.05).atRest)
    }

    @Test
    fun `tilt, bearing and padding count too`() {
        assertFalse(still.copy(pitchGap = 0.6).atRest)
        assertFalse(still.copy(pitchSpeed = 2.1).atRest)
        assertFalse(still.copy(bearingGap = -0.6).atRest)
        assertFalse(still.copy(bearingSpeed = -2.1).atRest)
        assertFalse(still.copy(paddingGap = 1.5).atRest)
    }

    @Test
    fun `the gaps are logged as sizes, rounded`() {
        assertEquals(
            mapOf("zoomGap" to 0.123, "pitchGap" to 1.23, "bearingGap" to 2.35, "paddingGap" to 12.3),
            still.copy(zoomGap = -0.12345, pitchGap = 1.2345, bearingGap = -2.3456, paddingGap = 12.345).gapFields(),
        )
    }
}
