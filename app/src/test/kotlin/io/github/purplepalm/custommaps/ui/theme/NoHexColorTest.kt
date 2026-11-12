package io.github.purplepalm.custommaps.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every colour of the screen comes from the look's tokens or Material's roles, which the theme
 * holds. A colour written into a composable would stay the same by day and at night, and only the
 * theme's own files may name one. Nor does anything follow the phone's dark theme: the app keeps
 * its own day and night.
 */
class NoHexColorTest {

    @Test
    fun `no composable outside the theme names a colour of its own`() {
        val ui = Sources.filesUnder("ui").filterKeys { !it.startsWith("ui/theme/") }
        assertTrue(ui.size >= 10, "found ${ui.keys}")
        val fixed = Regex("""\bColor\s*\(\s*0x|\bColor\.(White|Black|Gray|LightGray|DarkGray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""")
        val offenders = ui.filterKeys { fixed.containsMatchIn(Sources.code(it)) }.keys
        assertEquals(emptySet<String>(), offenders)
    }

    @Test
    fun `nothing reads the phone's dark theme`() {
        val app = Sources.filesUnder("")
        assertTrue(app.size >= 20, "found ${app.keys}")
        assertEquals(emptySet<String>(), app.filterValues { "isSystemInDarkTheme(" in it.readText() }.keys)
    }
}
