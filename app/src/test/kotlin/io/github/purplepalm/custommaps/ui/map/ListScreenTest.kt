package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The list of the maneuvers ahead in place of the phone's map, and the map put away meanwhile. The
 * project has no Compose UI tests, so the sources hold the rules no compiler checks: nothing on the
 * list moves by itself, its page is the black one in the night look's colours, its rows are made once
 * a step, and the map is kept, out of the window, with nothing calling its lifecycle.
 */
class ListScreenTest {

    private val list = Sources.code("ui/map/ListScreen.kt")
    private val screen = Sources.functionBody(Sources.code("ui/map/MapScreen.kt"), "fun MapScreen(")

    @Test
    fun `nothing on the list moves by itself`() {
        for (moving in listOf("Animated", "animate", "Transition", "ProgressIndicator", "withFrameNanos", "withFrameMillis", "LaunchedEffect")) {
            assertFalse(moving in list, moving)
        }
        // The replay's dot jumps to each position, and the Then chip, whose slide runs by itself, stays away.
        assertTrue("glides = false" in list)
        assertTrue("then = false" in list)
    }

    @Test
    fun `the list's page is black, in the night look's colours whatever the look, with the data's sources under it`() {
        val body = Sources.functionBody(list, "fun ListScreen(")
        assertTrue("CustomMapsTheme(Look.NIGHT)" in body, body)
        assertTrue(".background(LookTheme.tokens.listPage)" in body, body)
        assertTrue("R.string.data_attribution" in body, body)
        // Map in place of Overview.
        assertTrue("onMap = onMap" in list)
    }

    @Test
    fun `the rows are made once a step and only their distances are read at each update`() {
        val rows = Sources.functionBody(list, "fun UpcomingRows(")
        assertTrue("remember(upcoming, resources)" in rows, rows)
        assertTrue("ManeuverList.distanceFromCar(" in rows, rows)
        assertTrue("ManeuverList.dimmed(" in rows, rows)
    }

    @Test
    fun `the phone's map is kept and put away while the list shows, and nothing calls its lifecycle`() {
        // One map for the screen's life, made whatever the screen shows.
        assertEquals(1, Regex("createPhoneMap\\(").findAll(screen).count())
        assertTrue("phoneMap.setAway(listShown)" in screen)
        assertTrue("looks.onPhoneMapAway(listShown)" in screen)
        // The view leaves the parent it had before it is added again.
        assertTrue("(view.parent as? ViewGroup)?.removeView(view)" in screen)
        // The look goes to the map, and its close view to the look, only while the map is there.
        val shown = screen.substringAfter("if (!listShown) {").substringBefore("LifecycleStartEffect")
        assertTrue("looks.showOn(phoneMap::setLook)" in shown, shown)
        assertTrue("phoneMap.closeView.collect" in shown, shown)
        // Nothing here calls the view's lifecycle: Mapbox stops and starts it with the window.
        assertFalse(Regex("""\.view\s*\.\s*on[A-Z]""").containsMatchIn(screen), "a lifecycle call on the map's view")
        assertFalse(Regex("""\bMapView\b""").containsMatchIn(screen))
    }
}
