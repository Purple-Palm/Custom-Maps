package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where Debug's switch Side-road fields on routes reaches the map engine: as each stored value comes, from the app's start
 * on; and where Settings shows it. The graph needs Android, so the statements are pinned by its source ([Sources]); what the
 * engine does with the switch is the engine's tests'.
 */
class SideRoadFieldsWiringTest {

    @Test
    fun `the stored switch reaches the engine as it changes`() {
        val start = Sources.functionBody(Sources.code("AppGraph.kt"), "fun start()").lines().map { it.trim() }
        assertTrue("applicationScope.launch { settings.sideRoadFields.collect(engine::setSideRoadFields) }" in start)
    }

    @Test
    fun `Settings' Debug section shows the switch and stores its change`() {
        val app = Sources.code("ui/CustomMapsApp.kt").lines().map { it.trim() }
        assertTrue("onSideRoadFieldsChange = settingsViewModel::setSideRoadFields," in app)
        val section = Sources.functionBody(Sources.code("ui/settings/SettingsScreen.kt"), "private fun DebugSection(").lines().map { it.trim() }
        // One switch row, the whole row its target, that shows the stored value and stores a change.
        assertEquals(1, section.count { it == "checked = debug.sideRoadFields," })
        val row = section.indexOf("title = stringResource(R.string.debug_side_road_fields),")
        assertTrue(row > 0 && section[row - 1] == "SwitchRowItem(")
        assertEquals("checked = debug.sideRoadFields,", section[row + 1])
        assertEquals("onCheckedChange = onSideRoadFieldsChange,", section[row + 2])
        assertEquals("lines = listOf(RowLine(stringResource(R.string.debug_side_road_fields_note))),", section[row + 3])
    }
}
