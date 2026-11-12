package io.github.purplepalm.custommaps.engine.mapbox.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The strip at the bottom of the map that the screen keeps free of panels, for the Mapbox logo and the attribution button. */
class OrnamentStripTest {

    @Test
    fun `the strip keeps a panel's day shadow off the logo and the attribution button`() {
        // By day the 3 dp shadow under the guidance bar, the route sheet and the replay row reaches
        // about 10 dp below them: 12 dp free above the logo keep it off the logo and the button, in
        // both looks, so a change of look moves no panel.
        assertTrue(PhoneMap.SHADOW_CLEARANCE_DP >= 12f)
        // 6 dp under the 21 dp logo, the logo, and the 12 dp above it; the logo itself stays where it was.
        assertEquals(6f + 21f + 12f, PhoneMap.ORNAMENT_STRIP_DP)
    }
}
