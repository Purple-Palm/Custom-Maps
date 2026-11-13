package io.github.purplepalm.custommaps

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.purplepalm.custommaps.look.LookController
import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.ui.CustomMapsApp
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.theme.LookPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** The look the screen was last composed in; null before its first composition. */
    private var composedLook: Look? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val createdAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        // Transparent bars over the app's own colours. Their icons follow the look (showWindowLook):
        // nothing sets them back at a configuration change, as the activity library's own call did.
        WindowCompat.enableEdgeToEdge(window)
        // Recents and a start from it show the theme's graphite in both looks, not a picture of the
        // last look: one taken by day would light up a dark car at night. From Android 13 on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        val graph = (application as CustomMapsApplication).graph
        val looks = graph.lookController
        // The first look of the process; an activity created again finds it decided.
        looks.start()
        holdFirstFrame(createdAt, looks)
        lifecycleScope.launch { looks.look.collect { showWindowLook(it) } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { looks.runWhileStarted() } }
        recreateWhenTokenChanges(graph)
        setContent {
            val look by looks.look.collectAsState()
            CustomMapsTheme(look) {
                CustomMapsApp(graph)
            }
            SideEffect { composedLook = look }
        }
    }

    /**
     * Keeps the first frame back until the screen is composed in the look this process decided, and
     * no longer than [FIRST_FRAME_HOLD_MS] after [createdAt], so the first frame shows no other look.
     * Android asks at each frame it offers before the first one is drawn; once drawn the listener is
     * gone. An activity created again finds the look decided and draws at once.
     */
    private fun holdFirstFrame(createdAt: Long, looks: LookController) {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val ready = looks.decided.value && composedLook == looks.look.value
                    if (!ready && SystemClock.uptimeMillis() - createdAt < FIRST_FRAME_HOLD_MS) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    /** The window's own background, behind the map before it draws, and the bars' icons for [look]. */
    private fun showWindowLook(look: Look) {
        val palette = LookPalette.of(look)
        window.setBackgroundDrawable(palette.screen.toInt().toDrawable())
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = palette.darkBarIcons
            isAppearanceLightNavigationBars = palette.darkBarIcons
        }
    }

    /**
     * A map keeps the token it was created with. Once this activity has shown maps, a replaced or
     * removed token rebuilds it, and with it the map, from scratch.
     */
    private fun recreateWhenTokenChanges(graph: AppGraph) {
        lifecycleScope.launch {
            val shown = graph.mapsAccess.first { it is MapsAccess.Ready }
            graph.mapsAccess.first { it != shown && it != MapsAccess.Checking }
            recreate()
        }
    }

    private companion object {
        /** The longest the first frame waits for the look, from the start of onCreate. */
        const val FIRST_FRAME_HOLD_MS = 300L
    }
}
