package io.github.purplepalm.custommaps.engine.mapbox.map

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.mapbox.maps.plugin.attribution.AttributionDialogManager
import com.mapbox.maps.plugin.attribution.AttributionDialogManagerImpl
import com.mapbox.maps.plugin.delegates.MapAttributionDelegate
import io.github.purplepalm.custommaps.nav.look.Look

/**
 * A context whose resources follow the app's look, whatever the phone's own dark theme says: the
 * activity's context with the app's theme, and only the night bit of the configuration's uiMode set
 * to the look's. A view or dialog made with it that has a day and a night variant built in, as the
 * map's (i) dialog has, shows the one of the look.
 */
internal object LookContext {

    fun of(base: Context, look: Look): Context {
        // Every field left undefined here keeps the activity's own value.
        val nightBit = Configuration().apply {
            uiMode = if (look == Look.NIGHT) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(base, base.applicationInfo.theme).apply { applyOverrideConfiguration(nightBit) }
    }
}

/**
 * The map's (i) dialog in the variant of the app's look: the SDK's own dialog, with its text, its
 * links and its telemetry choice ([AttributionDialogManagerImpl]), made with a [LookContext]. A
 * dialog open while the look changes stays as it is until it is closed; the next one opens in the
 * new variant. Every one of them closes when the activity stops, as the SDK's own dialog does.
 */
internal class LookAttribution(private val base: Context, look: Look) : AttributionDialogManager {

    private var current = AttributionDialogManagerImpl(LookContext.of(base, look))

    /** The dialogs of earlier looks, which may still be open. */
    private val earlier = ArrayList<AttributionDialogManager>(1)

    /** The next dialog opens in [look]'s variant. */
    fun setLook(look: Look) {
        earlier += current
        current = AttributionDialogManagerImpl(LookContext.of(base, look))
    }

    override fun showAttribution(mapAttributionDelegate: MapAttributionDelegate) {
        current.showAttribution(mapAttributionDelegate)
    }

    override fun onStop() {
        current.onStop()
        for (dialogs in earlier) dialogs.onStop()
        earlier.clear()
    }
}
