package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.SourceDataLoadedType
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerAbove
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.slotLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing

/**
 * The layers that draw the app's own lanes on the map (the own lanes' design 2.2, 7 and 9), from one GeoJSON source
 * ([SOURCE]) of the features [LaneDrawing] makes, each with a [Key.KIND] and, for lines and symbols, its [Key.PAINT]
 * and [Key.WIDTH].
 *
 * Their order inside the style's middle slot, from the bottom (design 7): Standard's roads, which lie below the slot;
 * the [Group.UNDER_ROUTE] layers, below the navigation SDK's route line ([LayerOrder.ROUTE_LINE_BOTTOM]): the asphalt
 * with lanes kept for buses and the like darker on it, the tints, the lit lane and its edges, the eraser that covers
 * the lit lane behind the car ([ERASER]), the paint lines of two widths and the paint's shapes (the car-pool diamonds); the SDK's other routes (its primary line hidden while the app
 * draws its own); the app's own route line; the [Group.OVER_ROUTE] layers above it, the lane arrows and their edge;
 * the SDK's maneuver arrow; and in the top slot the 3D buildings and the labels.
 *
 * Every width is the feature's ground width as a zoom-only base-2 expression ([LaneDrawing.widthStops]) and every layer
 * shows from its minimum zoom ([LaneDrawing.ASPHALT_MIN_ZOOM], [LaneDrawing.SYMBOL_MIN_ZOOM],
 * [LaneDrawing.LIT_MIN_ZOOM]), both for the latitude the drawing lies at, made again when that moves
 * [LaneDrawing.LATITUDE_STEP_DEGREES]; no layer has an opacity ramp. Colours come from [MapPalette.lanes] of the look
 * ([LaneColours]) and every layer has emissive strength 1, so the style's light changes none of them. The layers add no
 * attribution of their own.
 *
 * Two ways to keep the order are built, [PLACEMENT] chooses: own slots in the middle slot ([Placement.SLOTS]: two
 * [UNDER_SLOT] and [OVER_SLOT] markers, every layer in one of them, so the order holds whatever the creation order),
 * or chained positions ([Placement.CHAINED]: each layer added right below the route line's bottom layer, or right above
 * the layer before it). Whether a slot inside a slot works on a phone is not known; the chained positions are what the
 * app's own route line already uses, so they are the choice until the "map_layers" line shows the slots in order.
 */
internal object LaneLayers {

    /** The drawing's source: the features of one drawing window. */
    const val SOURCE = "custommaps-lanes"

    const val ASPHALT = "custommaps-lanes-asphalt"
    const val TINT = "custommaps-lanes-tint"
    const val LIT = "custommaps-lanes-lit"
    const val LIT_EDGES = "custommaps-lanes-lit-edges"
    const val ERASER = "custommaps-lanes-eraser"
    const val PAINT = "custommaps-lanes-paint"
    const val PAINT_WIDE = "custommaps-lanes-paint-wide"
    const val SHAPES = "custommaps-lanes-shapes"
    const val ARROWS = "custommaps-lanes-arrows"
    const val ARROW_EDGES = "custommaps-lanes-arrow-edges"

    /** The own slots of [Placement.SLOTS]: one under the navigation SDK's route line, one over the app's own route line. */
    const val UNDER_SLOT = "custommaps-lanes-under"
    const val OVER_SLOT = "custommaps-lanes-over"

    /** Mapbox Standard's slot of the route line. */
    const val SLOT = "middle"

    /** The lane arrows' edge, on the screen. */
    const val ARROW_EDGE_DP = 1.0

    /**
     * The eraser's width on the ground: one freeway lane and 0.3 m, so it covers the lit lane and its edges, which lie
     * centred on the lane's lines, wherever it lies about the route's path.
     */
    const val ERASER_METERS = LaneTopology.FREEWAY_LANE_METERS + 0.3

    /** The property names of a drawn feature, as the layers read them. */
    object Key {
        /** [LaneDrawing.Kind.logName]. */
        const val KIND = "kind"

        /** A line's or a symbol's paint: [PAINT_WHITE] or [PAINT_YELLOW]. */
        const val PAINT = "paint"

        /** A line's width: [WIDTH_NORMAL], [WIDTH_WIDE] or [WIDTH_EDGE]. */
        const val WIDTH = "width"

        const val PAINT_WHITE = "white"
        const val PAINT_YELLOW = "yellow"
        const val WIDTH_NORMAL = "normal"
        const val WIDTH_WIDE = "wide"
        const val WIDTH_EDGE = "edge"

        /** The feature state of a tint's piece the car has passed: true, and the piece is not drawn. */
        const val STATE_BEHIND = "behind"

        /** The feature state of a lit piece where the route has traffic: one of the levels below, else the route's amber. */
        const val STATE_LEVEL = "level"

        const val LEVEL_MODERATE = "moderate"
        const val LEVEL_HEAVY = "heavy"
        const val LEVEL_SEVERE = "severe"
        const val LEVEL_CLOSED = "closed"
    }

    /** Where in the middle slot a layer lies. */
    enum class Group {
        /** Below the navigation SDK's route line, its other routes and the app's own route line. */
        UNDER_ROUTE,

        /** Above the app's own route line, below the SDK's maneuver arrow. */
        OVER_ROUTE,
    }

    /**
     * One layer: [id]; a [fill] or a line; the [kinds] of feature it draws, a line's of [width] only where one is
     * given; its [group]; shown from [minZoom] at latitude 0; a line [fixedDp] wide on the screen where given, else
     * [meters] or the ground width of [width]; [emissiveStrength] 1, so the style's light leaves its colours as they are;
     * from [source], the drawing's, or for the eraser the own route line's path.
     */
    class Spec(
        val id: String,
        val fill: Boolean,
        val kinds: List<LaneDrawing.Kind>,
        val width: LaneDrawing.Width?,
        val group: Group,
        val minZoom: Double,
        val fixedDp: Double? = null,
        val emissiveStrength: Double = 1.0,
        val meters: Double? = null,
        val source: String = SOURCE,
    )

    /** The lines of paint, of both widths. */
    private val PAINT_KINDS = listOf(LaneDrawing.Kind.LANE_LINE, LaneDrawing.Kind.EDGE_LINE, LaneDrawing.Kind.CHANNEL_LINE)

    /** The drawing's layers, the bottom one first. */
    val LAYERS: List<Spec> = listOf(
        Spec(ASPHALT, true, listOf(LaneDrawing.Kind.ASPHALT, LaneDrawing.Kind.KEPT_LANE), null, Group.UNDER_ROUTE, LaneDrawing.ASPHALT_MIN_ZOOM),
        Spec(TINT, true, listOf(LaneDrawing.Kind.TINT), null, Group.UNDER_ROUTE, LaneDrawing.LIT_MIN_ZOOM),
        Spec(LIT, true, listOf(LaneDrawing.Kind.LIT), null, Group.UNDER_ROUTE, LaneDrawing.LIT_MIN_ZOOM),
        Spec(LIT_EDGES, false, listOf(LaneDrawing.Kind.LIT_EDGE), LaneDrawing.Width.EDGE, Group.UNDER_ROUTE, LaneDrawing.LIT_MIN_ZOOM),
        // The path, in the road's colour where a lane is lit and transparent elsewhere, trimmed to the car: it covers the
        // lit lane behind the car, so the amber ends where the car is (the design's E3).
        Spec(ERASER, false, emptyList(), null, Group.UNDER_ROUTE, LaneDrawing.LIT_MIN_ZOOM, meters = ERASER_METERS, source = OwnRouteLine.SOURCE),
        Spec(PAINT, false, PAINT_KINDS, LaneDrawing.Width.NORMAL, Group.UNDER_ROUTE, LaneDrawing.ASPHALT_MIN_ZOOM),
        Spec(PAINT_WIDE, false, PAINT_KINDS, LaneDrawing.Width.WIDE, Group.UNDER_ROUTE, LaneDrawing.ASPHALT_MIN_ZOOM),
        Spec(SHAPES, false, listOf(LaneDrawing.Kind.DIAMOND), LaneDrawing.Width.NORMAL, Group.UNDER_ROUTE, LaneDrawing.SYMBOL_MIN_ZOOM),
        Spec(ARROWS, true, listOf(LaneDrawing.Kind.ARROW), null, Group.OVER_ROUTE, LaneDrawing.SYMBOL_MIN_ZOOM),
        Spec(ARROW_EDGES, false, listOf(LaneDrawing.Kind.ARROW), null, Group.OVER_ROUTE, LaneDrawing.SYMBOL_MIN_ZOOM, fixedDp = ARROW_EDGE_DP),
    )

    /** How the drawing's layers keep their places in the middle slot. */
    enum class Placement {
        /** In two own slots inside the middle slot: [UNDER_SLOT] and [OVER_SLOT]. */
        SLOTS,

        /** Each right below the route line's bottom layer, or right above the layer before it, in the middle slot. */
        CHAINED,
    }

    /** The way the app places the drawing's layers: chained positions, until nested slots are seen to work on a phone. */
    val PLACEMENT = Placement.CHAINED

    /** The value of [Key.WIDTH] for a line of [width]. */
    fun widthName(width: LaneDrawing.Width): String = when (width) {
        LaneDrawing.Width.NORMAL -> Key.WIDTH_NORMAL
        LaneDrawing.Width.WIDE -> Key.WIDTH_WIDE
        LaneDrawing.Width.EDGE -> Key.WIDTH_EDGE
    }

    /** The value of [Key.PAINT] for [paint]. */
    fun paintName(paint: LaneDrawing.Paint): String = when (paint) {
        LaneDrawing.Paint.WHITE -> Key.PAINT_WHITE
        LaneDrawing.Paint.YELLOW -> Key.PAINT_YELLOW
    }

    /**
     * Where one layer goes as it is added: [id], in [slot], right below [below] or right above [above] where one is
     * given, else at the end of its slot; [marker] for an own slot of [Placement.SLOTS], which draws nothing.
     */
    data class Position(val id: String, val slot: String, val below: String? = null, val above: String? = null, val marker: Boolean = false)

    /**
     * The drawing's layers in the order they are added, each with where it goes, by [placement]: first the layers under
     * the route line's bottom layer, and after them the ones over [overAnchor], the topmost layer of the app's own route
     * line.
     */
    fun positions(placement: Placement, overAnchor: String): List<Position> {
        val under = LAYERS.filter { it.group == Group.UNDER_ROUTE }
        val over = LAYERS.filter { it.group == Group.OVER_ROUTE }
        return when (placement) {
            Placement.CHAINED -> {
                // Each one right below the route line's bottom layer lies above the one added before it.
                val out = under.map { Position(it.id, SLOT, below = LayerOrder.ROUTE_LINE_BOTTOM) }.toMutableList()
                var anchor = overAnchor
                for (spec in over) {
                    out.add(Position(spec.id, SLOT, above = anchor))
                    anchor = spec.id
                }
                out
            }
            Placement.SLOTS ->
                listOf(
                    Position(UNDER_SLOT, SLOT, below = LayerOrder.ROUTE_LINE_BOTTOM, marker = true),
                    Position(OVER_SLOT, SLOT, above = overAnchor, marker = true),
                ) + under.map { Position(it.id, UNDER_SLOT) } + over.map { Position(it.id, OVER_SLOT) }
        }
    }

    /** The drawing's layers and source in the style of one map. Every call may throw where the style refuses. */
    interface Port {
        /** Whether the style has the source and every layer of [LAYERS]. */
        fun exist(): Boolean

        /**
         * Adds the source, with nothing in it, and [LAYERS] where [positions] put them, in [colours], with widths and
         * minimum zooms for [latitude]; whatever of them is there already is taken out first.
         */
        fun add(positions: List<Position>, colours: LaneColours, latitude: Double)

        /** Every layer's colours from now on. */
        fun setColours(colours: LaneColours)

        /** Every layer's widths and minimum zoom for [latitude]. */
        fun setLatitude(latitude: Double)

        /** Gives the source the [features] of one window, as its data [dataId]: the map says when that data has loaded. */
        fun setWindow(features: FeatureCollection, dataId: String)

        /** Whether the map is to say when the source's data has loaded; asked only while a window waits. */
        fun watchLoads(watch: Boolean)

        /** Gives the source nothing to draw, and forgets its feature states ([resetStates]). */
        fun clear()

        /**
         * Forgets every feature state of the source. The map keeps them by feature id across new data, and a route's ids
         * are its own: a new route's pieces may have the ids of the old route's, and must not take their states.
         */
        fun resetStates()

        /**
         * Feature states: each of [behind], a tint's piece the car has passed, is no longer drawn; each of [levels] is a lit
         * piece with its traffic level ([Key.STATE_LEVEL]; null for none, the route's amber).
         */
        fun setStates(behind: List<String>, levels: List<Pair<String, String?>>)

        /** Shows or hides the layers of what is lit for the route the map holds: the tints, the lit lane and its edges, the eraser. */
        fun setLitVisible(visible: Boolean)

        /** Shows or hides every other layer of the drawing: the asphalt, the paint and its shapes, the lane arrows and their edge. */
        fun setVisible(visible: Boolean)

        /** The eraser's colours along the path, a step from each of [stops] on: the road's where a lane is lit, else none. */
        fun setEraser(stops: List<OwnRouteLine.ColourStop>)

        /** Trims the eraser from [fraction] of the path's projected length to its end: it stays over the part behind the car. */
        fun setEraserTrim(fraction: Double)

        /** Takes the layers, the own slots and the source out of the style, whichever of them is there. */
        fun remove()
    }

    /** The layers that show only what is lit for a route, hidden while a new route's drawing loads. */
    val LIT_LAYERS: List<String> = listOf(TINT, LIT, LIT_EDGES, ERASER)

    /** The drawing's layers and source on [mapboxMap]; [onLoaded] hears each data id of the source that loaded. */
    fun on(mapboxMap: MapboxMap, onLoaded: (dataId: String) -> Unit): Port = object : Port {
        /** The source in the style now; its data is set through it. */
        private var source: GeoJsonSource? = null

        /** The map's word on loaded data, while a window waits. */
        private var loads: Cancelable? = null

        override fun exist(): Boolean =
            source != null && mapboxMap.styleSourceExists(SOURCE) && LAYERS.all { mapboxMap.styleLayerExists(it.id) }

        override fun add(positions: List<Position>, colours: LaneColours, latitude: Double) {
            // One of them may be left from an addition that failed half way.
            remove()
            val created = geoJsonSource(SOURCE) {}
            mapboxMap.addSource(created)
            source = created
            for (position in positions) {
                val layer = if (position.marker) {
                    slotLayer(position.id) { slot(position.slot) }
                } else {
                    layerOf(LAYERS.first { it.id == position.id }, position.slot, colours, latitude)
                }
                when {
                    position.below != null -> mapboxMap.addLayerBelow(layer, position.below)
                    position.above != null -> mapboxMap.addLayerAbove(layer, position.above)
                    else -> mapboxMap.addLayer(layer)
                }
            }
        }

        override fun setColours(colours: LaneColours) {
            for (spec in LAYERS) set(spec.id, if (spec.fill) "fill-color" else "line-color", colourOf(spec, colours))
        }

        override fun setLatitude(latitude: Double) {
            for (spec in LAYERS) {
                set(spec.id, "minzoom", Value.valueOf(LaneDrawing.zoomAt(spec.minZoom, latitude)))
                if (!spec.fill) set(spec.id, "line-width", widthOf(spec, latitude))
            }
        }

        override fun setWindow(features: FeatureCollection, dataId: String) {
            val source = checkNotNull(source) { "No source" }
            // The source hands its data to the map on a thread of its own, the one every GeoJSON source shares.
            source.featureCollection(features, dataId)
        }

        override fun watchLoads(watch: Boolean) {
            if (!watch) {
                loads?.cancel()
                loads = null
            } else if (loads == null) {
                loads = mapboxMap.subscribeSourceDataLoaded { loaded ->
                    if (loaded.sourceId == SOURCE && loaded.type == SourceDataLoadedType.METADATA) loaded.dataId?.let(onLoaded)
                }
            }
        }

        override fun clear() {
            source?.featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>()))
            resetStates()
        }

        override fun resetStates() {
            // The answer is not waited for: the states set after it, for the next route's window, come after it.
            if (source != null) mapboxMap.resetFeatureStates(SOURCE) {}
        }

        override fun setStates(behind: List<String>, levels: List<Pair<String, String?>>) {
            // A state that does not take costs the piece its look, nothing more: the answers are not waited for.
            for (id in behind) {
                mapboxMap.setFeatureState(SOURCE, id, Value.valueOf(hashMapOf(Key.STATE_BEHIND to Value.valueOf(true)))) {}
            }
            for ((id, level) in levels) {
                mapboxMap.setFeatureState(SOURCE, id, Value.valueOf(hashMapOf(Key.STATE_LEVEL to Value.valueOf(level ?: "")))) {}
            }
        }

        override fun setLitVisible(visible: Boolean) {
            val visibility = Value.valueOf(if (visible) "visible" else "none")
            for (id in LIT_LAYERS) set(id, "visibility", visibility)
        }

        override fun setVisible(visible: Boolean) {
            val visibility = Value.valueOf(if (visible) "visible" else "none")
            for (spec in LAYERS) if (spec.id !in LIT_LAYERS) set(spec.id, "visibility", visibility)
        }

        override fun setEraser(stops: List<OwnRouteLine.ColourStop>) {
            set(ERASER, "line-gradient", OwnRouteLine.stepGradient(stops))
        }

        override fun setEraserTrim(fraction: Double) {
            set(ERASER, "line-trim-offset", Value.valueOf(listOf(Value.valueOf(fraction), Value.valueOf(1.0))))
        }

        override fun remove() {
            loads?.cancel()
            loads = null
            for (spec in LAYERS.asReversed()) if (mapboxMap.styleLayerExists(spec.id)) mapboxMap.removeStyleLayer(spec.id)
            for (slot in listOf(OVER_SLOT, UNDER_SLOT)) if (mapboxMap.styleLayerExists(slot)) mapboxMap.removeStyleLayer(slot)
            if (mapboxMap.styleSourceExists(SOURCE)) mapboxMap.removeStyleSource(SOURCE)
            source = null
        }

        /** One property of one layer; the style's refusal is thrown, its words without the value. */
        private fun set(layerId: String, property: String, value: Value) {
            val error = mapboxMap.setStyleLayerProperty(layerId, property, value).error
            if (error != null) throw IllegalStateException("The style refused $property of $layerId")
        }
    }

    /** [spec] as a layer of [SOURCE] in [slot], in [colours], for [latitude]. */
    private fun layerOf(spec: Spec, slot: String, colours: LaneColours, latitude: Double) =
        if (spec.fill) {
            fillLayer(spec.id, SOURCE) {
                slot(slot)
                filter(filterOf(spec))
                minZoom(LaneDrawing.zoomAt(spec.minZoom, latitude))
                fillColor(colourOf(spec, colours))
                fillEmissiveStrength(spec.emissiveStrength)
                // A kept lane lies on the asphalt it is part of.
                if (spec.id == ASPHALT) fillSortKey(keptOnTop())
                // A tint's piece the car has passed is not drawn: all of it or nothing, never half.
                if (spec.id == TINT) fillOpacity(notBehind())
            }
        } else {
            lineLayer(spec.id, spec.source) {
                slot(slot)
                if (spec.kinds.isNotEmpty()) filter(filterOf(spec))
                minZoom(LaneDrawing.zoomAt(spec.minZoom, latitude))
                // Dashes end where they are cut; paint meets at its corners.
                lineCap(LineCap.BUTT)
                lineJoin(if (spec.id == LIT_EDGES || spec.id == ERASER) LineJoin.ROUND else LineJoin.MITER)
                lineWidth(widthOf(spec, latitude))
                lineColor(colourOf(spec, colours))
                lineEmissiveStrength(spec.emissiveStrength)
                // Never faded behind 3D objects: drawn whole or not at all, as the fills under them are.
                lineDepthOcclusionFactor(0.0)
                // The eraser covers nothing until the route's lit stretches are known.
                if (spec.id == ERASER) lineGradient(OwnRouteLine.stepGradient(listOf(OwnRouteLine.ColourStop(0.0, OwnRouteLine.TRANSPARENT))))
            }
        }

    /** The features [spec] draws: its kinds, and for a line of paint its width. */
    private fun filterOf(spec: Spec): Expression {
        val kinds = Expression.inExpression {
            get(Key.KIND)
            literal(spec.kinds.map { it.logName })
        }
        val width = spec.width
        return if (width == null || spec.kinds.none { it in PAINT_KINDS }) {
            kinds
        } else {
            Expression.all(kinds, Expression.eq(Expression.get(Key.WIDTH), Expression.literal(widthName(width))))
        }
    }

    /** The line width of [spec]: its fixed width on the screen, or its ground width for [latitude]. */
    private fun widthOf(spec: Spec, latitude: Double): Expression {
        spec.fixedDp?.let { return Expression.literal(it) }
        val meters = spec.meters ?: requireNotNull(spec.width) { "A line needs a width" }.meters
        return Expression.interpolate {
            exponential { literal(2.0) }
            zoom()
            for ((zoom, dp) in LaneDrawing.widthStops(meters, latitude)) stop(zoom, dp)
        }
    }

    /** 0 for a piece whose feature state says the car is past it, else 1. */
    private fun notBehind(): Expression = Expression.switchCase {
        boolean {
            featureState { literal(Key.STATE_BEHIND) }
            literal(false)
        }
        literal(0.0)
        literal(1.0)
    }

    /** A kept lane over the asphalt it lies on. */
    private fun keptOnTop(): Expression =
        Expression.match(Expression.get(Key.KIND), Expression.literal(LaneDrawing.Kind.KEPT_LANE.logName), Expression.literal(1.0), Expression.literal(0.0))

    /** The colour of [spec]'s features in [colours]. */
    private fun colourOf(spec: Spec, colours: LaneColours): Expression = when (spec.id) {
        ASPHALT -> Expression.match(
            Expression.get(Key.KIND),
            Expression.literal(LaneDrawing.Kind.KEPT_LANE.logName),
            Expression.color(colours.keptLane),
            Expression.color(colours.asphalt),
        )
        TINT -> Expression.color(colours.tint)
        // A lit piece in the colour of its stretch's traffic, by its feature state; the route's amber without one.
        LIT -> Expression.match {
            toString { featureState { literal(Key.STATE_LEVEL) } }
            literal(Key.LEVEL_MODERATE)
            color(colours.moderate)
            literal(Key.LEVEL_HEAVY)
            color(colours.heavy)
            literal(Key.LEVEL_SEVERE)
            color(colours.severe)
            literal(Key.LEVEL_CLOSED)
            color(colours.closed)
            color(colours.lit)
        }
        LIT_EDGES -> Expression.color(colours.litEdge)
        PAINT, PAINT_WIDE -> Expression.match(
            Expression.get(Key.PAINT),
            Expression.literal(Key.PAINT_YELLOW),
            Expression.color(colours.yellow),
            Expression.color(colours.white),
        )
        SHAPES, ARROWS -> Expression.color(colours.white)
        // Its gradient draws it; the colour is the road's all the same.
        ERASER -> Expression.color(colours.asphalt)
        ARROW_EDGES -> Expression.color(colours.arrowEdge)
        else -> throw IllegalArgumentException("No colour for ${spec.id}")
    }
}
