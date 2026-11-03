package io.github.purplepalm.custommaps.nav

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ViewportPaddingTest {

    @Test
    fun `padding cannot be negative`() {
        assertThrows<IllegalArgumentException> { ViewportPadding(0, -1, 0, 0) }
    }
}
