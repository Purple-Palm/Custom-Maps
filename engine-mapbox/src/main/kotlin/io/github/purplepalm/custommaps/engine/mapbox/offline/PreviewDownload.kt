package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.NetworkRestriction
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import io.github.purplepalm.custommaps.nav.geo.Geo

/**
 * What is downloaded when a route preview opens: the street map along every previewed route, so
 * that a drive that loses its connection still has map under it. Map tiles only. Road data is left
 * to guidance, whose requests are part of the trip.
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
     * [line] with fewer points: the first, each one that is [KEEP_EVERY_METERS] or more from the
     * one kept before it, and the last. Every point left out lies within that distance of a point
     * that was kept.
     */
    fun thin(line: List<Point>): List<Point> {
        if (line.size <= 2) return line
        val kept = ArrayList<Point>()
        var last = line.first()
        kept += last
        for (index in 1 until line.lastIndex) {
            val point = line[index]
            if (Geo.meters(last.latitude(), last.longitude(), point.latitude(), point.longitude()) >= KEEP_EVERY_METERS) {
                kept += point
                last = point
            }
        }
        kept += line.last()
        return kept
    }

    /**
     * The lines of [routes] as the region's shape, each thinned; null when no route has a line of
     * two points or more. Reads every point of every route: call it off the main thread.
     */
    fun geometry(routes: List<List<Point>>): MultiLineString? {
        val lines = routes.filter { it.size >= 2 }.map(::thin)
        return if (lines.isEmpty()) null else MultiLineString.fromLngLats(lines)
    }
}
