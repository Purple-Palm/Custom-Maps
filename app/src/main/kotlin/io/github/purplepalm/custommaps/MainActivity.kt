package io.github.purplepalm.custommaps

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.ui.CustomMapsApp
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as CustomMapsApplication).graph
        recreateWhenTokenChanges(graph)
        setContent {
            // The night look until the app chooses one by the sun.
            CustomMapsTheme(Look.NIGHT) {
                CustomMapsApp(graph)
            }
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
}
