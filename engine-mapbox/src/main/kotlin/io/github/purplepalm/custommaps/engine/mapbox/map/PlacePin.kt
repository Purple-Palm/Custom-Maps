package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.properties.generated.CirclePitchScale
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/**
 * The pin of a place on one map: an amber dot with a dark edge at the place itself, such as the
 * building. A route to the place may end elsewhere, at the place's stop point or where the nearest
 * road is. The route line marks its own end; the pin shows where the place is, next to it.
 *
 * The dot is one source and one layer in the map's style. A style comes without them: they are
 * added when a place is first shown in it, which after a style load is [onStyleLoaded], and
 * [release] takes them out again. While no place is shown the layer stays, with nothing to draw.
 *
 * The layer is in the style's top slot: above the route line, which is in the middle slot, and
 * above the buildings a place usually lies in. The car's puck is in no slot and is drawn over all
 * of them, so the pin never hides the car.
 *
 * Nothing here may cost the map more than its pin: what goes wrong is written to the debug log.
 * Main thread only.
 */
internal class PlacePin(private val layers: Layers, private val debugLog: DebugLog) {

    /** The pin's source and layer in the style of one map. */
    interface Layers {
        /** False before the map can be asked and once it is gone. */
        val usable: Boolean

        /** Whether the style has both the pin's source and its layer. */
        fun exist(): Boolean

        /** Adds the source and the layer, with the pin at [point]. */
        fun add(point: GeoPoint)

        /** Moves the pin to [point]; with null the layer has nothing to draw. */
        fun set(point: GeoPoint?)

        /** Takes the layer and the source out of the style, whichever of them is there. */
        fun remove()
    }

    private var styleReady = false
    private var released = false

    /** Where the pin is wanted; null for no pin. */
    private var place: GeoPoint? = null

    /** Puts the pin at [point], or takes it off the map with null. Before the style is loaded it is only remembered. */
    fun show(point: GeoPoint?) {
        if (point == place) return
        place = point
        apply()
    }

    /** Call from the callback of a style load: the new style has no pin yet. */
    fun onStyleLoaded() {
        styleReady = true
        apply()
    }

    /** The map is going away: the pin's layer and source leave its style, and nothing is set on it any more. */
    fun release() {
        if (ready()) attempt { layers.remove() }
        released = true
    }

    private fun ready() = styleReady && !released && layers.usable

    private fun apply() {
        if (!ready()) return
        val point = place
        attempt {
            when {
                layers.exist() -> layers.set(point)
                point != null -> layers.add(point)
                // No pin wanted and none in the style: nothing to do.
            }
        }
    }

    private inline fun attempt(change: () -> Unit) {
        try {
            change()
        } catch (e: RuntimeException) {
            // The style refused: the map goes on without the pin.
            debugLog.error("placePin", e)
        }
    }

    companion object {
        private const val SOURCE = "custommaps-place"
        private const val LAYER = "custommaps-place-pin"

        /** The slot of Mapbox Standard above roads, buildings and most labels. */
        private const val SLOT = "top"
        private const val RADIUS = 7.0
        private const val EDGE = 2.0

        /** The pin on [mapboxMap]. */
        fun on(mapboxMap: MapboxMap, debugLog: DebugLog): PlacePin {
            val layers = object : Layers {
                /** The source in the style now; its data is set through it. */
                private var source: GeoJsonSource? = null

                override val usable: Boolean get() = mapboxMap.isValid()

                override fun exist(): Boolean =
                    source != null && mapboxMap.styleSourceExists(SOURCE) && mapboxMap.styleLayerExists(LAYER)

                override fun add(point: GeoPoint) {
                    // One of the two may be left from an addition that failed half way.
                    remove()
                    val created = geoJsonSource(SOURCE) { geometry(point.toMapPoint()) }
                    mapboxMap.addSource(created)
                    mapboxMap.addLayer(
                        circleLayer(LAYER, SOURCE) {
                            slot(SLOT)
                            circleRadius(RADIUS)
                            circleColor(MapPalette.AMBER)
                            circleStrokeWidth(EDGE)
                            circleStrokeColor(MapPalette.GRAPHITE)
                            // Its own colors at night too, like the route line: the style's light does not dim it.
                            circleEmissiveStrength(1.0)
                            // The same size wherever it is on a tilted map.
                            circlePitchScale(CirclePitchScale.VIEWPORT)
                        },
                    )
                    source = created
                }

                override fun set(point: GeoPoint?) {
                    val source = source ?: return
                    // The source hands its data to the map on a thread of its own.
                    if (point == null) {
                        source.featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>()))
                    } else {
                        source.geometry(point.toMapPoint())
                    }
                }

                override fun remove() {
                    if (mapboxMap.styleLayerExists(LAYER)) mapboxMap.removeStyleLayer(LAYER)
                    if (mapboxMap.styleSourceExists(SOURCE)) mapboxMap.removeStyleSource(SOURCE)
                    source = null
                }
            }
            return PlacePin(layers, debugLog)
        }

        private fun GeoPoint.toMapPoint(): Point = Point.fromLngLat(longitude, latitude)
    }
}
