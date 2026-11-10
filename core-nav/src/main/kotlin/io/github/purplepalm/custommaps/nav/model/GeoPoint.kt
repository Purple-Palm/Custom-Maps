package io.github.purplepalm.custommaps.nav.model

import io.github.purplepalm.custommaps.nav.geo.Geo

/** A WGS 84 coordinate in degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "Latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range: $longitude" }
    }

    /** The great-circle distance to [other] ([Geo.meters]). */
    fun distanceTo(other: GeoPoint): Distance = Distance.meters(Geo.meters(this, other))
}
