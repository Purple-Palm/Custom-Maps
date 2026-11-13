package io.github.purplepalm.custommaps.engine.mapbox.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.mapbox.bindgen.Value
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.lanes.CarLayerWatch
import io.github.purplepalm.custommaps.nav.lanes.geometry.CarBar
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import kotlin.math.ceil

/**
 * The car drawn by the app itself (the own lanes' design 5.4; T2, E4, R3, R20), with the Debug switch Draw the app's lanes
 * on; with it off nothing here touches the style or the location component, and the puck is the navigation SDK's, as ever.
 *
 * With the switch on, from Start to the end of guidance, the location component's puck is invisible ([Port.hidePuck]): set
 * once at Start, or once as a map is made during such a drive, which is its first setting, and back at guidance end
 * ([Port.showPuck]). In between no setting of the location component changes, whatever the camera style, the lane zoom or
 * anything else does: every change rebuilds the puck and cancels its glide. The camera still follows the SDK's puck, which
 * glides on unseen. The app draws the car on a location-indicator layer of its own, on top of everything ([Port.addLayer]),
 * moved every frame from the puck's animated position, which the map hands on ([onPosition]): two property sets a frame,
 * its place and its bearing ([Port.moveTo]), and nothing else. Over the drawn lanes, while the lit lane shows in the route
 * line's place, the car is a bar across the carriageway at the puck's station ([CarBar]), square to the lanes; elsewhere
 * the usual dot with the puck's own images and bearing. The bar's image is made again only where the carriageway's width
 * has changed by [CarBar.WIDTH_STEP_METERS] ([Port.showBar]); its size is a zoom expression of base 2 that keeps it at its
 * ground size.
 *
 * While the map is not on screen, or put away for the list of the turns, nothing here changes a setting, adds a layer or
 * draws: what is due waits for the map to be back ([setShown]), when the puck's newest place comes with its first frame.
 * A watchdog ([CarLayerWatch]) gives the car back to the SDK's puck when no position has come for a second while the layer
 * draws the car, the map is on screen and drawing frames and the car moves ([check]): the puck visible again once, the only
 * setting change of the drive, the own layer gone for the rest of it, and one "car_layer" line with the reason. A style that
 * refuses a call costs the own car the same way, with one error line. A "car_layer" line says each change of who draws the
 * car: counts and words only. Main thread only.
 */
internal class CarLayer(
    private val port: Port,
    private val debugLog: DebugLog,
    /** Runs a call on the main thread once the one under way has returned: a fallback never changes a setting inside the location component's own callback. */
    private val later: (() -> Unit) -> Unit,
    /** The clock, in nanoseconds, of the watchdog. */
    private val nanos: () -> Long = System::nanoTime,
) {

    /** The location component's puck and the own car layer in the style of one map. Every call may throw where the style refuses. */
    interface Port {
        /** False before the map can be asked and once it is gone. */
        val usable: Boolean

        /** The location component's puck made invisible: its images at no opacity. Rebuilds the puck. */
        fun hidePuck()

        /** The location component's usual puck again. Rebuilds the puck. */
        fun showPuck()

        /** Adds the own layer on top of every layer, hidden until it is shown, with the dot's images; what was there of it goes first. */
        fun addLayer()

        /** Takes the own layer out of the style, if it is there. */
        fun removeLayer()

        /** The dot: the puck's own images, as the location component draws them, and the layer shown. */
        fun showDot()

        /** The bar [widthMeters] across ([twoWay]: its tick on the outer side alone), at its ground size at [latitude], and the layer shown. */
        fun showBar(widthMeters: Double, twoWay: Boolean, latitude: Double)

        /** The layer's place and bearing, in degrees clockwise from north: the two property sets of a frame. */
        fun moveTo(latitude: Double, longitude: Double, bearing: Double)
    }

    /** What the own layer shows. */
    private enum class Mark { NONE, DOT, BAR }

    /** Why the own car was given back to the SDK's puck before the drive's end. */
    enum class Fallback(val logName: String) {
        /** No position came for a second while it counted ([CarLayerWatch]). */
        NO_POSITION("no_position"),

        /** The style refused a call. */
        EXCEPTION("exception"),
    }

    private val watch = CarLayerWatch()

    // What the map is told.
    private var drawing = false
    private var guidance = false
    private var guidanceSeen = false
    private var shown = false
    private var styleReady = false
    private var released = false

    /** The own car was given back for the drive under way; the next drive tries again. */
    private var failed = false

    // What is set now.
    private var puckHidden = false
    private var layerAdded = false
    private var mark = Mark.NONE
    private var barWidth = Double.NaN
    private var barTwoWay = false
    private var barLatitude = Double.NaN

    /** Why the next change of who draws the car is due, for its "car_layer" line. */
    private var reason = REASON_START

    /** Whether the app draws the car now: the puck is invisible and the own layer in the style of a map that is there. */
    val live: Boolean get() = puckHidden && layerAdded && !failed && !released

    /** The Debug switch Draw the app's lanes: on, the app draws the car during guidance from now on; off, the SDK's puck again. */
    fun setDrawing(on: Boolean) {
        if (on == drawing) return
        drawing = on
        reason = REASON_SWITCH
        reconcile()
    }

    /** Guidance began ([active]) or ended. A map that learns of a drive under way with its first word draws the car from its start. */
    fun onGuidance(active: Boolean) {
        if (active == guidance) {
            guidanceSeen = true
            return
        }
        guidance = active
        if (active) {
            reason = if (guidanceSeen) REASON_START else REASON_SCENE
            watch.reset()
        } else {
            reason = REASON_GUIDANCE_END
            // The next drive tries again.
            failed = false
        }
        guidanceSeen = true
        reconcile()
    }

    /**
     * Whether the map is on screen and drawing ([shown]): its screen started and the map not put away. Meanwhile nothing is
     * set or drawn; back, what became due is done, and the next frame brings the puck's newest place.
     */
    fun setShown(shown: Boolean) {
        if (shown == this.shown) return
        this.shown = shown
        if (shown) reconcile()
    }

    /** Call from the callback of a style load: the new style has no own layer. */
    fun onStyleLoaded() {
        styleReady = true
        layerAdded = false
        mark = Mark.NONE
        reconcile()
    }

    /**
     * Every frame of the puck: its animated position at [latitude], [longitude] and the SDK's own bearing [puckBearing];
     * [bar], the car's bar along the lanes drawn, while the lit lane shows in the route line's place, else null. Moves the own
     * layer there: the bar where the bar finds the puck over drawn lanes, else the dot. Allocates nothing of its own.
     */
    fun onPosition(latitude: Double, longitude: Double, puckBearing: Double, bar: CarBar?) {
        // With the switch off, or the car given back, nothing but these reads.
        if (!live || !shown) return
        watch.onPosition(nanos())
        try {
            if (bar != null && bar.place(latitude, longitude)) {
                if (mark != Mark.BAR || bar.twoWay != barTwoWay || CarBar.widthChanged(barWidth, bar.width) || LaneDrawing.stopsStale(barLatitude, bar.latitude)) {
                    port.showBar(bar.width, bar.twoWay, bar.latitude)
                    mark = Mark.BAR
                    barWidth = bar.width
                    barTwoWay = bar.twoWay
                    barLatitude = bar.latitude
                }
                port.moveTo(bar.latitude, bar.longitude, bar.bearing)
            } else {
                if (mark != Mark.DOT) {
                    port.showDot()
                    mark = Mark.DOT
                    barWidth = Double.NaN
                }
                port.moveTo(latitude, longitude, puckBearing)
            }
        } catch (e: RuntimeException) {
            debugLog.error(WHERE, e)
            // Nothing more is drawn; the puck comes back once this frame's callback has returned.
            failed = true
            later { fallBack(Fallback.EXCEPTION) }
        }
    }

    /**
     * Once a second during guidance: the watchdog, counting while the map is [onScreen] and drawing frames and the car moves at
     * [speedMps], the matched speed. Where it trips, the car goes back to the SDK's puck for the rest of the drive.
     */
    fun check(onScreen: Boolean, speedMps: Double?) {
        if (watch.check(nanos(), live = live && guidance, onScreen = onScreen && shown, speedMps = speedMps)) fallBack(Fallback.NO_POSITION)
    }

    /** The map is going away: nothing is set any more. Its location component goes with it. */
    fun release() {
        released = true
    }

    /** Sets what is due: the puck invisible and the own layer in the style while the app draws the car, else neither. */
    private fun reconcile() {
        if (!shown || released) return
        val wanted = drawing && guidance && !failed
        if (wanted) {
            if (!puckHidden) {
                attempt {
                    port.hidePuck()
                    puckHidden = true
                    logLine(DRAWER_OWN, reason)
                }
            }
            if (puckHidden && styleReady && !layerAdded && port.usable) {
                attempt {
                    port.addLayer()
                    layerAdded = true
                    mark = Mark.NONE
                }
            }
        } else {
            if (layerAdded) quietly { if (port.usable) port.removeLayer() }
            layerAdded = false
            mark = Mark.NONE
            if (puckHidden) {
                quietly { port.showPuck() }
                puckHidden = false
                logLine(DRAWER_SDK, reason)
            }
        }
    }

    /** Runs [change]; where the style or the location component refuses, one error line and the car back to the SDK's puck. */
    private inline fun attempt(change: () -> Unit) {
        try {
            change()
        } catch (e: RuntimeException) {
            debugLog.error(WHERE, e)
            fallBack(Fallback.EXCEPTION)
        }
    }

    /** Runs [change] while the car goes back to the SDK's puck: what still fails is only written down. */
    private inline fun quietly(change: () -> Unit) {
        try {
            change()
        } catch (e: RuntimeException) {
            debugLog.error(WHERE, e)
        }
    }

    /**
     * The car back to the SDK's puck for the rest of the drive: the own layer gone, the puck visible again, the only setting
     * change between Start and the drive's end, and one "car_layer" line with [cause]. Once a drive.
     */
    private fun fallBack(cause: Fallback) {
        if (released) return
        val had = puckHidden || layerAdded
        failed = true
        if (layerAdded) quietly { if (port.usable) port.removeLayer() }
        layerAdded = false
        mark = Mark.NONE
        if (puckHidden) quietly { port.showPuck() }
        puckHidden = false
        if (had) logLine(DRAWER_SDK, REASON_FALLBACK, mapOf("carLayerFallback" to cause.logName))
    }

    /** A "car_layer" line: who draws the car from now on ([drawer]) and why. */
    private fun logLine(drawer: String, reason: String, more: Map<String, Any?> = emptyMap()) {
        debugLog.log(LINE, mapOf("drawer" to drawer, "reason" to reason) + more)
    }

    companion object {
        /** Where an error line says the car layer failed. */
        const val WHERE = "carLayer"

        /** The debug log's line about who draws the car, and its words. */
        const val LINE = "car_layer"
        const val DRAWER_OWN = "own"
        const val DRAWER_SDK = "sdk"

        /** Guidance began with the switch on. */
        const val REASON_START = "start"

        /** A map made during a drive with the switch on: its first setting. */
        const val REASON_SCENE = "scene"
        const val REASON_SWITCH = "switch"
        const val REASON_GUIDANCE_END = "guidance_end"
        const val REASON_FALLBACK = "fallback"

        /** The own location-indicator layer, and its images. */
        const val LAYER = "custommaps-car"
        const val TOP_IMAGE = "custommaps-car-top"
        const val BEARING_IMAGE = "custommaps-car-bearing"
        const val SHADOW_IMAGE = "custommaps-car-shadow"
        const val BAR_IMAGE = "custommaps-car-bar"
        const val NO_IMAGE = "custommaps-car-none"

        /** The dot as the location component draws its puck: its perspective compensation and its images' lift at a tilt. */
        const val DOT_PERSPECTIVE = 0.9
        const val DOT_PITCH_DISPLACEMENT = 4.0

        /** The bar is scaled by the projection, as the map's own ground is ([CarBar.sizeStops]). */
        const val BAR_PERSPECTIVE = 0.0

        /**
         * The own layer and the puck of [location] on [mapboxMap], whose usual puck is [puck]: the bar in the puck's blue,
         * taken from the puck's own top image, with a rim in the white of its own shadow image.
         */
        fun on(context: Context, mapboxMap: MapboxMap, location: LocationComponentPlugin, puck: LocationPuck2D): Port = object : Port {
            private val hidden = puck.copy(opacity = 0f)
            private val pixelRatio = mapboxMap.pixelRatio.toDouble()
            private val top: Bitmap? by lazy { bitmapOf(context, puck.topImage) }
            private val bearing: Bitmap? by lazy { bitmapOf(context, puck.bearingImage) }
            private val shadow: Bitmap? by lazy { bitmapOf(context, puck.shadowImage) }
            private val zero = Value.valueOf(0.0)

            override val usable: Boolean get() = mapboxMap.isValid()

            override fun hidePuck() = location.updateSettings { locationPuck = hidden }

            override fun showPuck() = location.updateSettings { locationPuck = puck }

            override fun addLayer() {
                removeLayer()
                image(TOP_IMAGE, checkNotNull(top) { "No top image" })
                bearing?.let { image(BEARING_IMAGE, it) }
                shadow?.let { image(SHADOW_IMAGE, it) }
                image(NO_IMAGE, createBitmap(1, 1))
                // On top of every layer, hidden until its first frame; the dot's images, as the location component sets its own.
                val properties = hashMapOf(
                    "id" to Value.valueOf(LAYER),
                    "type" to Value.valueOf("location-indicator"),
                    // Each place and bearing is drawn as it is set: the puck's own glide moves it.
                    "location-transition" to NO_TRANSITION,
                    "bearing-transition" to NO_TRANSITION,
                    "top-image" to Value.valueOf(TOP_IMAGE),
                    "bearing-image" to Value.valueOf(if (bearing != null) BEARING_IMAGE else NO_IMAGE),
                    "shadow-image" to Value.valueOf(if (shadow != null) SHADOW_IMAGE else NO_IMAGE),
                    "perspective-compensation" to Value.valueOf(DOT_PERSPECTIVE),
                    "image-pitch-displacement" to Value.valueOf(DOT_PITCH_DISPLACEMENT),
                    "visibility" to Value.valueOf("none"),
                )
                val error = mapboxMap.addStyleLayer(Value.valueOf(properties), null).error
                if (error != null) throw IllegalStateException("The style refused the car layer")
            }

            override fun removeLayer() {
                if (mapboxMap.styleLayerExists(LAYER)) mapboxMap.removeStyleLayer(LAYER)
            }

            override fun showDot() {
                set("top-image", Value.valueOf(TOP_IMAGE))
                set("bearing-image", Value.valueOf(if (bearing != null) BEARING_IMAGE else NO_IMAGE))
                set("shadow-image", Value.valueOf(if (shadow != null) SHADOW_IMAGE else NO_IMAGE))
                set("top-image-size", Value.valueOf(1.0))
                set("perspective-compensation", Value.valueOf(DOT_PERSPECTIVE))
                set("image-pitch-displacement", Value.valueOf(DOT_PITCH_DISPLACEMENT))
                set("visibility", Value.valueOf("visible"))
            }

            override fun showBar(widthMeters: Double, twoWay: Boolean, latitude: Double) {
                image(BAR_IMAGE, barBitmap(widthMeters, twoWay))
                set("top-image", Value.valueOf(BAR_IMAGE))
                set("bearing-image", Value.valueOf(NO_IMAGE))
                set("shadow-image", Value.valueOf(NO_IMAGE))
                set("top-image-size", sizeOf(latitude))
                set("perspective-compensation", Value.valueOf(BAR_PERSPECTIVE))
                set("image-pitch-displacement", zero)
                set("visibility", Value.valueOf("visible"))
            }

            override fun moveTo(latitude: Double, longitude: Double, bearing: Double) {
                set("location", Value.valueOf(listOf(Value.valueOf(latitude), Value.valueOf(longitude), zero)))
                set("bearing", Value.valueOf(bearing))
            }

            /** The bar [widthMeters] across, as an image in the puck's blue with a white rim and a forward tick on each outer apron. */
            private fun barBitmap(widthMeters: Double, twoWay: Boolean): Bitmap {
                val blue = centreColour(checkNotNull(top) { "No top image" })
                val white = centreColour(checkNotNull(shadow) { "No shadow image" })
                val scale = (CarBar.IMAGE_DP_PER_METER * pixelRatio).toFloat()
                val half = (widthMeters / 2.0).toFloat()
                val beside = (CarBar.TICK_GAP_METERS + CarBar.TICK_WIDTH_METERS + IMAGE_MARGIN_METERS).toFloat()
                val reach = (CarBar.TICK_REACH_METERS + IMAGE_MARGIN_METERS).toFloat()
                val width = ceil((2f * (half + beside)) * scale).toInt().coerceAtLeast(1)
                val height = ceil(2f * reach * scale).toInt().coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)
                val cx = width / 2f
                val cy = height / 2f
                // Metres across (right positive) and along (ahead positive) to the image's pixels: ahead is up.
                fun x(m: Float) = cx + m * scale
                fun y(m: Float) = cy - m * scale
                val rim = (CarBar.RIM_METERS).toFloat()
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = blue
                }
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = rim * scale
                    strokeJoin = Paint.Join.ROUND
                    color = white
                }
                // Each shape inset by half the rim, so the rim's outer edge is the shape's own.
                val inset = rim / 2f
                val depth = (CarBar.DEPTH_METERS / 2.0).toFloat()
                val body = Path().apply {
                    moveTo(x(-half + inset), y(depth - inset))
                    lineTo(x(half - inset), y(depth - inset))
                    lineTo(x(half - inset), y(-depth + inset))
                    lineTo(x(-half + inset), y(-depth + inset))
                    close()
                }
                canvas.drawPath(body, fill)
                canvas.drawPath(body, edge)
                val gap = CarBar.TICK_GAP_METERS.toFloat()
                val tick = CarBar.TICK_WIDTH_METERS.toFloat()
                val tip = CarBar.TICK_REACH_METERS.toFloat()
                // On a two-way street the inner tick would stand on the oncoming half: the outer one alone.
                val sides = if (twoWay) listOf(1f) else listOf(-1f, 1f)
                for (side in sides) {
                    val near = side * (half + gap)
                    val far = side * (half + gap + tick)
                    val path = Path().apply {
                        moveTo(x(near), y(-depth + inset))
                        lineTo(x(far), y(-depth + inset))
                        lineTo(x((near + far) / 2f), y(tip - inset))
                        close()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, edge)
                }
                return bitmap
            }

            private fun image(id: String, bitmap: Bitmap) {
                val error = mapboxMap.addImage(id, bitmap).error
                if (error != null) throw IllegalStateException("The style refused the image $id")
            }

            /** One property of the own layer; the style's refusal is thrown, its words without the value. */
            private fun set(property: String, value: Value) {
                val error = mapboxMap.setStyleLayerProperty(LAYER, property, value).error
                if (error != null) throw IllegalStateException("The style refused $property of the car layer")
            }
        }

        /** No transition: a property takes its value at once. */
        private val NO_TRANSITION: Value = Value.valueOf(hashMapOf("duration" to Value.valueOf(0L), "delay" to Value.valueOf(0L)))

        /** Room around the bar's shapes in its image, so the rim is never cut. */
        private const val IMAGE_MARGIN_METERS = 0.3

        /** The bar's size as a zoom expression of base 2 at [latitude]: its image at its ground size at every zoom ([CarBar.sizeStops]). */
        private fun sizeOf(latitude: Double): Expression = Expression.interpolate {
            exponential { literal(2.0) }
            zoom()
            for ((zoom, size) in CarBar.sizeStops(latitude)) stop(zoom, size)
        }

        /** An image of the puck as a bitmap at the screen's density; null for none. */
        private fun bitmapOf(context: Context, holder: ImageHolder?): Bitmap? {
            holder?.bitmap?.let { return it }
            val id = holder?.drawableId ?: return null
            return ContextCompat.getDrawable(context, id)?.toBitmap()
        }

        /** The colour at the middle of [bitmap]: the puck's blue in its top image, the white of its disc in its shadow image. */
        private fun centreColour(bitmap: Bitmap): Int = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }
}
