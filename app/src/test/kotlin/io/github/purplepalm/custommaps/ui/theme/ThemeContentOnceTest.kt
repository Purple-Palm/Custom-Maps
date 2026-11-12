package io.github.purplepalm.custommaps.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A change of look must compose the screen again in place, never anew. Content composed from two
 * places, one a look, or under a key that changes with the look, would be disposed at every change,
 * and with it the map screen's map: a new map and a full style load twice a day, during a drive
 * too. The project has no Compose UI tests, so the sources hold the rule.
 */
class ThemeContentOnceTest {

    @Test
    fun `the theme composes its content from one place, whatever the look`() {
        val body = Sources.functionBody(Sources.code("ui/theme/Theme.kt"), "fun CustomMapsTheme(")
        assertEquals(1, Regex("\\bcontent\\b").findAll(body).count(), body)
        // The look picks prebuilt colours before the body; nothing in it branches.
        assertFalse(Regex("\\b(if|when)\\b").containsMatchIn(body), body)
    }

    @Test
    fun `nothing above the map screen is keyed`() {
        for (path in listOf("ui/theme/Theme.kt", "MainActivity.kt", "ui/CustomMapsApp.kt")) {
            assertFalse(Regex("\\bkey\\s*\\(").containsMatchIn(Sources.code(path)), path)
        }
    }
}
