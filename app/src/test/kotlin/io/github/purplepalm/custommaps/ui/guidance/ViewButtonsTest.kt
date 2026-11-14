package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.DriveViewControl
import io.github.purplepalm.custommaps.nav.model.RecenterControl
import io.github.purplepalm.custommaps.ui.theme.Sources
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guidance bar's two view buttons (W21): Overview, a switch that shows the drive view, and
 * Recenter, which takes the numbers' place while the driver holds the map. The project has no
 * Compose UI tests, so the sources hold what no compiler checks: each button reads its own state,
 * the bar only whether the map is held, and the numbers stay laid out under Recenter.
 */
class ViewButtonsTest {

    private val panels = Sources.code("ui/guidance/GuidancePanels.kt")
    private val buttons = Sources.code("ui/guidance/ViewButtons.kt")
    private val bar = Sources.functionBody(panels, "fun GuidanceBar(")

    @Test
    fun `Overview is outlined in Normal and filled in the Overview, with Zoom in before turns on or off`() {
        // The switch shows the drive view, never where the camera is: also down for a turn, and held.
        assertEquals(ToggleLook.OUTLINED, toggleLook(DriveViewControl(DriveView.NORMAL, turnZoom = true)))
        assertEquals(ToggleLook.OUTLINED, toggleLook(DriveViewControl(DriveView.NORMAL, turnZoom = false)))
        assertEquals(ToggleLook.FILLED, toggleLook(DriveViewControl(DriveView.OVERVIEW, turnZoom = true)))
        assertEquals(ToggleLook.FILLED, toggleLook(DriveViewControl(DriveView.OVERVIEW, turnZoom = false)))
        // TalkBack reads it as a switch with its state.
        assertEquals(R.string.guidance_overview_off, ToggleLook.OUTLINED.said)
        assertEquals(R.string.guidance_overview_on, ToggleLook.FILLED.said)
    }

    @Test
    fun `Recenter counts down while the map is held, says the word alone with Recenter by itself off, and is gone without a hold`() {
        assertEquals(RecenterLabel.Count(7), recenterLabel(RecenterControl(secondsLeft = 7)))
        assertEquals(RecenterLabel.Count(20), recenterLabel(RecenterControl(secondsLeft = 20)))
        assertEquals(RecenterLabel.Count(0), recenterLabel(RecenterControl(secondsLeft = 0)))
        // Never fewer seconds than none.
        assertEquals(RecenterLabel.Count(0), recenterLabel(RecenterControl(secondsLeft = -1)))
        // Off: the map goes back on a tap only.
        assertEquals(RecenterLabel.Plain, recenterLabel(RecenterControl(secondsLeft = null)))
        assertNull(recenterLabel(null))
    }

    @Test
    fun `the words, Overview always, its state for TalkBack, and Recenter's count on the button and in its description`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for (line in listOf(
            """<string name="guidance_overview">Overview</string>""",
            """<string name="guidance_overview_on">Overview, on</string>""",
            """<string name="guidance_overview_off">Overview, off</string>""",
            """<string name="guidance_recenter">Recenter</string>""",
            """<string name="guidance_recenter_count">Recenter %1${'$'}d</string>""",
            """<item quantity="one">Recenter. The map comes back in %1${'$'}d second.</item>""",
            """<item quantity="other">Recenter. The map comes back in %1${'$'}d seconds.</item>""",
        )) {
            assertTrue(line in strings, line)
        }
    }

    @Test
    fun `each button reads its own state, the bar only whether the map is held, and the map screen neither`() {
        // The count and the drive view are read in their buttons; the bar asks only for a yes or no.
        assertTrue("remember(recenter) { derivedStateOf { recenter.value != null } }" in bar, bar)
        assertEquals(1, Regex("""\brecenter\.value\b""").findAll(bar).count())
        assertFalse(Regex("""\bdriveView\.value\b""").containsMatchIn(bar))
        assertTrue("toggleLook(control.value)" in Sources.functionBody(buttons, "fun OverviewToggle("))
        assertTrue("recenterLabel(control.value)" in Sources.functionBody(buttons, "fun RecenterButton("))
        // No tick is announced: nothing of the buttons is a live region.
        assertFalse("liveRegion" in buttons)
        // The map screen reads no camera mode, and passes both states on unread.
        val screen = Sources.functionBody(Sources.code("ui/map/MapScreen.kt"), "fun MapScreen(")
        assertFalse("cameraMode" in screen)
        assertTrue("val driveView = phoneMap.driveViewControl.collectAsStateWithLifecycle()" in screen)
        assertTrue("val recenter = phoneMap.recenterControl.collectAsStateWithLifecycle()" in screen)
        assertFalse(Regex("""\b(driveView|recenter)\.value\b""").containsMatchIn(screen))
        // The map has the other drive view at once, and the choice is stored for the next drives and the car's map.
        assertTrue("onToggleView = { viewModel.onDriveViewToggled(phoneMap.toggleDriveView()) }" in screen)
        assertTrue("onRecenter = phoneMap::recenter" in screen)
        // The stored settings reach the map, the button's choice among them.
        assertTrue("LaunchedEffect(phoneMap) { settings.driveCamera.collect(phoneMap::setDriveCamera) }" in screen)
    }

    @Test
    fun `the numbers stay laid out under Recenter, undrawn and hidden from TalkBack, and the two swap in one crossfade`() {
        assertEquals(150, SWAP_MILLIS)
        // Never lower than Recenter, so the bar keeps its height when Recenter comes.
        assertTrue(".heightIn(min = RECENTER_HEIGHT)" in bar)
        assertTrue(".graphicsLayer { alpha = numbersAlpha }" in bar)
        assertTrue(".then(if (recentering) Modifier.clearAndSetSemantics {} else Modifier)" in bar)
        // One transition on whether the map is held, faded both ways; Compose runs it at once with the
        // system's animations off.
        assertTrue("updateTransition(targetState = recentering" in bar)
        assertTrue("""swap.animateFloat(transitionSpec = { tween(SWAP_MILLIS) }, label = "numbers") { if (it) 0f else 1f }""" in bar, bar)
        assertTrue("enter = fadeIn(tween(SWAP_MILLIS))" in bar && "exit = fadeOut(tween(SWAP_MILLIS))" in bar)
        // The list's bar has Map in Overview's place and no Recenter.
        assertTrue("val views = onMap == null" in bar)
        assertTrue("val recentering = views && held" in bar)
    }

    @Test
    fun `Overview off is outlined in the outline colour, on is Material's tonal button ringed in amber ink, and Recenter is the amber button`() {
        val toggle = Sources.functionBody(buttons, "fun OverviewToggle(")
        assertTrue("border = BorderStroke(1.dp, tokens.outline)" in toggle)
        assertTrue("outlinedButtonColors(contentColor = tokens.onCard)" in toggle)
        assertTrue("FilledTonalButton(" in toggle)
        assertTrue("border = BorderStroke(2.dp, tokens.accentInk)" in toggle)
        assertTrue("role = Role.Switch" in toggle)
        // One word, Overview, in both looks of the switch.
        assertEquals(1, Regex("""R\.string\.guidance_overview\b""").findAll(toggle).count())
        val recenter = Sources.functionBody(buttons, "fun RecenterButton(")
        assertTrue("AccentButton(" in recenter)
        // Never Material's filled button on the scheme's primary, which by day is the dark amber of text.
        assertFalse(Regex("""(?<![A-Za-z])Button\(""").containsMatchIn(recenter))
        assertTrue(".height(RECENTER_HEIGHT)" in recenter)
        assertTrue("maxLines = 1" in recenter && "softWrap = false" in recenter)
        assertTrue("TextAutoSize.StepBased(minFontSize = RECENTER_MIN_SIZE, maxFontSize = RECENTER_MAX_SIZE)" in recenter)
        assertEquals(48.dp, RECENTER_HEIGHT)
        assertEquals(14.sp, RECENTER_MAX_SIZE)
        assertEquals(11.sp, RECENTER_MIN_SIZE)
    }
}
