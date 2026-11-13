package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The app's own lanes on freeways and ramps, as features in metres (the design's 2.2 to 2.5, 3.2, 3.3 and 4.2 to 4.3):
 * what the map draws under the route where the route's own lane data says how many lanes there are. Every element is
 * the route's data, a rule with a named constant, or not drawn; nothing is drawn as a lane that the data does not give.
 * Every lateral position comes from the one [CrossSection], laid along the [RoutePath]'s own axes, so the lane lines,
 * the asphalt and the route's path agree by construction.
 *
 * - **Asphalt** ([Kind.ASPHALT]): one opaque fill per drawn stretch, its lanes ([LaneTopology.FREEWAY_LANE_METERS] each)
 *   and [CrossSection.APRON_METERS] of apron outside each edge line. A stretch begins with a taper over
 *   [RoutePath.ASPHALT_TAPER_METERS] from the map's own road band ([STANDARD_FREEWAY_BAND_METERS], on ramps
 *   [STANDARD_RAMP_BAND_METERS]) and ends with one: where the drawing ends on its road, after the path's return to the
 *   road's line over asphalt without lines. A zone between two drawn stretches is part of the stretch: no taper there.
 * - **Lane lines** ([Kind.LANE_LINE]), only where the route's lane points give the count (a known or bridged stretch),
 *   never in a zone and never beside a lane that is only possible: broken [LINE_METERS] wide, [BROKEN_DASH_METERS] on
 *   [BROKEN_GAP_METERS], cut as geometry; beside a lane kept for car pools one wide solid line ([WIDE_LINE_METERS]),
 *   never a double one; beside a lane kept for other vehicles a solid line; beside a lane only for an exit on a
 *   freeway (its arrows all point to one side, at that edge) dotted, [DOTTED_DASH_METERS] on [DOTTED_GAP_METERS], wide
 *   where a lane that went on through before drops at the exit, else normal (an added lane). A line is drawn only where
 *   both its lanes are at least [LINE_LANE_SHARE] of their width, so it begins and ends half way along a taper.
 * - **Edge lines** ([Kind.EDGE_LINE]): solid, left yellow and right white; not in a zone where nothing explains the
 *   lanes (a change of the count, a gap), kept on an approach where no lane is named (C1), broken for
 *   [JUNCTION_GAP_METERS] on each side of an intersection where a road meets that side, and wherever the edge
 *   steps sideways (a lane leaving at the edge) rather than run across it.
 * - **Designated lanes** by kind: a car-pool lane (its kinds include car pools) with its wide line and a diamond
 *   outline [DIAMOND_WIDTH_METERS] by [DIAMOND_LENGTH_METERS] every [DIAMOND_EVERY_METERS] ([Kind.DIAMOND]); a lane
 *   for buses, taxis, motorcycles, mopeds or other vehicles drawn darker ([Kind.KEPT_LANE]) with a solid line beside
 *   it and no symbol; a lane only for bicycles at its own width with a solid line beside it.
 * - **A possible lane** (the evidence's rule 2a): the cross-section draws it from its possible start; here it is
 *   asphalt with the edge line beyond it, no line beside its neighbour, no arrow.
 * - **Lane arrows** ([Kind.ARROW]): each lane point's arrows ([RouteLane.indications]) as white polygons in its lanes,
 *   [ARROW_NEAR_METERS] and [ARROW_FAR_METERS] before the point (within [ARROW_FAR_MIN_METERS] to
 *   [ARROW_FAR_MAX_METERS] where that one does not fit); at a maneuver only the far row, at [ARROW_FAR_MIN_METERS],
 *   because the maneuver arrow begins just before it. Only where the point's own lanes hold, at full width, never on a
 *   possible lane, a designated lane or in a zone.
 * - **A gore** where the route leaves its road ([Gore]): the lanes the route does not take go on straight along the old
 *   road's way for [STUB_METERS] with their lines ([Part.STUB]), and past it their asphalt tapers into the map's band; between
 *   them and the route's lanes two wide white channelizing lines ([Kind.CHANNEL_LINE]) run from the split to the nose,
 *   where the two carriageways' aprons part ([NOSE_GAP_METERS]); no chevrons. Where the route's data has no lanes past
 *   the turn, the lanes it takes go on beside the path, without lane lines between them, to where the path is back on
 *   the new road's line, and from there taper into the map's band ([Part.BEYOND]).
 * - **A gore the path reaches on the road's line** (no lane named on the approach, C1): the route geometry's diagonal runs
 *   across the old road's lanes there, as they go on straight, so from where the turn's arc begins, and past a ramp's start
 *   to where the path leaves the line for the ramp's lanes ([RoutePath.Diagonal]), the carriageway is bare asphalt, as is
 *   the stub and the lanes beyond: no line, symbol, lit lane or tint, and the maneuver arrow is the navigation SDK's.
 * - **The lit lane** ([Kind.LIT], 2.2 and 4.3), inside card windows only, wherever the plan names a lane: that lane's full
 *   width about the route's path, square to it, so it moves from lane to lane with the path's S and follows a lane
 *   entered along its taper; its two edges ([Kind.LIT_EDGE], [LIT_EDGE_METERS]). Never a designated lane, never on an
 *   approach where no lane is named. Only where it is that lane: where the path lies within half a lane of the lane's
 *   drawn middle (or in its feeder along its taper) or moves, and the lit lane lies on the drawn lanes ([LIT_FIT_METERS]);
 *   elsewhere, as where the path waits on the road's line beside a lane still opening or keeps to it around a fold, the
 *   route line shows instead.
 * - **The tints** ([Kind.TINT]): in the same windows, where a lane is lit, each other lane of the plan's band as it is
 *   drawn, where the data gives the lanes (not in a zone), never a designated lane or one only possible. Where its lane
 *   stops working (it leaves the band while it is still there, or in a zone that begins where the tint ends) its far edge
 *   from the lit lane slides onto its near edge over [WEDGE_METERS], or the whole tint where it is shorter: a wedge
 *   pointing at the lit lane.
 * - Both are cut into pieces at multiples of [PIECE_METERS] from the route's start, with ids that stay the same in every
 *   window ([litId], [tintId]): the map hides a tint behind the car and gives a lit piece its traffic by feature state.
 * - **A curve too sharp for the lanes** (3.3): where a line or an edge of the asphalt, a lane, a tint or the lit lane lies
 *   farther out than an arc's radius on its inner side (1 - kappa t <= 0, [RoadAxis.folds]), it turns where it would cross
 *   itself and nothing of it is drawn where it folds ([Loops]): no line or ring of the drawing crosses itself.
 *
 * Widths are ground widths: the map draws each as a zoom-only base-2 expression ([widthStops]), the asphalt and lines
 * from [ASPHALT_MIN_ZOOM], arrows and diamonds from [SYMBOL_MIN_ZOOM], the lit lane, its edges and the tints from
 * [LIT_MIN_ZOOM], at latitude 0 ([zoomAt] elsewhere), with no opacity ramp. Colours are the map's (the lit lane amber or
 * its traffic colour, its edges the route's casing, the tints amber see-through): this class gives kinds and paints only.
 *
 * Built once for a path (off the main thread); [features] gives the features of a stretch of the route, the same
 * features for the same route every time. Pure; never stored.
 */
class LaneDrawing private constructor(
    /** The navigator's id of the route. */
    val routeId: String,
    /** The version of the path the lanes are drawn along ([RoutePath.version]). */
    val version: Int,
    private val builder: Builder,
) {

    /** What a feature is, and which of the map's layers draws it. */
    enum class Kind(
        /** A filled polygon; otherwise a line drawn at its ground width. */
        val polygon: Boolean,
        /** How the debug log names it. */
        val logName: String,
    ) {
        /** Our asphalt: one opaque fill per drawn stretch, with its aprons. */
        ASPHALT(true, "asphalt"),

        /** A lane kept for buses, taxis, motorcycles, mopeds or other vehicles (not car pools, not bicycles): drawn darker. */
        KEPT_LANE(true, "kept_lane"),

        /**
         * A lane that still works beside the lit one, inside a card window: the map's amber, see-through; where its lane
         * stops working it ends in a wedge pointing at the lit lane. In pieces ([PIECE_METERS]) with ids.
         */
        TINT(true, "tint"),

        /** The lane to drive in, inside a card window: the named lane's width about the route's path. In pieces with ids. */
        LIT(true, "lit"),

        /** The lit lane's two edges ([LIT_EDGE_METERS]), in the route's casing colour. */
        LIT_EDGE(false, "lit_edge"),

        /** A line between two lanes. */
        LANE_LINE(false, "lane_line"),

        /** A carriageway's edge line. */
        EDGE_LINE(false, "edge_line"),

        /** A gore's channelizing line, from the split to the nose. */
        CHANNEL_LINE(false, "channel_line"),

        /** The outline of a car-pool lane's diamond, closed. */
        DIAMOND(false, "diamond"),

        /** A lane arrow. */
        ARROW(true, "arrow"),
    }

    /** The paint of a line or a symbol. */
    enum class Paint { WHITE, YELLOW }

    /** How wide a line is on the ground. */
    enum class Width(val meters: Double) {
        NORMAL(LINE_METERS),
        WIDE(WIDE_LINE_METERS),
        EDGE(LIT_EDGE_METERS),
    }

    /** How a line is cut: solid, or into dashes of [dashMeters] with [gapMeters] between them. */
    enum class Pattern(val dashMeters: Double, val gapMeters: Double) {
        SOLID(0.0, 0.0),
        BROKEN(BROKEN_DASH_METERS, BROKEN_GAP_METERS),
        DOTTED(DOTTED_DASH_METERS, DOTTED_GAP_METERS),
        ;

        /** A dash and its gap. */
        val periodMeters: Double get() = dashMeters + gapMeters
    }

    /** Which road a feature lies on. */
    enum class Part {
        /** The route's own carriageway, where its lane data holds. */
        ROUTE,

        /** The road the route leaves at a gore: its lanes go on for [STUB_METERS]. */
        STUB,

        /** Past a turn where the route's data has no lanes: the lanes it takes, beside the path. */
        BEYOND,
    }

    /**
     * One feature: a [kind] from route station [from] to [to] (on a stub, the gore's station and the metres along it), its
     * [parts] (for a polygon its rings, one outer ring each, counterclockwise, closed; for a line its lines, a broken or
     * dotted line's dashes two points each, a diamond's outline closed), its [paint], [width] and [pattern]. [lane] is the
     * lane a polygon or symbol belongs to (an id of the plan's [LaneTopology]), [between] the lanes left and right of a
     * lane line, [side] the side of an edge or channelizing line, [indications] an arrow's arrows, and [id] a number that
     * stays the same across windows for the pieces the map changes by feature state.
     */
    data class Feature(
        val kind: Kind,
        val from: Double,
        val to: Double,
        val parts: List<List<GeoPoint>>,
        val paint: Paint? = null,
        val width: Width? = null,
        val pattern: Pattern? = null,
        val part: Part = Part.ROUTE,
        val lane: Int? = null,
        val between: Pair<Int, Int>? = null,
        val side: LaneEdge? = null,
        val indications: List<TurnModifier> = emptyList(),
        val id: Long? = null,
    ) {
        /** How many coordinates its geometry has. */
        val coordinates: Int get() = parts.sumOf { it.size }
    }

    /**
     * The features that lie between route stations [from] and [to], cut there: asphalt first, next the lanes drawn darker,
     * the lines, the diamonds and the arrows, each in route order. A symbol (an arrow, a diamond) is in where it begins.
     */
    fun features(from: Double = Double.NEGATIVE_INFINITY, to: Double = Double.POSITIVE_INFINITY): List<Feature> = builder.features(from, to)

    /** The route stations the drawing covers: every drawn stretch from where its asphalt begins to where it ends, in route order. */
    val spans: List<ClosedFloatingPointRange<Double>> get() = builder.spans

    /**
     * The route stations where a lane is lit, each stretch from where the lit lane begins to where it ends, in route
     * order: where the map's route line of the lit stretches lies over it. Empty where the plan names no lane in a card
     * window.
     */
    fun litStretches(): List<ClosedFloatingPointRange<Double>> = builder.litStretches()

    /**
     * The route stations of the drawing's gores, in route order: where the route leaves its road and lanes of that road go
     * on beside it, drawn with a gore, and the path reaches it in a lane. The map lays the arrow of a maneuver there along the
     * path ([GoreArrow]). Not a gore the path reaches on the road's line, where no lane is named before it: the navigation
     * SDK's own arrow follows that same line.
     */
    fun gores(): List<Double> = builder.goreStations()

    /** The lanes drawn on the route's carriageway at [station], from the left: none outside the drawn stretches. */
    internal fun lanesAt(station: Double): List<CrossSection.Place> = builder.lanesAt(station)

    /** The asphalt's left and right edges at [station] on the route's carriageway, as offsets from the axis; null where none is drawn. */
    internal fun asphaltAt(station: Double): Pair<Double, Double>? = builder.asphaltAt(station)

    /** Where each gore's two channelizing lines end, along the stub and along the route: for the tests. */
    internal val noses: List<Pair<Double, Double>> get() = builder.noses()

    companion object {

        /** A normal line (MUTCD: four to six inches). */
        const val LINE_METERS = 0.15

        /** A wide line: a car-pool lane's line, a channelizing line, a lane drop's dotted line. */
        const val WIDE_LINE_METERS = 0.30

        /** A broken lane line's dash... */
        const val BROKEN_DASH_METERS = 3.05

        /** ...and its gap (ten and thirty feet). */
        const val BROKEN_GAP_METERS = 9.15

        /** A dotted line's dash... */
        const val DOTTED_DASH_METERS = 0.91

        /** ...and its gap (three and nine feet). */
        const val DOTTED_GAP_METERS = 2.74

        /** A car-pool diamond's width... */
        const val DIAMOND_WIDTH_METERS = 0.91

        /** ...its length... */
        const val DIAMOND_LENGTH_METERS = 4.0

        /** ...and how far apart diamonds are (about a quarter mile), from the route's start. */
        const val DIAMOND_EVERY_METERS = 402.0

        /** A freeway lane arrow's length (23.5 ft)... */
        const val ARROW_LENGTH_METERS = 7.16

        /** ...its head's length (seven feet)... */
        const val ARROW_HEAD_LENGTH_METERS = 2.13

        /** ...its head's width... */
        const val ARROW_HEAD_WIDTH_METERS = 1.05

        /** ...and its stem's width (eight inches). */
        const val ARROW_STEM_METERS = 0.20

        /** The near row of a lane point's arrows begins this far before the point. */
        const val ARROW_NEAR_METERS = 10.0

        /** The far row begins this far before it... */
        const val ARROW_FAR_METERS = 200.0

        /** ...or as near as this where that does not fit, and at a maneuver always here... */
        const val ARROW_FAR_MIN_METERS = 170.0

        /** ...and no farther than this. */
        const val ARROW_FAR_MAX_METERS = 230.0

        /** The road the route leaves at a gore is drawn on for this far with its lanes; past it, it tapers into the map's band. */
        const val STUB_METERS = 100.0

        /** How wide the map's own band of a freeway is where our asphalt tapers into it. */
        const val STANDARD_FREEWAY_BAND_METERS = 9.0

        /** The same for a ramp. */
        const val STANDARD_RAMP_BAND_METERS = 5.5

        /** An edge line is broken this far before and after an intersection where a road meets its side. */
        const val JUNCTION_GAP_METERS = 15.0

        /**
         * A line that would move more than this sideways between two samples, and more sideways than along (a lane leaving at
         * an edge), is broken there instead of drawn across the lane.
         */
        const val STEP_METERS = 1.0

        /** A gore's channelizing lines end where they lie this far apart: where the two carriageways' aprons part. */
        const val NOSE_GAP_METERS = 2.0 * CrossSection.APRON_METERS

        /** A line between two lanes is drawn only where both are at least this share of their width. */
        const val LINE_LANE_SHARE = 0.5

        /** The asphalt and the lines show from this zoom at latitude 0 (a lane 2.2 dp wide). */
        const val ASPHALT_MIN_ZOOM = 15.5

        /** The arrows and diamonds from this zoom at latitude 0. */
        const val SYMBOL_MIN_ZOOM = 16.5

        /** The lit lane, its edges and the tints from this zoom at latitude 0 (under the opaque route line until the swap). */
        const val LIT_MIN_ZOOM = 16.85

        /** The lit lane's edges, centred on its sides. */
        const val LIT_EDGE_METERS = 0.22

        /**
         * The lit lane is drawn only where it reaches no farther than this past the drawn carriageway's outer edges, where its own
         * edge line, centred on its side, still lies on the carriageway's edge line.
         */
        const val LIT_FIT_METERS = LIT_EDGE_METERS / 2.0

        /**
         * The lit lane and the tints are cut into pieces this long, at its multiples from the route's start: the map changes
         * a piece by feature state (a tint behind the car hidden, a lit piece's traffic) under an id that stays the same
         * across windows ([litId], [tintId]).
         */
        const val PIECE_METERS = 50.0

        /** A tint whose lane stops working ends in a wedge this long, or as long as the tint where that is shorter. */
        const val WEDGE_METERS = 110.0

        /** A line is never drawn thinner than this on the screen, in dp. */
        const val FLOOR_DP = 1.0

        /** The widths' stops are made again when the latitude has moved this far, in degrees. */
        const val LATITUDE_STEP_DEGREES = 0.5

        /** The last stop of a width's expression, the map's deepest zoom. */
        const val MAX_ZOOM = 24.0

        /** The lit lane's pieces' ids begin here... */
        private const val LIT_IDS = 1_000_000_000_000L

        /** ...the tints' here, a block for each lane... */
        private const val TINT_IDS = 2_000_000_000_000L

        /** ...of this many pieces. */
        private const val LANE_IDS = 10_000_000L

        /** The piece ([PIECE_METERS]) that begins at or holds route station [station]. */
        fun pieceOf(station: Double): Long = floor(station / PIECE_METERS + PIECE_EPSILON).toLong()

        /** The id of the lit lane's piece [piece]. */
        fun litId(piece: Long): Long = LIT_IDS + piece

        /** The id of the piece [piece] of the tint on [lane] (an id of the plan's [LaneTopology]). */
        fun tintId(lane: Int, piece: Long): Long = TINT_IDS + lane * LANE_IDS + piece

        /** A station this close below a piece's start, as a share of a piece, lies on it. */
        private const val PIECE_EPSILON = 1e-9

        /** The drawing of [path], whose lanes are [plan]'s with the plan's second pass of the evidence [evidence] laid out by [section]. */
        fun of(
            plan: LanePlan,
            path: RoutePath,
            evidence: LaneEvidence = plan.evidence(),
            section: CrossSection = CrossSection.of(plan, evidence),
        ): LaneDrawing = LaneDrawing(path.routeId, path.version, Builder(plan, path, evidence, section))

        /**
         * The zoom at [latitude] that matches [zoomAtEquator] at latitude 0: the same ground width is the same width on the
         * screen there (2.3: at 35 degrees about 0.29 lower).
         */
        fun zoomAt(zoomAtEquator: Double, latitude: Double): Double = zoomAtEquator + log2(cos(Math.toRadians(latitude)))

        /**
         * The stops of a zoom-only exponential expression, base 2, that draws a line of [groundMeters] at its ground width at
         * [latitude] and every zoom, and never thinner than [floorDp]: the zoom where it is [floorDp] wide (0 at the least),
         * and [MAX_ZOOM]. Exact at every zoom between them, since the ground width doubles with each zoom.
         */
        fun widthStops(groundMeters: Double, latitude: Double, floorDp: Double = FLOOR_DP): List<Pair<Double, Double>> {
            require(groundMeters > 0.0) { "A width needs metres" }
            val floorZoom = max(0.0, log2(floorDp * MapScale.metersPerDp(0.0, latitude) / groundMeters))
            val first = max(floorDp, groundMeters / MapScale.metersPerDp(floorZoom, latitude))
            return listOf(floorZoom to first, MAX_ZOOM to groundMeters / MapScale.metersPerDp(MAX_ZOOM, latitude))
        }

        /** Whether stops made at [builtAt] should be made again at [latitude]: it has moved [LATITUDE_STEP_DEGREES] or more. */
        fun stopsStale(builtAt: Double, latitude: Double): Boolean = abs(latitude - builtAt) >= LATITUDE_STEP_DEGREES

        /** How long the wedge is that ends a tint [tintMeters] long whose lane stops working: [WEDGE_METERS], or the whole tint. */
        internal fun wedgeMeters(tintMeters: Double): Double = min(WEDGE_METERS, tintMeters)

        /**
         * Whether a line that moves [sideways] metres over [along] metres of the route does so as a step, not along a taper
         * or a bend: more than [STEP_METERS], and more across the road than along it. A line is broken there.
         */
        internal fun steps(sideways: Double, along: Double): Boolean = abs(sideways) > STEP_METERS && abs(sideways) > abs(along)

        /**
         * How far before a lane point its far row of arrows is tried, in order: [ARROW_FAR_METERS], and ten metres nearer
         * and farther in turn within [ARROW_FAR_MIN_METERS] to [ARROW_FAR_MAX_METERS]; at a maneuver only
         * [ARROW_FAR_MIN_METERS].
         */
        internal fun farRows(maneuver: Boolean): List<Double> {
            if (maneuver) return listOf(ARROW_FAR_MIN_METERS)
            val out = ArrayList<Double>()
            out.add(ARROW_FAR_METERS)
            var step = ARROW_ROW_STEP_METERS
            while (ARROW_FAR_METERS - step >= ARROW_FAR_MIN_METERS || ARROW_FAR_METERS + step <= ARROW_FAR_MAX_METERS) {
                if (ARROW_FAR_METERS - step >= ARROW_FAR_MIN_METERS) out.add(ARROW_FAR_METERS - step)
                if (ARROW_FAR_METERS + step <= ARROW_FAR_MAX_METERS) out.add(ARROW_FAR_METERS + step)
                step += ARROW_ROW_STEP_METERS
            }
            return out
        }

        /** The far row is tried in steps this long: three each way from [ARROW_FAR_METERS] to the ends of its range. */
        private const val ARROW_ROW_STEP_METERS = (ARROW_FAR_MAX_METERS - ARROW_FAR_METERS) / 3.0

        /** Arrows that point to the right, and to the left. */
        private val RIGHT_ARROWS = setOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)
        private val LEFT_ARROWS = setOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT)

        /** Two stations closer than this are one. */
        private const val SAME_STATION = 1e-6

        /** A lane narrower than this is not drawn. */
        private const val NO_WIDTH = 1e-6

        /** The stub's and the ramp's taper are drawn every this many metres. */
        private const val TAPER_STEP_METERS = 1.0

        /** The path's way at a station is read from its offsets this far before and after it. */
        private const val SLOPE_METERS = 0.25
    }

    /** How one line is drawn. */
    private data class Style(val kind: Kind, val paint: Paint, val width: Width, val pattern: Pattern)

    /** A run of samples of one line: route stations and points. */
    private class Run(val style: Style, val key: Any) {
        val stations = ArrayList<Double>()
        val points = ArrayList<GeoPoint>()
        var last = Double.NaN
    }

    /** A run of samples of one polygon: route stations and its left and right edges. */
    private class Strip {
        val stations = ArrayList<Double>()
        val left = ArrayList<GeoPoint>()
        val right = ArrayList<GeoPoint>()
    }

    /**
     * Where the route leaves its road at the intersection [node], at [station]: the old road's lanes go on straight from
     * [begin], where the turn's arc begins along [axis], the route's own to the [right] or left. [stub]: the lanes that go
     * on along the old road (the route takes none of them), as they lie at [begin]. [beyond]: where the drawing ends here
     * and the route's data has no lanes past the turn, the lanes it takes, [beyondLeft] to [beyondRight] metres from the
     * path where the turn's arc begins (the path lies [pathAtBegin] from the axis there), to [back], where the path is on
     * the new road's line. [onLine]: the path reaches the gore on the road's line, no lane named before it (C1); past a
     * ramp's start it follows the line's diagonal across the gore ([RoutePath.Diagonal]).
     */
    private class Gore(
        val node: Int,
        val station: Double,
        val begin: Double,
        val axis: RoadAxis,
        val right: Boolean,
        val atBegin: List<CrossSection.Place>,
        val stub: List<CrossSection.Place>,
        val stubRoad: RoadKind,
        val beyond: Boolean,
        val beyondLeft: Double,
        val beyondRight: Double,
        val beyondLanes: List<Int>,
        val pathAtBegin: Double,
        val back: Double,
        val rampRoad: RoadKind,
        val onLine: Boolean,
    ) {
        private val place = axis.at(begin)

        /**
         * Where the lanes beyond the turn are one lane: where the path is on the new road's line ([back]), and no nearer the
         * split than the asphalt's taper, so where the path reaches the turn on that line already the lanes do not narrow in a
         * step.
         */
        val narrowed: Double get() = max(back, station + RoutePath.ASPHALT_TAPER_METERS)

        /** The point [u] metres straight on from the axis at [begin] and [t] to the left of it. */
        fun straight(u: Double, t: Double): LocalPoint =
            LocalPoint(place.point.x + place.tx * u + place.nx * t, place.point.y + place.ty * u + place.ny * t)

        /** Where the gore's channelizing lines end: along the stub (route station of the old road) and along the route. */
        var stubNose = Double.NaN
        var routeNose = Double.NaN

    }

    private class Builder(
        val plan: LanePlan,
        val path: RoutePath,
        val evidence: LaneEvidence,
        val section: CrossSection,
    ) {
        private val topology: LaneTopology = plan.topology
        private val route = topology.evidence.route
        private val nodes = route.nodes
        private val stretches = evidence.stretches
        private val drawings = path.drawings
        private val pathAxes = path.axes
        private val extraAxes = ArrayList<RoadAxis>()
        private val stationCache = HashMap<RoadAxis, DoubleArray>()

        /** Each topology point's index by its intersection. */
        private val pointOf = HashMap<Int, Int>().apply { topology.points.forEachIndexed { k, p -> put(p.node, k) } }

        /**
         * For each lane point, the lanes it lists as only for an exit (all their arrows to one side, at that side's edge), with
         * that side and whether the lane went on through at an earlier point of its run (a lane drop: a wide dotted line).
         */
        private val exitOnly: List<Map<Int, Pair<LaneEdge, Boolean>>> = topology.points.indices.map { exitOnlyAt(it) }

        /** For each side, the stretches where an edge line is broken at an intersection. */
        private val junctionGaps: Map<LaneEdge, List<Pair<Double, Double>>> = junctions()

        /** The drawing's gores: each ramp start inside a drawing, and each drawing's end at a turn. */
        private val gores = ArrayList<Gore>()

        /** For each drawing, its gore at its end where it ends at a turn. */
        private val ends = arrayOfNulls<Gore>(drawings.size)

        /**
         * Where the path follows the road's line past a ramp's start it reached on that line ([RoutePath.Diagonal]): it crosses
         * the lanes there, so no line, symbol or lit lane of ours is drawn, as in the zone before the gore.
         */
        private val diagonals = path.diagonals

        /**
         * Where the route's carriageway is drawn bare, asphalt only: the diagonals, and at each gore the path reaches on the
         * road's line the turn's arc, where the lanes go on straight while the line turns off; half open, in route order.
         */
        private val bare = ArrayList<Pair<Double, Double>>()

        /**
         * Where the plan names a lane in a card window but the lit lane, that lane's width about the path, would not be that lane:
         * the path lies outside it and no move takes it there, or the lit lane would leave the lanes drawn there ([lightable]).
         * Nothing is lit or tinted there ([litLane]).
         */
        private val dark: List<Pair<Double, Double>>

        /** Stations where something drawn changes: the samples take each of them and a station just before it. */
        private val events: DoubleArray

        /** The card windows, merged where they meet or overlap, in route order: nothing is lit or tinted outside them. */
        private val windowSpans: List<Pair<Double, Double>> = run {
            val out = ArrayList<Pair<Double, Double>>()
            for (w in plan.windows.sortedBy { it.from }) {
                val last = out.lastOrNull()
                if (last != null && w.from <= last.second) out[out.size - 1] = last.first to max(last.second, w.to) else out.add(w.from to w.to)
            }
            out
        }

        val spans: List<ClosedFloatingPointRange<Double>>

        init {
            for ((i, d) in drawings.withIndex()) {
                val asphaltFrom = max(0.0, d.asphaltFrom)
                val routeEnd = path.routeStations.last()
                lineAxis(asphaltFrom - 1.0, d.from + 1.0)?.let { extraAxes.add(it) }
                lineAxis(d.back - 1.0, min(routeEnd, d.asphaltTo) + 1.0)?.let { extraAxes.add(it) }
                for (start in section.rampStarts) {
                    if (start.station > d.from && start.station < d.to) goreAt(d, start.node, start.station, false)?.let { gores.add(it) }
                }
                if (d.turnsAtEnd) {
                    val node = nodes.indexOfFirst { abs(it.station - d.to) < SAME_STATION && LaneEvidence.planTurns(it) }
                    if (node >= 0) goreAt(d, node, d.to, true)?.let {
                        gores.add(it)
                        ends[i] = it
                    }
                }
            }
            gores.sortBy { it.station }
            for (gore in gores) noseOf(gore)
            for (diagonal in diagonals) bare.add(diagonal.from to diagonal.to)
            for (gore in gores) if (gore.onLine && gore.begin < gore.station) bare.add(gore.begin to gore.station + SAME_STATION)
            bare.sortBy { it.first }
            val e = ArrayList<Double>()
            for (s in stretches) {
                e.add(s.from)
                e.add(s.to)
            }
            for (z in evidence.zones) {
                e.add(z.from)
                e.add(z.to)
            }
            for (lane in topology.lanes) {
                e.add(lane.opens)
                e.add(lane.closes)
                lane.possibleFrom?.let { e.add(it) }
            }
            for (d in drawings) {
                e.add(d.from)
                e.add(d.to)
                e.add(d.back)
            }
            for ((_, gaps) in junctionGaps) for ((a, b) in gaps) {
                e.add(a)
                e.add(b)
            }
            for (g in gores) {
                e.add(g.begin)
                e.add(g.station)
                if (g.routeNose.isFinite()) e.add(g.routeNose)
            }
            for (seg in plan.segments) {
                e.add(seg.from)
                e.add(seg.to)
            }
            for ((a, b) in bare) {
                e.add(a)
                e.add(b)
            }
            val base = e.filter { it.isFinite() }.sorted().distinct().toDoubleArray()
            // Where the named lane cannot be lit about the path, found on the samples of the rest: its ends are events too, so a
            // tint begins or ends with the lit lane.
            dark = darkSpans(base)
            for ((a, b) in dark) {
                e.add(a)
                e.add(b)
            }
            events = e.filter { it.isFinite() }.sorted().distinct().toDoubleArray()
            spans = drawings.indices.map { i ->
                val d = drawings[i]
                val gore = ends[i]
                val stubEnd = gore?.let { if (it.stub.isNotEmpty()) it.station + STUB_METERS + RoutePath.ASPHALT_TAPER_METERS else 0.0 } ?: 0.0
                max(0.0, d.asphaltFrom)..max(d.asphaltTo, stubEnd)
            }
        }

        // ------------------------------------------------------------------ the features

        fun features(a: Double, b: Double): List<Feature> {
            val out = ArrayList<Feature>()
            for ((i, d) in drawings.withIndex()) {
                val end = ends[i]
                mainAsphalt(d, end, a, b, out)
                mainLines(d, end, a, b, out)
            }
            for (gore in gores) {
                stub(gore, a, b, out)
                if (gore.beyond) beyond(gore, a, b, out)
            }
            diamonds(a, b, out)
            arrows(a, b, out)
            lit(a, b, out)
            tints(a, b, out)
            // Stable: within a kind, the order the features were made in, which is route order.
            return merged(out).sortedBy { it.kind.ordinal }
        }

        /** [features] with the parts of each piece made apart (a lit lane or a tint broken inside one piece) as one feature. */
        private fun merged(features: List<Feature>): List<Feature> {
            val at = HashMap<Long, Int>()
            val out = ArrayList<Feature>(features.size)
            for (f in features) {
                val id = f.id
                val k = id?.let { at[it] }
                if (id == null) {
                    out.add(f)
                } else if (k == null) {
                    at[id] = out.size
                    out.add(f)
                } else {
                    val g = out[k]
                    out[k] = g.copy(from = min(g.from, f.from), to = max(g.to, f.to), parts = g.parts + f.parts)
                }
            }
            return out
        }

        /** The route's carriageway at [station], from the left, as the drawing lays it. */
        fun lanesAt(station: Double): List<CrossSection.Place> {
            val k = drawings.indexOfFirst { station >= it.from && station <= it.to }
            if (k < 0) return emptyList()
            return placesAt(drawings[k], ends[k], station, axisAt(station))
        }

        fun asphaltAt(station: Double): Pair<Double, Double>? {
            val k = drawings.indexOfFirst { station >= max(0.0, it.asphaltFrom) && station <= it.asphaltTo }
            if (k < 0) return null
            val end = ends[k]
            if (end != null && station > end.begin) return null
            return asphaltEdges(drawings[k], end, station)
        }

        fun noses(): List<Pair<Double, Double>> = gores.filter { it.stub.isNotEmpty() }.map { it.stubNose to it.routeNose }

        fun goreStations(): List<Double> = gores.filter { it.stub.isNotEmpty() && !it.onLine }.map { it.station }

        /** Each run of the lit lane, from its edges, which run where it does; runs that meet are one. */
        fun litStretches(): List<ClosedFloatingPointRange<Double>> {
            val out = ArrayList<Feature>()
            lit(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, out)
            val runs = ArrayList<ClosedFloatingPointRange<Double>>()
            for (edge in out.filter { it.kind == Kind.LIT_EDGE }.sortedBy { it.from }) {
                val last = runs.lastOrNull()
                if (last != null && edge.from <= last.endInclusive + SAME_STATION) {
                    runs[runs.size - 1] = last.start..max(last.endInclusive, edge.to)
                } else {
                    runs.add(edge.from..edge.to)
                }
            }
            return runs
        }

        // ------------------------------------------------------------------ the route's carriageway

        /** The lanes drawn at [s] of the drawing [d], whose end is [end]: from where the turn's arc begins, on their straight lines. */
        private fun placesAt(d: RoutePath.Drawing, end: Gore?, s: Double, axis: RoadAxis): List<CrossSection.Place> {
            if (end != null && s >= end.begin) {
                return end.atBegin.map { p ->
                    val left = CrossSection.lineOffset(end.axis, end.begin, p.left, s) ?: p.left
                    val right = CrossSection.lineOffset(end.axis, end.begin, p.right, s) ?: p.right
                    p.copy(left = left, right = right)
                }
            }
            return section.lanes(s, axis).filter { it.width > NO_WIDTH }
        }

        /** The asphalt's edges at [s] of the drawing [d] (its own and its tapers), offsets from the axis; null where no lane is drawn. */
        private fun asphaltEdges(d: RoutePath.Drawing, end: Gore?, s: Double): Pair<Double, Double>? {
            val from = d.from
            return when {
                s < from -> {
                    val e = carriageway(d, end, from) ?: return null
                    val half = bandOf(roadAt(from)) / 2.0
                    val f = CrossSection.ramp(s, from - RoutePath.ASPHALT_TAPER_METERS, from)
                    Pair(half + (e.first - half) * f, -half + (e.second + half) * f)
                }
                end != null || s < d.to -> carriageway(d, end, min(s, d.to))
                else -> {
                    val held = carriageway(d, null, d.to - SAME_STATION) ?: return null
                    if (s <= d.back) return held
                    val half = bandOf(roadAt(d.to - SAME_STATION)) / 2.0
                    val f = CrossSection.ramp(s, d.back, d.asphaltTo)
                    Pair(held.first + (half - held.first) * f, held.second + (-half - held.second) * f)
                }
            }
        }

        /** The carriageway's asphalt edges at [s], with its aprons. */
        private fun carriageway(d: RoutePath.Drawing, end: Gore?, s: Double): Pair<Double, Double>? {
            val places = placesAt(d, end, s, axisAt(s))
            if (places.isEmpty()) return null
            return Pair(places.first().left + CrossSection.APRON_METERS, places.last().right - CrossSection.APRON_METERS)
        }

        private fun mainAsphalt(d: RoutePath.Drawing, end: Gore?, a: Double, b: Double, out: MutableList<Feature>) {
            val from = max(a, max(0.0, d.asphaltFrom))
            // At a turn the drawing ends at, the stub and the lanes beyond take the asphalt over where the turn's arc begins,
            // so all three meet across the same normal.
            val to = min(b, end?.begin ?: d.asphaltTo)
            if (to <= from) return
            val strip = Strip()
            for (s in samples(from, to)) {
                val edges = asphaltEdges(d, end, s) ?: continue
                val axis = axisAt(s)
                strip.stations.add(s)
                strip.left.add(axis.toGeo(axis.offset(s, edges.first)))
                strip.right.add(axis.toGeo(axis.offset(s, edges.second)))
            }
            polygon(strip, Kind.ASPHALT, Part.ROUTE, null)?.let { out.add(it) }
        }

        /** The lines and the darker lanes of the drawing [d] between [a] and [b]. */
        private fun mainLines(d: RoutePath.Drawing, end: Gore?, a: Double, b: Double, out: MutableList<Feature>) {
            val from = max(a, d.from)
            val to = min(b, d.to)
            if (to <= from) return
            val runs = LinkedHashMap<Any, Run>()
            val kept = LinkedHashMap<Int, Strip>()
            // The ramps' starts the path reaches in a lane: a gore reached on the road's line has no channelizing lines.
            val starts = gores.filter { !it.beyond && !it.onLine && it.station > d.from && it.station < d.to }
            for (s in samples(from, to)) {
                val axis = axisAt(s)
                val places = placesAt(d, end, s, axis)
                val stretch = if (s < d.to) stretchAt(s) else null
                val road = roadAt(s)
                val here = LinkedHashMap<Any, Pair<Style, Double>>()
                // Where the carriageway is bare the path crosses the lanes, which still go on straight along the old road: no line.
                val bareHere = onBare(s)
                if (!bareHere) for (k in 0 until places.size - 1) {
                    val l = places[k]
                    val r = places[k + 1]
                    val style = laneLine(l, r, stretch, road) ?: continue
                    here[Pair(l.lane, r.lane)] = style to l.right
                }
                if (places.isNotEmpty() && !bareHere) {
                    // A ramp start's side toward the road it leaves: the gore's channelizing line up to the nose.
                    val gore = starts.firstOrNull { s >= it.station && s <= it.routeNose }
                    for (side in listOf(LaneEdge.LEFT, LaneEdge.RIGHT)) {
                        val t = if (side == LaneEdge.LEFT) places.first().left else places.last().right
                        val channel = gore != null && (side == LaneEdge.LEFT) == gore.right
                        val style = if (channel) CHANNEL else edgeLine(side, s, stretch) ?: continue
                        here[side] = style to t
                    }
                }
                advance(runs, here, s, axis, out, Part.ROUTE)
                // Lanes kept for other vehicles, darker, where the data holds and outside zones and bare stretches.
                val known = !bareHere && stretch != null && (stretch.kind == LaneEvidence.Kind.KNOWN || stretch.kind == LaneEvidence.Kind.BRIDGED)
                val keptHere = HashSet<Int>()
                if (known) {
                    for (p in places) {
                        if (p.state == CrossSection.State.POSSIBLE || !kept(p.lane)) continue
                        keptHere.add(p.lane)
                        val strip = kept.getOrPut(p.lane) { Strip() }
                        strip.stations.add(s)
                        strip.left.add(axis.toGeo(axis.offset(s, p.left)))
                        strip.right.add(axis.toGeo(axis.offset(s, p.right)))
                    }
                }
                for (lane in kept.keys.filter { it !in keptHere }) {
                    polygon(requireNotNull(kept.remove(lane)), Kind.KEPT_LANE, Part.ROUTE, lane)?.let { out.add(it) }
                }
            }
            for (run in runs.values) emit(run, out, Part.ROUTE)
            for ((lane, strip) in kept) polygon(strip, Kind.KEPT_LANE, Part.ROUTE, lane)?.let { out.add(it) }
        }

        /**
         * Takes the lines present at [s], [here] (each by its key, with its style and offset along [axis]), into [runs]: a run
         * goes on while its key is present with the same style and does not step sideways; one that ends becomes a feature.
         */
        private fun advance(runs: LinkedHashMap<Any, Run>, here: Map<Any, Pair<Style, Double>>, s: Double, axis: RoadAxis, out: MutableList<Feature>, part: Part) {
            val points = HashMap<Any, GeoPoint>()
            for ((key, value) in here) points[key] = axis.toGeo(axis.offset(s, value.second))
            advancePoints(runs, here, points, s, out, part)
        }

        private fun advancePoints(
            runs: LinkedHashMap<Any, Run>,
            here: Map<Any, Pair<Style, Double>>,
            points: Map<Any, GeoPoint>,
            s: Double,
            out: MutableList<Feature>,
            part: Part,
        ) {
            val ending = runs.keys.filter { key ->
                val now = here[key]
                val run = requireNotNull(runs[key])
                now == null || now.first != run.style || steps(now.second - run.last, s - run.stations.last())
            }
            for (key in ending) emit(requireNotNull(runs.remove(key)), out, part)
            for ((key, value) in here) {
                val run = runs.getOrPut(key) { Run(value.first, key) }
                run.stations.add(s)
                run.points.add(requireNotNull(points[key]))
                run.last = value.second
            }
        }

        /** The style of the line between the lanes [l] and [r] at a station of [stretch] on [road]; null where none is drawn. */
        private fun laneLine(l: CrossSection.Place, r: CrossSection.Place, stretch: LaneEvidence.Stretch?, road: RoadKind): Style? {
            if (stretch == null || (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED)) return null
            if (l.state == CrossSection.State.POSSIBLE || r.state == CrossSection.State.POSSIBLE) return null
            val left = topology.lanes[l.lane]
            val right = topology.lanes[r.lane]
            if (l.width < LINE_LANE_SHARE * CrossSection.laneMeters(road, left.kinds) - NO_WIDTH) return null
            if (r.width < LINE_LANE_SHARE * CrossSection.laneMeters(road, right.kinds) - NO_WIDTH) return null
            val hovLeft = DesignatedKind.HOV in left.kinds
            val hovRight = DesignatedKind.HOV in right.kinds
            if (hovLeft != hovRight) return CARPOOL
            if (left.designated != right.designated) return SOLID_LINE
            if (!left.designated) {
                val point = stretch.point?.let { pointOf[it] }
                if (point != null) {
                    val only = exitOnly[point]
                    val leftOnly = only[l.lane]
                    val rightOnly = only[r.lane]
                    val drop = when {
                        leftOnly != null && leftOnly.first == LaneEdge.LEFT && rightOnly == null -> leftOnly.second
                        rightOnly != null && rightOnly.first == LaneEdge.RIGHT && leftOnly == null -> rightOnly.second
                        else -> null
                    }
                    if (drop != null) return if (drop) DROP else DOTTED_LINE
                }
            }
            return BROKEN_LINE
        }

        /** The style of the edge line on [side] at [s] of [stretch]; null where it is not drawn. */
        private fun edgeLine(side: LaneEdge, s: Double, stretch: LaneEvidence.Stretch?): Style? {
            if (stretch == null) return null
            when (stretch.kind) {
                LaneEvidence.Kind.KNOWN, LaneEvidence.Kind.BRIDGED -> Unit
                LaneEvidence.Kind.ZONE -> {
                    // Kept on an approach where no lane is named; not in a zone where the lanes are not known.
                    val zones = evidence.zones.filter { s >= it.from && s < it.to }
                    if (zones.isEmpty() || zones.any { it.cause != LaneEvidence.Zone.Cause.NO_LANE }) return null
                }
                else -> return null
            }
            if (requireNotNull(junctionGaps[side]).any { s >= it.first && s < it.second }) return null
            return if (side == LaneEdge.LEFT) LEFT_EDGE else RIGHT_EDGE
        }

        // ------------------------------------------------------------------ gores

        /** The gore where the drawing [d] meets the route's turn at [node], at [station]: at the drawing's end where [end]. */
        private fun goreAt(d: RoutePath.Drawing, node: Int, station: Double, end: Boolean): Gore? {
            val axis = pathAxes.firstOrNull { station >= it.from && station <= it.to } ?: return null
            val begin = min(station, max(d.from, CrossSection.beginOf(axis, station)))
            val atBegin = section.lanes(begin, axis).filter { it.width > NO_WIDTH }
            if (atBegin.isEmpty()) return null
            val point = pointOf[node]?.let { topology.points[it] }
            val right = when (point?.edge) {
                LaneEdge.RIGHT -> true
                LaneEdge.LEFT -> false
                null -> turnsRight(axis, begin, station)
            }
            val pathAtBegin = path.offsetAt(begin)
            // The lanes the route takes: a ramp start's lanes past the turn; at a drawing's end those the route can be
            // followed from, and the lane the path is in.
            val taken = HashSet<Int>()
            if (!end) {
                section.lanes(station + SAME_STATION, axis).forEach { taken.add(it.lane) }
            } else {
                point?.allowed?.let { taken.addAll(it) }
                atBegin.firstOrNull { pathAtBegin <= it.left + NO_WIDTH && pathAtBegin >= it.right - NO_WIDTH }?.let { taken.add(it.lane) }
            }
            val first = atBegin.indexOfFirst { it.lane in taken }
            val last = atBegin.indexOfLast { it.lane in taken }
            val stub = when {
                first < 0 -> emptyList()
                right -> atBegin.subList(0, first)
                else -> atBegin.subList(last + 1, atBegin.size)
            }
            if (!end && stub.isEmpty()) return null
            val beyondLeft: Double
            val beyondRight: Double
            if (first >= 0) {
                beyondLeft = atBegin[first].left - pathAtBegin
                beyondRight = atBegin[last].right - pathAtBegin
            } else {
                // Nothing to read the lanes from: one lane about the path.
                beyondLeft = LaneTopology.FREEWAY_LANE_METERS / 2.0
                beyondRight = -LaneTopology.FREEWAY_LANE_METERS / 2.0
            }
            return Gore(
                node = node,
                station = station,
                begin = begin,
                axis = axis,
                right = right,
                atBegin = atBegin,
                stub = stub,
                stubRoad = route.approachRoad(node),
                beyond = end,
                beyondLeft = beyondLeft,
                beyondRight = beyondRight,
                beyondLanes = if (first >= 0) atBegin.subList(first, last + 1).map { it.lane } else emptyList(),
                pathAtBegin = pathAtBegin,
                back = max(station, d.back),
                rampRoad = nodes[node].road,
                onLine = if (end) plan.segmentAt(station - SAME_STATION)?.noLane != null else diagonals.any { abs(it.from - station) < SAME_STATION },
            )
        }

        /** Whether the axis turns right from [begin] to past [station]. */
        private fun turnsRight(axis: RoadAxis, begin: Double, station: Double): Boolean {
            val a = axis.at(begin)
            val b = axis.at(min(axis.to, station + RoutePath.RETURN_METERS))
            return a.tx * b.ty - a.ty * b.tx < 0.0
        }

        /** Where the stub's and the route's sides of [gore] lie [NOSE_GAP_METERS] apart, along each. */
        private fun noseOf(gore: Gore) {
            if (gore.stub.isEmpty()) return
            val stubSide = if (gore.right) gore.stub.last().right else gore.stub.first().left
            val u0 = gore.station - gore.begin
            // The route's side toward the stub, every metre from the split.
            val route = ArrayList<Pair<Double, LocalPoint>>()
            var s = gore.station
            val until = gore.station + STUB_METERS
            while (s <= until + SAME_STATION) {
                val p = routeSide(gore, s) ?: break
                route.add(s to p)
                s += 1.0
            }
            // Along the route: the first place as far from the stub's straight side as the nose's gap.
            gore.routeNose = route.firstOrNull { (_, p) -> abs(distanceToStraight(gore, p, stubSide)) >= NOSE_GAP_METERS }?.first ?: until
            // Along the stub: the first place as far from the route's side.
            var u = u0
            var nose = gore.station + STUB_METERS
            while (u <= u0 + STUB_METERS + SAME_STATION) {
                val p = gore.straight(u, stubSide)
                if (distanceToPolyline(p, route.map { it.second }) >= NOSE_GAP_METERS) {
                    nose = gore.begin + u
                    break
                }
                u += 1.0
            }
            gore.stubNose = min(nose, gore.station + STUB_METERS)
        }

        /** The route's side of [gore] toward its stub at [s]: the ramp's edge there, in the axis' frame. */
        private fun routeSide(gore: Gore, s: Double): LocalPoint? {
            if (gore.beyond) {
                if (s > gore.back + SAME_STATION) return null
                return beside(gore, s, gore.right, 0.0)
            }
            val places = section.lanes(s, gore.axis).filter { it.width > NO_WIDTH }
            if (places.isEmpty()) return null
            return gore.axis.offset(s, if (gore.right) places.first().left else places.last().right)
        }

        /** How far [p] lies to the left of the stub's straight side, [t] to the left of the axis at the gore's begin. */
        private fun distanceToStraight(gore: Gore, p: LocalPoint, t: Double): Double {
            val o = gore.straight(0.0, t)
            val a = gore.axis.at(gore.begin)
            return (p.x - o.x) * a.nx + (p.y - o.y) * a.ny
        }

        /** The point of the lanes beyond the turn of [gore] at [s] ([beyondOffset]). */
        private fun beside(gore: Gore, s: Double, left: Boolean, extra: Double): LocalPoint = gore.axis.offset(s, beyondOffset(gore, s, left, extra))

        /**
         * Where the left ([left]) or right edge of the lanes beyond the turn of [gore], and [extra] metres more to the left,
         * lies at [s], as an offset from the axis. Up to the split it lies on its own straight line from where the turn's arc
         * begins, beside the path as it lay there, moved sideways only as far as the path has settled off its straight line;
         * from the split it blends, by the time the path is on the new road's line ([Gore.back]) and over the asphalt's taper at
         * least ([Gore.narrowed]), into one lane about the path across the new road: past the turn the route's data gives no
         * count, and one lane is the rule.
         */
        private fun beyondOffset(gore: Gore, s: Double, left: Boolean, extra: Double): Double {
            val r = (if (left) gore.beyondLeft else gore.beyondRight) + extra
            val target = (if (left) LaneTopology.laneMeters(gore.rampRoad) / 2.0 else -LaneTopology.laneMeters(gore.rampRoad) / 2.0) + extra
            val p0 = gore.pathAtBegin
            val line = CrossSection.lineOffset(gore.axis, gore.begin, p0 + r, s)
            val pathLine = CrossSection.lineOffset(gore.axis, gore.begin, p0, s)
            val straight = if (line == null || pathLine == null) r else line - pathLine
            return path.offsetAt(s) + straight + (target - straight) * CrossSection.ramp(s, gore.station, gore.narrowed)
        }

        /** The stub of [gore] between [a] and [b]: its asphalt and lines. */
        private fun stub(gore: Gore, a: Double, b: Double, out: MutableList<Feature>) {
            if (gore.stub.isEmpty()) return
            val axis = gore.axis
            val u0 = gore.station - gore.begin
            val u1 = u0 + STUB_METERS
            val u2 = u1 + RoutePath.ASPHALT_TAPER_METERS
            val left = gore.stub.first().left + CrossSection.APRON_METERS
            val right = gore.stub.last().right - CrossSection.APRON_METERS
            val half = bandOf(gore.stubRoad) / 2.0
            // The asphalt: straight on, and tapering into the map's band about the old road's line at its end.
            val strip = Strip()
            val us = ArrayList<Double>()
            // The asphalt from where the turn's arc begins, as the route's carriageway hands it over there.
            us.add(0.0)
            us.add(u0)
            var u = u1
            while (u < u2 - SAME_STATION) {
                us.add(u)
                u += TAPER_STEP_METERS
            }
            us.add(u2)
            us.add(a - gore.begin)
            us.add(b - gore.begin)
            for (x in us.filter { it >= 0.0 && it <= u2 && it >= a - gore.begin && it <= b - gore.begin }.sorted().distinct()) {
                val s = gore.begin + x
                val f = CrossSection.ramp(x, u1, u2)
                strip.stations.add(s)
                strip.left.add(axis.toGeo(gore.straight(x, left + (half - left) * f)))
                strip.right.add(axis.toGeo(gore.straight(x, right + (-half - right) * f)))
            }
            polygon(strip, Kind.ASPHALT, Part.STUB, null)?.let { out.add(it) }
            // The lines: between its lanes as their kinds say, the far edge, and the gore's side, a channelizing line to the nose.
            // Where the path reaches the gore on the road's line, it crosses the stub and the gore on the line's diagonal: as in
            // the zone before it, the stub is asphalt only.
            if (gore.onLine) return
            val runs = LinkedHashMap<Any, Run>()
            val nose = gore.stubNose
            val end = gore.station + STUB_METERS
            val stations = listOf(gore.station, nose - SAME_STATION, nose, end, a, b)
                .filter { it >= max(a, gore.station) && it <= min(b, end) }.sorted().distinct()
            for (s in stations) {
                val x = s - gore.begin
                val here = LinkedHashMap<Any, Pair<Style, Double>>()
                for (k in 0 until gore.stub.size - 1) {
                    val l = gore.stub[k]
                    val r = gore.stub[k + 1]
                    here[Pair(l.lane, r.lane)] = stubLine(l.lane, r.lane) to l.right
                }
                val farSide = if (gore.right) LaneEdge.LEFT else LaneEdge.RIGHT
                val goreSide = if (gore.right) LaneEdge.RIGHT else LaneEdge.LEFT
                here[farSide] = (if (farSide == LaneEdge.LEFT) LEFT_EDGE else RIGHT_EDGE) to (if (gore.right) gore.stub.first().left else gore.stub.last().right)
                val goreT = if (gore.right) gore.stub.last().right else gore.stub.first().left
                here[goreSide] = (if (s < nose) CHANNEL else if (goreSide == LaneEdge.LEFT) LEFT_EDGE else RIGHT_EDGE) to goreT
                val points = here.mapValues { (_, v) -> axis.toGeo(gore.straight(x, v.second)) }
                advancePoints(runs, here, points, s, out, Part.STUB)
            }
            for (run in runs.values) emit(run, out, Part.STUB)
        }

        /** The style of a line between two lanes of a stub: by their kinds only, past the data. */
        private fun stubLine(left: Int, right: Int): Style {
            val l = topology.lanes[left]
            val r = topology.lanes[right]
            val hovLeft = DesignatedKind.HOV in l.kinds
            val hovRight = DesignatedKind.HOV in r.kinds
            if (hovLeft != hovRight) return CARPOOL
            if (l.designated != r.designated) return SOLID_LINE
            return BROKEN_LINE
        }

        /** Past a turn without lane data at [gore]: the lanes the route takes beside the path, their edges, and their taper. */
        private fun beyond(gore: Gore, a: Double, b: Double, out: MutableList<Feature>) {
            // The asphalt from where the turn's arc begins, the lines from the split.
            val from = max(a, gore.begin)
            val back = gore.back
            val end = back + RoutePath.ASPHALT_TAPER_METERS
            val to = min(b, end)
            if (to <= from) return
            val apron = CrossSection.APRON_METERS
            val half = bandOf(gore.rampRoad) / 2.0
            val strip = Strip()
            val runs = LinkedHashMap<Any, Run>()
            for (s in samples(from, to)) {
                if (s <= back) {
                    strip.stations.add(s)
                    strip.left.add(gore.axis.toGeo(beside(gore, s, true, apron)))
                    strip.right.add(gore.axis.toGeo(beside(gore, s, false, -apron)))
                    // Reached on the road's line, the path crosses them on the line's diagonal: asphalt only.
                    if (s < gore.station - SAME_STATION || gore.onLine) continue
                    val here = LinkedHashMap<Any, Pair<Style, Double>>()
                    val goreSide = if (gore.right) LaneEdge.LEFT else LaneEdge.RIGHT
                    for (side in listOf(LaneEdge.LEFT, LaneEdge.RIGHT)) {
                        val style = when {
                            gore.stub.isNotEmpty() && side == goreSide && s <= gore.routeNose -> CHANNEL
                            side == LaneEdge.LEFT -> LEFT_EDGE
                            else -> RIGHT_EDGE
                        }
                        here[side] = style to beyondOffset(gore, s, side == LaneEdge.LEFT, 0.0)
                    }
                    val points = here.mapValues { (key, _) -> gore.axis.toGeo(beside(gore, s, key == LaneEdge.LEFT, 0.0)) }
                    advancePoints(runs, here, points, s, out, Part.BEYOND)
                } else {
                    // The path is on the new road's line: taper into the map's band about it.
                    val axis = axisAt(s)
                    val f = CrossSection.ramp(s, back, end)
                    val l = beyondOffset(gore, back, true, apron)
                    val r = beyondOffset(gore, back, false, -apron)
                    strip.stations.add(s)
                    strip.left.add(axis.toGeo(axis.offset(s, l + (half - l) * f)))
                    strip.right.add(axis.toGeo(axis.offset(s, r + (-half - r) * f)))
                }
            }
            for (run in runs.values) emit(run, out, Part.BEYOND)
            polygon(strip, Kind.ASPHALT, Part.BEYOND, null)?.let { out.add(it) }
        }

        // ------------------------------------------------------------------ symbols

        /** The diamonds of the car-pool lanes between [a] and [b], every [DIAMOND_EVERY_METERS] from the route's start. */
        private fun diamonds(a: Double, b: Double, out: MutableList<Feature>) {
            val hov = topology.lanes.filter { DesignatedKind.HOV in it.kinds }
            if (hov.isEmpty()) return
            for (d in drawings) {
                val from = max(a, d.from)
                val to = min(b, d.to)
                if (to <= from) continue
                var k = Math.ceil(from / DIAMOND_EVERY_METERS - 1e-9).toLong()
                while (k * DIAMOND_EVERY_METERS + DIAMOND_LENGTH_METERS <= to) {
                    val s = k * DIAMOND_EVERY_METERS
                    k++
                    if (s < from) continue
                    for (lane in hov) {
                        val middle = fullAt(d, s, lane.id, DIAMOND_LENGTH_METERS) ?: continue
                        val axis = axisAt(s)
                        val h = DIAMOND_WIDTH_METERS / 2.0
                        val l = DIAMOND_LENGTH_METERS
                        val ring = listOf(0.0 to 0.0, l / 2.0 to -h, l to 0.0, l / 2.0 to h, 0.0 to 0.0)
                            .map { (u, v) -> axis.toGeo(axis.offset(s + u, middle.at(u) + v)) }
                        out.add(Feature(Kind.DIAMOND, s, s + l, listOf(ring), Paint.WHITE, Width.NORMAL, Pattern.SOLID, lane = lane.id))
                    }
                }
            }
        }

        /**
         * The middle of [lane] from [s] for [length] metres where it is drawn at full width all along, in a known or bridged
         * stretch; null otherwise.
         */
        private fun fullAt(d: RoutePath.Drawing, s: Double, lane: Int, length: Double): Middle? {
            if (s < d.from || s + length > d.to) return null
            // Never where the carriageway is bare, where the path crosses the lanes.
            if (bare.any { it.first < s + length && it.second > s }) return null
            val centres = DoubleArray(3)
            for ((i, x) in listOf(s, s + length / 2.0, s + length).withIndex()) {
                val stretch = stretchAt(min(x, d.to - SAME_STATION)) ?: return null
                if (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED) return null
                val end = drawings.indexOf(d).let { ends[it] }
                if (end != null && x >= end.begin) return null
                val place = placesAt(d, end, x, axisAt(x)).firstOrNull { it.lane == lane } ?: return null
                if (place.state != CrossSection.State.FULL) return null
                val lanes = topology.lanes[lane]
                if (place.width < CrossSection.laneMeters(roadAt(x), lanes.kinds) - 1e-6) return null
                centres[i] = place.centre
            }
            return Middle(length, centres)
        }

        /**
         * A lane's middle over [length] metres from where a symbol begins, read at its start, half way and its end: a symbol
         * follows its lane where the lanes beside it taper and the lanes shift across the road.
         */
        private class Middle(private val length: Double, private val centres: DoubleArray) {
            /** The middle [u] metres on, between the three readings. */
            fun at(u: Double): Double {
                val half = length / 2.0
                return if (u <= half) {
                    centres[0] + (centres[1] - centres[0]) * u / half
                } else {
                    centres[1] + (centres[2] - centres[1]) * (u - half) / half
                }
            }
        }

        /** The lane arrows of every lane point between [a] and [b]. */
        private fun arrows(a: Double, b: Double, out: MutableList<Feature>) {
            for (point in topology.points) {
                val node = nodes[point.node]
                val rows = ArrayList<Double>()
                if (!point.turns) rows.add(point.station - ARROW_NEAR_METERS)
                rows.add(farRow(point))
                for (tail in rows) {
                    if (tail.isNaN() || tail < a || tail >= b) continue
                    val d = drawings.firstOrNull { tail >= it.from && tail + ARROW_LENGTH_METERS <= it.to } ?: continue
                    if (!ownStretch(point.node, tail)) continue
                    for ((i, lane) in node.lanes.withIndex()) {
                        if (lane.designated || lane.indications.isEmpty()) continue
                        val id = point.lanes.getOrNull(i) ?: continue
                        val middle = fullAt(d, tail, id, ARROW_LENGTH_METERS) ?: continue
                        val axis = axisAt(tail)
                        val rings = arrowRings(lane.indications).map { ring -> ring.map { (u, v) -> axis.toGeo(axis.offset(tail + u, middle.at(u) + v)) } }
                        if (rings.isEmpty()) continue
                        out.add(
                            Feature(
                                Kind.ARROW, tail, tail + ARROW_LENGTH_METERS, rings.map { ccw(it) }, Paint.WHITE,
                                lane = id, indications = lane.indications.sortedBy { it.ordinal },
                            ),
                        )
                    }
                }
            }
        }

        /**
         * Where the far row of [point]'s arrows begins: [ARROW_FAR_METERS] before the point, at a maneuver
         * [ARROW_FAR_MIN_METERS]; where an arrow there would not lie in the point's own stretch, the nearest place that
         * does within [ARROW_FAR_MIN_METERS] to [ARROW_FAR_MAX_METERS], in steps of ten metres; NaN where none does.
         */
        private fun farRow(point: LaneTopology.Point): Double {
            for (before in farRows(point.turns)) {
                val tail = point.station - before
                if (ownStretch(point.node, tail)) return tail
            }
            return Double.NaN
        }

        /** Whether an arrow from [tail] lies in the known stretch of the lane point at [node], all of it. */
        private fun ownStretch(node: Int, tail: Double): Boolean {
            val a = stretchAt(tail) ?: return false
            val b = stretchAt(tail + ARROW_LENGTH_METERS) ?: return false
            return a === b && a.kind == LaneEvidence.Kind.KNOWN && a.point == node
        }

        // ------------------------------------------------------------------ the lit lane and the tints

        /** The stretches between [a] and [b] inside both a card window and a drawing, each with its drawing's index. */
        private fun litRanges(a: Double, b: Double): List<Triple<Double, Double, Int>> {
            val out = ArrayList<Triple<Double, Double, Int>>()
            for ((i, d) in drawings.withIndex()) {
                for ((w0, w1) in windowSpans) {
                    val s0 = max(max(a, w0), d.from)
                    val s1 = min(min(b, w1), d.to)
                    if (s1 - s0 > SAME_STATION) out.add(Triple(s0, s1, i))
                }
            }
            return out
        }

        /**
         * Stations to sample the lit lane and the tints at from [s0] to [s1]: [samples] about the events [at], each piece's start
         * and the path's moves' ends.
         */
        private fun litSamples(s0: Double, s1: Double, at: DoubleArray = events): List<Double> {
            val all = ArrayList<Double>(samples(s0, s1, at))
            var k = pieceOf(s0) + 1
            while (k * PIECE_METERS < s1 - SAME_STATION) {
                all.add(k * PIECE_METERS)
                k++
            }
            for (m in path.moves) {
                for (x in listOf(m.from, m.to)) if (x > s0 + SAME_STATION && x < s1 - SAME_STATION) all.add(x)
            }
            all.sort()
            val out = ArrayList<Double>(all.size)
            for (x in all) if (out.isEmpty() || x - out.last() > SAME_STATION / 2.0) out.add(x)
            return out
        }

        /** The lane the plan names at [s], where it lights one: never a designated lane, never on an approach where none is named. */
        private fun namedAt(s: Double): Int? {
            val seg = plan.segmentAt(s) ?: return null
            if (seg.noLane != null) return null
            val named = seg.named ?: return null
            return if (topology.lanes[named].designated) null else named
        }

        /**
         * The lane lit at [s]: the one the plan names ([namedAt]), but not where the carriageway is bare ([bare]), where the path
         * crosses the lanes, and not where it cannot be lit about the path ([dark]).
         */
        private fun litLane(s: Double): Int? {
            val named = namedAt(s) ?: return null
            if (onBare(s) || dark.any { s >= it.first && s < it.second }) return null
            return named
        }

        /** Whether [s] lies where the route's carriageway is drawn bare ([bare]). */
        private fun onBare(s: Double): Boolean = bare.any { s >= it.first && s < it.second }

        /**
         * Whether [named] can be lit at [s] of the drawing [i] as its full width about the path: the path lies within half that
         * lane's width of its drawn middle (to the path's own [RoutePath.TOLERANCE_METERS]), or in its feeder while a lane its
         * neighbour feeds opens along its taper, or a move of the path is under way there; and the lit lane lies on the lanes
         * drawn there, its edges no farther than [LIT_FIT_METERS] past the outer edges of the drawn carriageway.
         */
        private fun lightable(i: Int, s: Double, named: Int): Boolean {
            val places = placesAt(drawings[i], ends[i], s, axisAt(s))
            if (places.isEmpty()) return false
            val t = path.offsetAt(s)
            val lane = topology.lanes[named]
            val half = CrossSection.laneMeters(roadAt(s), lane.kinds) / 2.0
            if (t + half > places.first().left + LIT_FIT_METERS || t - half < places.last().right - LIT_FIT_METERS) return false
            if (path.moves.any { s >= it.from && s <= it.to }) return true
            val place = places.firstOrNull { it.lane == named }
            if (place != null && abs(t - place.centre) <= half + RoutePath.TOLERANCE_METERS) return true
            val feeder = lane.fedBy?.let { f -> places.firstOrNull { it.lane == f } } ?: return false
            if (place != null && place.width >= 2.0 * half - NO_WIDTH) return false
            val left = max(place?.left ?: feeder.left, feeder.left)
            val right = min(place?.right ?: feeder.right, feeder.right)
            return t <= left + RoutePath.TOLERANCE_METERS && t >= right - RoutePath.TOLERANCE_METERS
        }

        /**
         * The stretches inside card windows and drawings where the plan names a lane, off the bare stretches, but it cannot be lit
         * about the path ([lightable]), found at the samples about the events [at]: each from its first such sample to the next
         * where it can again. So where the path keeps to the road's line beside a lane that is still opening, the lane is lit from
         * where it lies about the path, and where the path reaches a drawing on the road's line, from where it moves into the lane.
         */
        private fun darkSpans(at: DoubleArray): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            for ((s0, s1, i) in litRanges(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                var from = Double.NaN
                for (s in litSamples(s0, s1, at)) {
                    val named = namedAt(s)
                    val off = named != null && !onBare(s) && !lightable(i, s, named)
                    if (off && from.isNaN()) from = s
                    if (!off && !from.isNaN()) {
                        out.add(from to s)
                        from = Double.NaN
                    }
                }
                if (!from.isNaN()) out.add(from to s1)
            }
            return out
        }

        /**
         * The lit lane between [a] and [b], inside card windows and drawings wherever the plan names a lane: [named]'s full
         * width about the route's path, square to the path's own way ([ribbonAt]), so it moves with the path from lane to lane
         * and along a taper; cut into pieces at multiples of [PIECE_METERS] ([litId]), with its two edges ([Kind.LIT_EDGE]).
         */
        private fun lit(a: Double, b: Double, out: MutableList<Feature>) {
            for ((s0, s1, _) in litRanges(a, b)) {
                val pieces = Pieces(Kind.LIT, out)
                val edges = Strip()
                var lane: Int? = null
                for (s in litSamples(s0, s1)) {
                    val named = litLane(s)
                    if (named == null) {
                        pieces.close()
                        litEdges(edges, lane, out)
                        continue
                    }
                    if (edges.stations.isEmpty()) lane = named
                    val (l, r) = ribbonAt(s, named)
                    pieces.add(s, l, r, named) { litId(it) }
                    edges.stations.add(s)
                    edges.left.add(l)
                    edges.right.add(r)
                }
                pieces.close()
                litEdges(edges, lane, out)
            }
        }

        /** The lit lane's edges along [edges] as two lines without their small loops ([untangle]), and [edges] emptied. */
        private fun litEdges(edges: Strip, lane: Int?, out: MutableList<Feature>) {
            if (edges.stations.size >= 2 && edges.stations.last() - edges.stations.first() > SAME_STATION) {
                for ((side, points) in listOf(LaneEdge.LEFT to edges.left, LaneEdge.RIGHT to edges.right)) {
                    out.add(
                        Feature(
                            Kind.LIT_EDGE, edges.stations.first(), edges.stations.last(), listOf(simplify(untangle(points, edges.stations).first)),
                            width = Width.EDGE, pattern = Pattern.SOLID, lane = lane, side = side,
                        ),
                    )
                }
            }
            edges.stations.clear()
            edges.left.clear()
            edges.right.clear()
        }

        /**
         * The lit lane's left and right edges at [s]: [named]'s full width about the path's point, square to the path's own
         * way there (the axis' way turned by the offset's slope).
         */
        private fun ribbonAt(s: Double, named: Int): Pair<GeoPoint, GeoPoint> {
            val axis = path.axisAt(s) ?: axisAt(s)
            val place = axis.at(s)
            val t = path.offsetAt(s)
            val slope = (path.offsetAt(s + SLOPE_METERS) - path.offsetAt(s - SLOPE_METERS)) / (2.0 * SLOPE_METERS)
            val along = 1.0 - place.curvature * t
            val dx = place.tx * along + place.nx * slope
            val dy = place.ty * along + place.ny * slope
            val norm = hypot(dx, dy)
            // The left normal of the path's way.
            val nx = -dy / norm
            val ny = dx / norm
            val half = CrossSection.laneMeters(roadAt(s), topology.lanes[named].kinds) / 2.0
            val cx = place.point.x + place.nx * t
            val cy = place.point.y + place.ny * t
            return axis.toGeo(LocalPoint(cx + nx * half, cy + ny * half)) to axis.toGeo(LocalPoint(cx - nx * half, cy - ny * half))
        }

        /**
         * A stretch where [lane] is tinted, inside the drawing [drawing], from [from] to [to]; [leaves]: it ends where the lane
         * stops working while it is still there, so it ends in a wedge.
         */
        private class TintSpan(val lane: Int, val drawing: Int, val from: Double, val to: Double, val leaves: Boolean)

        /**
         * Every tinted stretch of the route, in route order. Found once, from the events: a tint begins or ends only where the
         * band, the named lane, a stretch, a lane or a window does, so a wedge is the same in every window.
         */
        private val tintSpans: List<TintSpan> by lazy {
            val out = ArrayList<TintSpan>()
            for ((r0, r1, i) in litRanges(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                val stops = ArrayList<Double>()
                stops.add(r0)
                var k = lowerBound(events, r0)
                while (k < events.size && events[k] < r1) {
                    if (events[k] > r0 + SAME_STATION && events[k] < r1 - SAME_STATION) stops.add(events[k])
                    k++
                }
                stops.add(r1)
                val open = LinkedHashMap<Int, Double>()
                for (j in 0 until stops.size - 1) {
                    val mid = (stops[j] + stops[j + 1]) / 2.0
                    val here = tintedAt(i, mid)
                    for (lane in open.keys.toList()) {
                        if (lane in here) continue
                        out.add(TintSpan(lane, i, requireNotNull(open.remove(lane)), stops[j], leavesBand(i, lane, mid)))
                    }
                    for (lane in here) if (lane !in open) open[lane] = stops[j]
                }
                for ((lane, from) in open) out.add(TintSpan(lane, i, from, r1, false))
            }
            out.sortedWith(compareBy<TintSpan>({ it.from }, { it.lane }))
        }

        /**
         * The lanes tinted at [s] of the drawing [i]: the band's lanes beside the named one, where the data gives the lanes (not
         * in a zone); never a designated lane, never one that is only possible.
         */
        private fun tintedAt(i: Int, s: Double): Set<Int> {
            val named = litLane(s) ?: return emptySet()
            val stretch = stretchAt(s) ?: return emptySet()
            if (stretch.kind != LaneEvidence.Kind.KNOWN && stretch.kind != LaneEvidence.Kind.BRIDGED) return emptySet()
            val band = plan.segmentAt(s)?.band ?: return emptySet()
            val places = placesAt(drawings[i], ends[i], s, axisAt(s))
            val out = LinkedHashSet<Int>()
            for (lane in band) {
                if (lane == named || topology.lanes[lane].designated) continue
                val place = places.firstOrNull { it.lane == lane } ?: continue
                if (place.state != CrossSection.State.POSSIBLE) out.add(lane)
            }
            return out
        }

        /**
         * Whether [lane], tinted up to where the stretch holding [s] of the drawing [i] begins, stops working there: it is out of
         * the band while it is still there and a lane is lit beside it; or a zone begins there, where no lane but the lit one is
         * drawn, and the lane stops working before the zone ends.
         */
        private fun leavesBand(i: Int, lane: Int, s: Double): Boolean {
            val stretch = stretchAt(s)
            if (stretch != null && stretch.kind == LaneEvidence.Kind.ZONE) {
                return plan.segments.any { seg ->
                    seg.from < stretch.to && seg.to > s && seg.named != null && seg.noLane == null && lane !in seg.band
                }
            }
            val seg = plan.segmentAt(s) ?: return false
            val named = namedAt(s) ?: return false
            if (lane == named || lane in seg.band) return false
            return placesAt(drawings[i], ends[i], s, axisAt(s)).any { it.lane == lane }
        }

        /**
         * The tints between [a] and [b]: each lane of [tintSpans] as it is drawn, cut into pieces at multiples of
         * [PIECE_METERS] ([tintId]); where its lane stops working, its far edge from the lit lane slides onto its near edge over
         * [WEDGE_METERS] (or the whole tint where it is shorter): a wedge pointing at the lit lane.
         */
        private fun tints(a: Double, b: Double, out: MutableList<Feature>) {
            for (span in tintSpans) {
                val s0 = max(a, span.from)
                val s1 = min(b, span.to)
                if (s1 - s0 <= SAME_STATION) continue
                val d = drawings[span.drawing]
                val end = ends[span.drawing]
                val wedge = if (span.leaves) wedgeMeters(span.to - span.from) else 0.0
                val pieces = Pieces(Kind.TINT, out)
                for (s in litSamples(s0, s1)) {
                    val axis = axisAt(s)
                    val place = if (end != null && s >= end.begin) {
                        placesAt(d, end, s, axis).firstOrNull { it.lane == span.lane }
                    } else {
                        section.lanes(s, axis).firstOrNull { it.lane == span.lane }
                    } ?: continue
                    var left = place.left
                    var right = place.right
                    if (wedge > 0.0) {
                        val f = CrossSection.ramp(s, span.to - wedge, span.to)
                        if (place.centre > path.offsetAt(s)) left += (right - left) * f else right += (left - right) * f
                    }
                    pieces.add(s, axis.toGeo(axis.offset(s, left)), axis.toGeo(axis.offset(s, right)), span.lane) { tintId(span.lane, it) }
                }
                pieces.close()
            }
        }

        /**
         * Strips of one polygon kind cut into pieces at multiples of [PIECE_METERS], each a feature with its piece's id. A strip
         * is taken whole, its edges without their small loops ([untangle]), before it is cut, so a loop is cut the same where a
         * piece's start falls on it.
         */
        private inner class Pieces(private val kind: Kind, private val out: MutableList<Feature>) {
            private val strip = Strip()
            private val lanes = ArrayList<Int>()
            private var idOf: (Long) -> Long = { it }

            /** Adds the sample at [s], its [left] and [right] edges, of [lane]; [idOf] gives a piece's id. */
            fun add(s: Double, left: GeoPoint, right: GeoPoint, lane: Int, idOf: (Long) -> Long) {
                strip.stations.add(s)
                strip.left.add(left)
                strip.right.add(right)
                lanes.add(lane)
                this.idOf = idOf
            }

            /** Ends the strip being made: its pieces, each from its first sample to the next piece's start where a sample lies there. */
            fun close() {
                val stations = strip.stations
                if (stations.size >= 2) {
                    val (left, leftAt) = untangle(strip.left, stations)
                    val (right, rightAt) = untangle(strip.right, stations)
                    val whole = left === strip.left && right === strip.right
                    var i = 0
                    while (i < stations.size) {
                        val k = pieceOf(stations[i])
                        var j = i
                        while (j + 1 < stations.size && pieceOf(stations[j + 1]) == k) j++
                        // The next piece's first sample ends this one too where it lies on the pieces' boundary.
                        val end = if (j + 1 < stations.size && abs(stations[j + 1] - (k + 1) * PIECE_METERS) <= SAME_STATION) j + 1 else j
                        val from = stations[i]
                        val to = stations[end]
                        if (to - from > SAME_STATION) {
                            val feature = if (whole) {
                                ring(strip.left.subList(i, end + 1), strip.right.subList(i, end + 1), kind, Part.ROUTE, lanes[i], from, to)
                            } else {
                                ring(between(left, leftAt, from, to), between(right, rightAt, from, to), kind, Part.ROUTE, lanes[i], from, to)
                            }
                            feature?.let { out.add(it.copy(id = idOf(k))) }
                        }
                        i = j + 1
                    }
                }
                strip.stations.clear()
                strip.left.clear()
                strip.right.clear()
                lanes.clear()
            }

            /** The part of the edge [points] at [stations] from [from] to [to]: its points between them, and its points at both. */
            private fun between(points: List<GeoPoint>, stations: List<Double>, from: Double, to: Double): List<GeoPoint> {
                val part = ArrayList<GeoPoint>()
                part.add(pointOn(points, stations, from))
                for (k in stations.indices) if (stations[k] > from && stations[k] < to) part.add(points[k])
                part.add(pointOn(points, stations, to))
                return part
            }
        }

        // ------------------------------------------------------------------ geometry helpers

        /** The axis that holds [s]: a window of the path, else a piece of the route's own line. */
        private fun axisAt(s: Double): RoadAxis {
            for (axis in pathAxes) if (s >= axis.from - SAME_STATION && s <= axis.to + SAME_STATION) return axis
            for (axis in extraAxes) if (s >= axis.from - SAME_STATION && s <= axis.to + SAME_STATION) return axis
            return pathAxes.minByOrNull { abs(it.from - s) } ?: extraAxes.first()
        }

        /** Stations to sample [s0] to [s1] at: both ends, every axis sample between them, and each of the events [at] with one just before it. */
        private fun samples(s0: Double, s1: Double, at: DoubleArray = events): List<Double> {
            // The ends and the events exactly, each event with a station just before it; and the axes' samples that do not
            // fall on one of them, so a change at an event is never moved to an axis sample a hair before it.
            val exact = ArrayList<Double>()
            exact.add(s0)
            exact.add(s1)
            var k = lowerBound(at, s0)
            while (k < at.size && at[k] < s1) {
                val e = at[k]
                if (e > s0) {
                    exact.add(e)
                    if (e - SAME_STATION > s0) exact.add(e - SAME_STATION)
                }
                k++
            }
            exact.sort()
            val out = ArrayList<Double>(exact)
            for (axis in pathAxes + extraAxes) {
                if (axis.to < s0 || axis.from > s1) continue
                for (x in stationCache.getOrPut(axis) { axis.stations() }) {
                    if (x <= s0 || x >= s1) continue
                    val j = java.util.Collections.binarySearch(exact, x).let { if (it >= 0) it else -it - 1 }
                    val near = (j < exact.size && exact[j] - x <= SAME_STATION) || (j > 0 && x - exact[j - 1] <= SAME_STATION)
                    if (!near) out.add(x)
                }
            }
            out.sort()
            val distinct = ArrayList<Double>(out.size)
            for (x in out) if (distinct.isEmpty() || x - distinct.last() > SAME_STATION / 2.0) distinct.add(x)
            return distinct
        }

        /** The axis of the route's own line from [from] to [to]; null where it is too short. */
        private fun lineAxis(from: Double, to: Double): RoadAxis? {
            val st = path.routeStations
            val pts = path.route
            if (pts.size < 2) return null
            val a = max(from, st.first())
            val b = min(to, st.last())
            if (b - a < 1e-3) return null
            val points = ArrayList<GeoPoint>()
            val stations = ArrayList<Double>()
            points.add(routePointAt(a))
            stations.add(a)
            for (i in pts.indices) {
                if (st[i] > a + 1e-9 && st[i] < b - 1e-9) {
                    points.add(pts[i])
                    stations.add(st[i])
                }
            }
            points.add(routePointAt(b))
            stations.add(b)
            return try {
                RoadAxis.of(points, stations.toDoubleArray(), points.first())
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun routePointAt(s: Double): GeoPoint {
            val st = path.routeStations
            val pts = path.route
            val i = RoutePath.indexAtOrBefore(st, s).coerceIn(0, pts.size - 1)
            if (i >= pts.size - 1) return pts.last()
            val f = if (st[i + 1] > st[i]) ((s - st[i]) / (st[i + 1] - st[i])).coerceIn(0.0, 1.0) else 0.0
            val p = pts[i]
            val q = pts[i + 1]
            return GeoPoint(p.latitude + (q.latitude - p.latitude) * f, p.longitude + (q.longitude - p.longitude) * f)
        }

        /** The evidence's stretch at [s]: from its start up to but not at its end; null off the route. */
        private fun stretchAt(s: Double): LaneEvidence.Stretch? {
            var lo = 0
            var hi = stretches.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val st = stretches[mid]
                when {
                    s < st.from -> hi = mid - 1
                    s >= st.to -> lo = mid + 1
                    else -> return st
                }
            }
            return null
        }

        /** The kind of road at [s]: the cross-section's, else the road that leaves the last intersection before it. */
        private fun roadAt(s: Double): RoadKind = section.roadAt(s) ?: nodes.lastOrNull { it.station <= s + SAME_STATION }?.road ?: RoadKind.FREEWAY

        private fun bandOf(road: RoadKind): Double = if (road == RoadKind.FREEWAY) STANDARD_FREEWAY_BAND_METERS else STANDARD_RAMP_BAND_METERS

        /** Whether [lane] is kept for vehicles other than car pools and bicycles: drawn darker. */
        private fun kept(lane: Int): Boolean {
            val kinds = topology.lanes[lane].kinds
            if (kinds.isEmpty() || DesignatedKind.HOV in kinds) return false
            return kinds.any { it != DesignatedKind.BICYCLE }
        }

        /** The lanes [point] lists as only for an exit, with the side and whether each is a lane drop. */
        private fun exitOnlyAt(k: Int): Map<Int, Pair<LaneEdge, Boolean>> {
            val point = topology.points[k]
            if (route.approachRoad(point.node) != RoadKind.FREEWAY) return emptyMap()
            val lanes = nodes[point.node].lanes
            val out = HashMap<Int, Pair<LaneEdge, Boolean>>()
            for (side in listOf(LaneEdge.RIGHT, LaneEdge.LEFT)) {
                val arrows = if (side == LaneEdge.RIGHT) RIGHT_ARROWS else LEFT_ARROWS
                val order = if (side == LaneEdge.RIGHT) lanes.indices.reversed() else lanes.indices
                for (i in order) {
                    val lane = lanes[i]
                    if (lane.designated || lane.indications.isEmpty() || !arrows.containsAll(lane.indications)) break
                    val id = point.lanes.getOrNull(i) ?: break
                    out[id] = side to wentOnThrough(k, id, arrows)
                }
            }
            // Where every lane points one way, none is only for an exit.
            return if (out.size >= lanes.size) emptyMap() else out
        }

        /** Whether [lane] is listed at a point of its run before the point [k] with an arrow away from [arrows]: it went on through. */
        private fun wentOnThrough(k: Int, lane: Int, arrows: Set<TurnModifier>): Boolean {
            val point = topology.points[k]
            for (j in k - 1 downTo 0) {
                val earlier = topology.points[j]
                if (earlier.run != point.run) break
                val i = earlier.lanes.indexOf(lane)
                if (i < 0) continue
                val listed = nodes[earlier.node].lanes.getOrNull(i) ?: continue
                if (listed.indications.isEmpty() || !arrows.containsAll(listed.indications)) return true
            }
            return false
        }

        /** For each side, where an edge line is broken: about each intersection the route goes on through where a road meets that side. */
        private fun junctions(): Map<LaneEdge, List<Pair<Double, Double>>> {
            val left = ArrayList<Pair<Double, Double>>()
            val right = ArrayList<Pair<Double, Double>>()
            for ((i, node) in nodes.withIndex()) {
                if (i == 0 || LaneEvidence.planTurns(node)) continue
                val out = node.outIndex ?: continue
                val outBearing = node.bearings.getOrNull(out) ?: continue
                for (j in node.bearings.indices) {
                    if (j == out || j == node.inIndex) continue
                    val bearing = node.bearings[j] ?: continue
                    val turn = Math.floorMod(bearing - outBearing, FULL_TURN)
                    val gap = node.station - JUNCTION_GAP_METERS to node.station + JUNCTION_GAP_METERS
                    when {
                        turn in 1 until HALF_TURN -> right.add(gap)
                        turn in HALF_TURN + 1 until FULL_TURN -> left.add(gap)
                    }
                }
            }
            return mapOf(LaneEdge.LEFT to left, LaneEdge.RIGHT to right)
        }

        // ------------------------------------------------------------------ features from samples

        /**
         * A polygon of [strip]: its right edge forward and its left edge back, each without its small loops ([untangle]) and
         * simplified; null with fewer than two samples.
         */
        private fun polygon(strip: Strip, kind: Kind, part: Part, lane: Int?): Feature? {
            if (strip.stations.size < 2) return null
            val right = untangle(strip.right, strip.stations).first
            val left = untangle(strip.left, strip.stations).first
            return ring(left, right, kind, part, lane, strip.stations.first(), strip.stations.last())
        }

        /**
         * The polygon of the edges [left] and [right] from [from] to [to]: the right edge forward and the left back, each
         * simplified. Where the two edges come within a few centimetres of each other (the tip of a wedge or a taper), simplified
         * apart they may cross, and the ring is made of the edges as they were made, which never cross there; a ring that still
         * crosses itself, a piece that begins inside a loop of a curve too tight for it, is made simple ([Loops.cutRing]). Null
         * where nothing of it is left.
         */
        private fun ring(left: List<GeoPoint>, right: List<GeoPoint>, kind: Kind, part: Part, lane: Int?, from: Double, to: Double): Feature? {
            if (left.isEmpty() || right.isEmpty()) return null
            val simple = closed(simplify(right), simplify(left))
            val frame = LocalMeters(simple.first().latitude, simple.first().longitude)
            val flat = { points: List<GeoPoint> -> points.map { frame.toLocal(it.latitude, it.longitude) } }
            val ring = if (Loops.ringCrossing(flat(simple)) == null) {
                simple
            } else {
                val raw = closed(right, left)
                val local = flat(raw)
                if (Loops.ringCrossing(local) == null) raw else Loops.cutRing(local).map { GeoPoint(frame.latitudeOf(it), frame.longitudeOf(it)) }
            }
            if (ring.size < 4) return null
            return Feature(kind, from, to, listOf(ccw(ring)), part = part, lane = lane)
        }

        /** The closed ring of [right] forward and [left] back. */
        private fun closed(right: List<GeoPoint>, left: List<GeoPoint>): List<GeoPoint> {
            val ring = ArrayList<GeoPoint>(right.size + left.size + 1)
            ring.addAll(right)
            for (i in left.indices.reversed()) ring.add(left[i])
            ring.add(ring.first())
            return ring
        }

        /**
         * Makes [run] a feature: without its small loops ([untangle]), a solid line simplified, a broken or dotted one cut into
         * dashes at the route's stations.
         */
        private fun emit(run: Run, out: MutableList<Feature>, part: Part) {
            if (run.stations.size < 2) return
            val from = run.stations.first()
            val to = run.stations.last()
            if (to - from < SAME_STATION) return
            val style = run.style
            val (points, stations) = untangle(run.points, run.stations)
            val parts: List<List<GeoPoint>> = if (style.pattern == Pattern.SOLID) {
                listOf(simplify(points))
            } else {
                dashes(points, stations, style.pattern)
            }
            if (parts.isEmpty()) return
            val key = run.key
            out.add(
                Feature(
                    kind = style.kind,
                    from = from,
                    to = to,
                    parts = parts,
                    paint = style.paint,
                    width = style.width,
                    pattern = style.pattern,
                    part = part,
                    between = (key as? Pair<*, *>)?.let { Pair(it.first as Int, it.second as Int) },
                    side = key as? LaneEdge,
                ),
            )
        }

        /**
         * The dashes of the line through [points] at [stations] in [pattern]: each from a multiple of the pattern's period along
         * the route, two points each.
         */
        private fun dashes(points: List<GeoPoint>, stations: List<Double>, pattern: Pattern): List<List<GeoPoint>> {
            val from = stations.first()
            val to = stations.last()
            val period = pattern.periodMeters
            val out = ArrayList<List<GeoPoint>>()
            var k = floor(from / period).toLong()
            while (k * period < to) {
                val d0 = max(from, k * period)
                val d1 = min(to, k * period + pattern.dashMeters)
                k++
                if (d1 - d0 < MIN_DASH_METERS) continue
                out.add(listOf(pointOn(points, stations, d0), pointOn(points, stations, d1)))
            }
            return out
        }

        private companion object {
            /** Dashes shorter than this, at the ends of a line, are left out. */
            const val MIN_DASH_METERS = 0.05

            const val FULL_TURN = 360
            const val HALF_TURN = 180

            val BROKEN_LINE = Style(Kind.LANE_LINE, Paint.WHITE, Width.NORMAL, Pattern.BROKEN)
            val SOLID_LINE = Style(Kind.LANE_LINE, Paint.WHITE, Width.NORMAL, Pattern.SOLID)
            val CARPOOL = Style(Kind.LANE_LINE, Paint.WHITE, Width.WIDE, Pattern.SOLID)
            val DOTTED_LINE = Style(Kind.LANE_LINE, Paint.WHITE, Width.NORMAL, Pattern.DOTTED)
            val DROP = Style(Kind.LANE_LINE, Paint.WHITE, Width.WIDE, Pattern.DOTTED)
            val LEFT_EDGE = Style(Kind.EDGE_LINE, Paint.YELLOW, Width.NORMAL, Pattern.SOLID)
            val RIGHT_EDGE = Style(Kind.EDGE_LINE, Paint.WHITE, Width.NORMAL, Pattern.SOLID)
            val CHANNEL = Style(Kind.CHANNEL_LINE, Paint.WHITE, Width.WIDE, Pattern.SOLID)

            fun lowerBound(values: DoubleArray, x: Double): Int {
                var lo = 0
                var hi = values.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (values[mid] < x) lo = mid + 1 else hi = mid
                }
                return lo
            }
        }
    }
}

/** [points] without those that lie within [RoutePath.TOLERANCE_METERS] of the line between their kept neighbours (Douglas-Peucker). */
private fun simplify(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size <= 2) return points
    val frame = LocalMeters(points.first().latitude, points.first().longitude)
    val local = points.map { frame.toLocal(it.latitude, it.longitude) }
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.size - 1)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b - a < 2) continue
        var worst = -1.0
        var at = -1
        for (i in a + 1 until b) {
            val d = distanceToSegment(local[i], local[a], local[b])
            if (d > worst) {
                worst = d
                at = i
            }
        }
        if (worst > RoutePath.TOLERANCE_METERS) {
            keep[at] = true
            stack.addLast(a to at)
            stack.addLast(at to b)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/**
 * [points] at [stations] without their small loops ([Loops.cut]), cut in flat metres about the first point: where a line or an
 * edge lies farther out than an arc's radius on its inner side it turns where it would cross itself. Where nothing is cut, the
 * same lists.
 */
private fun untangle(points: List<GeoPoint>, stations: List<Double>): Pair<List<GeoPoint>, List<Double>> {
    if (points.size < 4) return points to stations
    val frame = LocalMeters(points.first().latitude, points.first().longitude)
    return Loops.cut(points, stations, { frame.toLocal(it.latitude, it.longitude) }, { GeoPoint(frame.latitudeOf(it), frame.longitudeOf(it)) })
}

/** The point at station [s] of the line through [points] at [stations], ascending: one of them, or between two. */
private fun pointOn(points: List<GeoPoint>, stations: List<Double>, s: Double): GeoPoint {
    var lo = 0
    var hi = stations.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (stations[mid] <= s) lo = mid else hi = mid
    }
    val a = points[lo]
    val b = points[hi]
    val f = if (stations[hi] > stations[lo]) ((s - stations[lo]) / (stations[hi] - stations[lo])).coerceIn(0.0, 1.0) else 0.0
    return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
}

private fun distanceToSegment(p: LocalPoint, a: LocalPoint, b: LocalPoint): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 <= 0.0) return p.distanceTo(a)
    val f = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    return p.distanceTo(LocalPoint(a.x + dx * f, a.y + dy * f))
}

/** How far [p] lies from the polyline [line]: its nearest segment's nearest point. */
private fun distanceToPolyline(p: LocalPoint, line: List<LocalPoint>): Double {
    if (line.isEmpty()) return Double.POSITIVE_INFINITY
    if (line.size == 1) return p.distanceTo(line[0])
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until line.size - 1) best = min(best, distanceToSegment(p, line[i], line[i + 1]))
    return best
}

/** [ring] counterclockwise (its signed area positive, east and north up), as GeoJSON wants outer rings. */
private fun ccw(ring: List<GeoPoint>): List<GeoPoint> {
    if (ring.size < 4) return ring
    val frame = LocalMeters(ring.first().latitude, ring.first().longitude)
    var area = 0.0
    var prev = frame.toLocal(ring[0].latitude, ring[0].longitude)
    for (i in 1 until ring.size) {
        val p = frame.toLocal(ring[i].latitude, ring[i].longitude)
        area += prev.x * p.y - p.x * prev.y
        prev = p
    }
    return if (area >= 0.0) ring else ring.reversed()
}

/**
 * The rings of a lane arrow with [indications], in metres: [u] along the lane from the tail, [v] across it, left
 * positive; one ring per arrow it shows (a straight arrow and a bent one share the tail). A U-turn shows none.
 */
private fun arrowRings(indications: Set<TurnModifier>): List<List<Pair<Double, Double>>> {
    val out = ArrayList<List<Pair<Double, Double>>>()
    val length = LaneDrawing.ARROW_LENGTH_METERS
    for (m in indications.sortedBy { it.ordinal }) {
        val side = when (m) {
            TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT -> -1.0
            TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT -> 1.0
            else -> 0.0
        }
        val centre: List<Pair<Double, Double>> = when (m) {
            TurnModifier.STRAIGHT -> listOf(0.0 to 0.0, length - LaneDrawing.ARROW_HEAD_LENGTH_METERS to 0.0, length to 0.0)
            TurnModifier.SLIGHT_RIGHT, TurnModifier.SLIGHT_LEFT -> {
                val stem = SLIGHT_STEM * length
                val a = Math.toRadians(SLIGHT_TURN_DEGREES)
                val far = length - stem
                listOf(0.0 to 0.0, stem to 0.0, stem + far * cos(a) to side * far * sin(a))
            }
            TurnModifier.RIGHT, TurnModifier.LEFT, TurnModifier.SHARP_RIGHT, TurnModifier.SHARP_LEFT -> {
                val stem = TURN_STEM * length
                val r = TURN_RADIUS * length
                val pts = ArrayList<Pair<Double, Double>>()
                pts.add(0.0 to 0.0)
                pts.add(stem to 0.0)
                for (j in 1..TURN_SEGMENTS) {
                    val t = PI / 2.0 * j / TURN_SEGMENTS
                    pts.add(stem + r * sin(t) to side * r * (1.0 - cos(t)))
                }
                val last = pts.last()
                pts.add(last.first to last.second + side * (length - stem - r))
                pts
            }
            else -> continue
        }
        out.add(arrowRing(centre))
    }
    return out
}

/** One arrow along [centre] (its last point the tip): a stem of [LaneDrawing.ARROW_STEM_METERS] and a head at the tip. */
private fun arrowRing(centre: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    val tip = centre.last()
    val before = centre[centre.size - 2]
    val dx = tip.first - before.first
    val dy = tip.second - before.second
    val len = hypot(dx, dy)
    val ux = dx / len
    val uy = dy / len
    val headLength = LaneDrawing.ARROW_HEAD_LENGTH_METERS
    val base = (tip.first - ux * headLength) to (tip.second - uy * headLength)
    // The stem's centre line up to the head's base, offset to each side with mitred corners.
    val stem = ArrayList<Pair<Double, Double>>()
    var walked = 0.0
    val total = run {
        var t = 0.0
        for (i in 1 until centre.size) t += hypot(centre[i].first - centre[i - 1].first, centre[i].second - centre[i - 1].second)
        t
    }
    val stemLength = total - headLength
    stem.add(centre.first())
    for (i in 1 until centre.size) {
        val seg = hypot(centre[i].first - centre[i - 1].first, centre[i].second - centre[i - 1].second)
        if (walked + seg >= stemLength - 1e-9) {
            stem.add(base)
            break
        }
        walked += seg
        stem.add(centre[i])
    }
    val half = LaneDrawing.ARROW_STEM_METERS / 2.0
    val right = ArrayList<Pair<Double, Double>>()
    val left = ArrayList<Pair<Double, Double>>()
    for (i in stem.indices) {
        val (nx, ny, k) = mitre(stem, i)
        right.add((stem[i].first - nx * half * k) to (stem[i].second - ny * half * k))
        left.add((stem[i].first + nx * half * k) to (stem[i].second + ny * half * k))
    }
    val hw = LaneDrawing.ARROW_HEAD_WIDTH_METERS / 2.0
    // The normal to the head's way, left positive.
    val hx = -uy
    val hy = ux
    val ring = ArrayList<Pair<Double, Double>>()
    ring.addAll(right)
    ring.add((base.first - hx * hw) to (base.second - hy * hw))
    ring.add(tip)
    ring.add((base.first + hx * hw) to (base.second + hy * hw))
    for (i in left.indices.reversed()) ring.add(left[i])
    ring.add(ring.first())
    return ring
}

/** The unit normal (left) at [i] of [line] and the mitre's length factor there. */
private fun mitre(line: List<Pair<Double, Double>>, i: Int): Triple<Double, Double, Double> {
    fun normal(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = hypot(dx, dy)
        return (-dy / len) to (dx / len)
    }
    if (i == 0) return normal(line[0], line[1]).let { Triple(it.first, it.second, 1.0) }
    if (i == line.size - 1) return normal(line[i - 1], line[i]).let { Triple(it.first, it.second, 1.0) }
    val n1 = normal(line[i - 1], line[i])
    val n2 = normal(line[i], line[i + 1])
    val sx = n1.first + n2.first
    val sy = n1.second + n2.second
    val len = hypot(sx, sy)
    if (len < 1e-9) return Triple(n1.first, n1.second, 1.0)
    val nx = sx / len
    val ny = sy / len
    val cosHalf = nx * n1.first + ny * n1.second
    return Triple(nx, ny, 1.0 / max(MITRE_LIMIT, cosHalf))
}

private const val SLIGHT_STEM = 0.45
private const val SLIGHT_TURN_DEGREES = 20.0
private const val TURN_STEM = 0.42
private const val TURN_RADIUS = 0.20
private const val TURN_SEGMENTS = 6
private const val MITRE_LIMIT = 0.5
