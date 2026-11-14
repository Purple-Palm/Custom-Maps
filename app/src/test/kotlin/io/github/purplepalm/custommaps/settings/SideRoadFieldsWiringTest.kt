package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.ui.settings.DebugState
import io.github.purplepalm.custommaps.ui.settings.RowNote
import io.github.purplepalm.custommaps.ui.settings.RowText
import io.github.purplepalm.custommaps.ui.settings.SettingId
import io.github.purplepalm.custommaps.ui.settings.SettingsCatalogue
import io.github.purplepalm.custommaps.ui.settings.SettingsValues
import io.github.purplepalm.custommaps.ui.settings.SwitchRow
import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where Debug's switch Side-road fields on routes reaches the map engine: as each stored value comes, from the app's start
 * on; and where Settings shows it and stores its change. The graph needs Android, so its statements are pinned by its source
 * ([Sources]); what the engine does with the switch is the engine's tests'.
 */
class SideRoadFieldsWiringTest {

    @Test
    fun `the stored switch reaches the engine as it changes`() {
        val start = Sources.functionBody(Sources.code("AppGraph.kt"), "fun start()").lines().map { it.trim() }
        assertTrue("applicationScope.launch { settings.sideRoadFields.collect(engine::setSideRoadFields) }" in start)
    }

    @Test
    fun `Settings' Debug group shows the switch with the stored value`() {
        for (on in listOf(true, false)) {
            val debug = SettingsCatalogue.page(SettingsValues(debug = DebugState(sideRoadFields = on))).single { it.title == R.string.debug_title }
            val row = debug.rows.single { it.id == SettingId.SIDE_ROAD_FIELDS } as SwitchRow
            assertEquals(on, row.on)
            assertEquals(RowText.Res(R.string.debug_side_road_fields), row.title)
            assertEquals(listOf(RowNote(RowText.Res(R.string.debug_side_road_fields_note))), row.lines)
        }
        assertEquals("side_road_fields", SettingId.SIDE_ROAD_FIELDS.key)
    }

    @Test
    fun `the view model stores the switch's change`() {
        // The event goes through the switch's own setter, which stores it; SettingsViewModelTest runs it.
        val onEvent = Sources.functionBody(Sources.code("ui/settings/SettingsViewModel.kt"), "fun onEvent(").lines().map { it.trim() }
        assertTrue("SettingId.SIDE_ROAD_FIELDS -> event.switched(::setSideRoadFields)" in onEvent)
        assertTrue("onEvent = settingsViewModel::onEvent," in Sources.code("ui/CustomMapsApp.kt").lines().map { it.trim() })
    }
}
