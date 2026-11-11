package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.NetworkRestriction
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import io.github.purplepalm.custommaps.nav.geo.Geo

/**
 * What is downloaded when a route preview opens: the street map along the first part of every
 * previewed route, so that a drive that loses its connection still has map under it. Map tiles
 * only. Road data is left to guidance, whose requests are part of the trip.
 */
internal object PreviewDownload {

    /**
     * The one tile region the app has. Every preview loads it again under this name with its own
     * routes, which takes the place of what the preview before asked for.
     */
    const val REGION_ID = "preview-routes"

    /** The style the phone map shows, Mapbox Standard. */
    const val STYLE = Style.STANDARD

    /** Both bands of the map that guidance keeps ahead ([OfflineAreas]), as one range. */
    val ZOOMS = ZoomBand(min = OfflineAreas.TOWN_ZOOMS.min, max = OfflineAreas.STREET_ZOOMS.max)

    /**
     * Which networks the download may use: for now any, mobile data included, so routes planned on
     * the road are covered too. Whether it should wait for a network that costs nothing is an open
     * question to the owner; a setting may take the place of this constant, and
     * [NetworkRestriction.DISALLOW_EXPENSIVE] is the value that waits.
     */
    val NETWORK: NetworkRestriction = NetworkRestriction.NONE

    /**
     * Along a route one point is kept at least this often. A route has a point every few meters,
     * tens of thousands on a long one, and the region is handed to the tile store on the main
     * thread, point by point. The smallest tile asked for is hundreds of meters wide, so a line
     * that strays this far from the road still crosses the same tiles, give or take a corner.
     */
    const val KEEP_EVERY_METERS = 100.0

    /**
     * How far along each route, from its start, the region reaches: the first 100 km. Mapbox
     * refuses a region whole when it would take the phone past 750 tile packs (its squares of
     * zoom 11 and 12, counted for each tileset of the style), so without a limit a long trip's
     * preview would save nothing at all, not even its first kilometers, on any network. The first
     * part of a route is the part driven first; during guidance predictive caching keeps the rest
     * ahead. Counted by those squares, three routes of this length stay below the limit even for a
     * style of four tilesets. A first setting: the phone's `offline_region` lines show what a
     * region really needs.
     */
    const val MAX_ROUTE_METERS = 100_000.0

    /**
     * One route as the region takes it: [line], its points with fewer of them, [routeMeters], the
     * length of the whole route, and [keptMeters], how far along the route [line] reaches.
     */
    data class KeptRoute(val line: List<Point>, val routeMeters: Double, val keptMeters: Double)

    /**
     * [line] with fewer points, and no further than [maxMeters] along it: the first point, each
     * one that is [KEEP_EVERY_METERS] or more from the one kept before it, and the last. Every
     * point left out before the end lies within that distance of a point that was kept. A route
     * longer than [maxMeters] ends at its first point at or past that length, which is then the
     * last point kept.
     */
    fun thin(line: List<Point>, maxMeters: Double = MAX_ROUTE_METERS): KeptRoute {
        if (line.isEmpty()) return KeptRoute(line, routeMeters = 0.0, keptMeters = 0.0)
        val kept = ArrayList<Point>()
        var last = line.first()
        kept += last
        // Meters along the route to the point at hand, and to the end of what is kept.
        var along = 0.0
        var keptMeters = -1.0
        for (index in 1..line.lastIndex) {
            val point = line[index]
            along += meters(line[index - 1], point)
            when {
                // Past the end of what is kept, only the route's length is still counted.
                keptMeters >= 0.0 -> Unit
                along >= maxMeters -> {
                    kept += point
                    keptMeters = along
                }
                index == line.lastIndex || meters(last, point) >= KEEP_EVERY_METERS -> {
                    kept += point
                    last = point
                }
            }
        }
        return KeptRoute(kept, routeMeters = along, keptMeters = if (keptMeters >= 0.0) keptMeters else along)
    }

    /**
     * The region's shape for a preview: [geometry], one line for each route, and [routes], each
     * route's lengths, whole and as kept, in the same order.
     */
    class Shape(val geometry: MultiLineString, val routes: List<KeptRoute>)

    /**
     * The lines of [routes] as the region's shape, each thinned and cut at [MAX_ROUTE_METERS];
     * null when no route has a line of two points or more. Reads every point of every route: call
     * it off the main thread.
     */
    fun geometry(routes: List<List<Point>>): Shape? {
        val kept = routes.filter { it.size >= 2 }.map { thin(it) }
        return if (kept.isEmpty()) null else Shape(MultiLineString.fromLngLats(kept.map { it.line }), kept)
    }

    private fun meters(a: Point, b: Point): Double = Geo.meters(a.latitude(), a.longitude(), b.latitude(), b.longitude())
}
