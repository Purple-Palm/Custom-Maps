package io.github.purplepalm.custommaps.engine.mapbox.map

import android.os.Handler
import android.os.Looper
import com.mapbox.bindgen.Value
import com.mapbox.maps.MapboxMap
import io.github.purplepalm.custommaps.nav.debug.DebugLog

/**
 * Lane details on one map: the lane markings, painted arrows, stop lines and crosswalks that Mapbox
 * Standard can draw where Mapbox has them, from zoom 16. They are a setting of the style
 * (showHdRoads), off unless asked for.
 *
 * The navigation SDK turns them off. Every viewport data source looks at the setting when the style
 * is first ready and on every style load, and sets it to false if it is true; at no other time. So
 * the setting is never turned on before or while a style loads: the SDK would turn it off again,
 * and the map would show the lane details fade out. [onStyleLoaded] is called from the callback of
 * the style load and only leaves the setting for later, a message for the main thread. By the time
 * that message runs, everything that listens for the style load has run and found nothing to turn
 * off, and then the setting is made. Nothing turns it off after that.
 *
 * That order is read from the SDK's sources, not promised by it. So [check] looks again once a
 * second during guidance, and once shortly after a style load, and makes the setting again if it
 * was changed; how often that happened is in the camera line of the debug log.
 *
 * The SDK has a switch that stops it from turning lane details off. It is for Mapbox's own modules
 * and is not used here. Main thread only.
 */
internal class LaneDetails(
    private val setting: Setting,
    private val debugLog: DebugLog,
    private val later: (delayMillis: Long, task: () -> Unit) -> Unit,
) {

    /** The style's own switch for lane details, on one map. */
    interface Setting {
        /** False before the map can be asked and once it is gone. */
        val usable: Boolean

        /** What the style says now; null if it has no such setting. */
        fun read(): Boolean?

        /** Makes the setting. Returns what went wrong, or null. */
        fun write(on: Boolean): String?
    }

    private var styleReady = false
    private var released = false
    private var reapplied = 0

    /** What the style said when it was last asked; null when it has no such setting, or was not asked yet. */
    private var shown: Boolean? = null

    /** The driver's switch in Settings. */
    var enabled = false
        private set

    /** Turns lane details on or off, at once; the map fades between the two. */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        apply(REASON_SETTING)
    }

    /**
     * Call from the callback of the style load. The setting is made in a later message of the main
     * thread, after everything else that listens for the style load.
     */
    fun onStyleLoaded() {
        styleReady = true
        // A style comes with lane details off: there is nothing to do unless they are wanted.
        if (!enabled) return
        later(0L) { apply(REASON_STYLE_LOADED) }
        later(RECHECK_MILLIS) { check() }
    }

    /** Reads what the style says, and makes the setting again if something changed it. */
    fun check() {
        if (!ready()) return
        val now = setting.read()
        shown = now
        if (now != null && now != enabled) {
            reapplied += 1
            apply(REASON_REAPPLIED)
        }
    }

    /**
     * For the camera line: the switch, what the style said at the last [check], and how often the
     * setting had to be made again.
     */
    fun diagnostics(): Map<String, Any?> = mapOf("laneDetails" to enabled, "hdRoads" to shown, "hdRoadsReapplied" to reapplied)

    /** The map is going away: nothing is set on it any more, also not by what was left for later. */
    fun release() {
        released = true
    }

    private fun ready() = styleReady && !released && setting.usable

    private fun apply(reason: String) {
        if (!ready()) return
        val error = setting.write(enabled)
        shown = setting.read()
        debugLog.log(
            "lane_details",
            mapOf("reason" to reason, "wanted" to enabled, "read" to shown, "error" to error).filterValues { it != null },
        )
    }

    companion object {
        const val REASON_SETTING = "setting"
        const val REASON_STYLE_LOADED = "style_loaded"
        const val REASON_REAPPLIED = "reapplied"

        /** How long after a style load the setting is looked at again, outside guidance too. */
        const val RECHECK_MILLIS = 1_000L

        /** The name Mapbox Standard has as an import when it is the style itself, as in [MapPalette]. */
        private const val BASEMAP = "basemap"
        private const val SHOW_HD_ROADS = "showHdRoads"

        /** Lane details on [mapboxMap], with what is left for later run on the main thread. */
        fun on(mapboxMap: MapboxMap, debugLog: DebugLog): LaneDetails {
            val main = Handler(Looper.getMainLooper())
            val setting = object : Setting {
                override val usable: Boolean get() = mapboxMap.isValid()

                override fun read(): Boolean? =
                    mapboxMap.getStyleImportConfigProperty(BASEMAP, SHOW_HD_ROADS).value?.value?.contents as? Boolean

                override fun write(on: Boolean): String? =
                    mapboxMap.setStyleImportConfigProperty(BASEMAP, SHOW_HD_ROADS, Value.valueOf(on)).error
            }
            return LaneDetails(setting, debugLog) { delayMillis, task -> main.postDelayed(task, delayMillis) }
        }
    }
}
