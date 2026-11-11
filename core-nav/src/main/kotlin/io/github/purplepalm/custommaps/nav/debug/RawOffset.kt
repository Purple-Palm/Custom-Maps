package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the phone's own position lay against the one the navigator matched to the road, for the
 * "raw" object inside the "match" fields of the debug log. The navigator puts the car on a road of
 * the route, a little ahead of where the phone measured it; how far to the side the phone's fix
 * lies tells, after a drive, whether the car stayed on the freeway while the navigator had it on a
 * ramp, and how far fixes stray on a ramp that was taken. Numbers only: no position is written here.
 */
object RawOffset {

    /** Below this speed the direction of a fix is noise, and the two directions are not compared. */
    const val MIN_BEARING_SPEED_MPS = 3.0

    /**
     * The "raw" fields: `sideM`, how far the phone's fix lies across the matched direction of
     * travel, to the right positive; `aheadM`, how far along it, ahead positive; `accM`, the fix's
     * accuracy; `ageMs`, [ageMillis]; `bearingDiff`, the fix's direction less the matched one
     * ([bearingDifference]). Each null where it cannot be told: the offsets without a matched
     * direction, the difference without either direction or below [MIN_BEARING_SPEED_MPS].
     *
     * @param rawSpeedMps the speed the phone measured with its fix
     * @param ageMillis how old the phone's fix was when the matched position came; null when its
     *   time is not the phone's own, as for a simulated drive
     */
    fun fields(
        rawLatitude: Double,
        rawLongitude: Double,
        rawBearing: Double?,
        rawSpeedMps: Double?,
        rawAccuracyMeters: Double?,
        matchedLatitude: Double,
        matchedLongitude: Double,
        matchedBearing: Double?,
        ageMillis: Long?,
    ): Map<String, Any?> {
        val heading = matchedBearing?.takeIf { it.isFinite() }?.let { Math.toRadians(it) }
        var side: Double? = null
        var ahead: Double? = null
        if (heading != null) {
            val raw = LocalMeters(matchedLatitude, matchedLongitude).toLocal(rawLatitude, rawLongitude)
            // East and north into right and forward of a heading measured clockwise from north.
            side = raw.x * cos(heading) - raw.y * sin(heading)
            ahead = raw.x * sin(heading) + raw.y * cos(heading)
        }
        return mapOf(
            "sideM" to side?.let { Motion.round(it, 1) },
            "aheadM" to ahead?.let { Motion.round(it, 1) },
            "accM" to rawAccuracyMeters?.let { Motion.round(it, 1) },
            "ageMs" to ageMillis,
            "bearingDiff" to bearingDifference(rawBearing, matchedBearing, rawSpeedMps)?.let { Motion.round(it, 1) },
        )
    }

    /**
     * [raw] less [matched], the short way round: more than -180 and up to 180 degrees. Null without
     * either direction, and for a fix slower than [MIN_BEARING_SPEED_MPS] or without a speed.
     */
    fun bearingDifference(raw: Double?, matched: Double?, rawSpeedMps: Double?): Double? {
        if (raw == null || matched == null || !raw.isFinite() || !matched.isFinite()) return null
        if (rawSpeedMps == null || !(rawSpeedMps >= MIN_BEARING_SPEED_MPS)) return null
        var difference = (raw - matched) % FULL_TURN
        if (difference > HALF_TURN) difference -= FULL_TURN
        if (difference <= -HALF_TURN) difference += FULL_TURN
        return difference
    }

    private const val FULL_TURN = 360.0
    private const val HALF_TURN = 180.0
}
