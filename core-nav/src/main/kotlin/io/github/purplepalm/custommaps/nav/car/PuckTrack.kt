package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.camera.Angles
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where the car map's puck is drawn at any moment between the updates of its location provider, so
 * that it moves on the car's frame clock instead of on Android's animators, which stop with the main
 * thread's frames ([CarFrameClock]). Each update says where the puck goes and how long it takes, as
 * a location provider tells Mapbox's location component:
 *
 * - **Positions** ([onPosition]): from where the puck is at that moment through the update's points,
 *   the last one the target, at one speed all the way, in the time the update gives; 0 puts it on the
 *   target at once. That is the glide of the navigation SDK's provider (its constant-velocity
 *   interpolator spends on each part of the way the share of the time that the part has of the
 *   distance, in degrees as the SDK measures it), and with one point the location component's own.
 *   A new update in the middle of a glide starts from where the puck is then.
 * - **Bearings** ([onBearing]): the same, turning the short way from one to the next. Bearings that
 *   come with the positions of the same update, one for each of its points and over the same time,
 *   are reached together with their points, as the SDK's provider has them; others are spread evenly
 *   over their time.
 *
 * The first position puts the puck there at once. Times are nanoseconds on the frame clock's clock.
 * [at] allocates nothing; the updates copy their points. Main thread only.
 */
class PuckTrack {

    /** The puck's position and bearing at the last [at]; NaN before the first position or bearing. */
    var latitude = Double.NaN
        private set
    var longitude = Double.NaN
        private set
    var bearing = Double.NaN
        private set

    private val position = Glide(values = 2)
    private val heading = Glide(values = 1)

    /** When the positions' glide started, and how long it takes, for bearings of the same update. */
    private var positionStartedAt = 0L
    private var positionNanos = 0L

    /** Scratch for the point the puck is at when an update comes. */
    private val now = DoubleArray(2)

    /**
     * A position update at [time]: the puck goes through the first [count] points of [latitudes] and
     * [longitudes] in [durationNanos], from where it is at [time]; at once for 0, and for the first.
     */
    fun onPosition(latitudes: DoubleArray, longitudes: DoubleArray, count: Int, durationNanos: Long, time: Long) {
        if (count <= 0) return
        val placed = position.started
        if (placed) {
            position.valueAt(time, now)
        } else {
            now[0] = latitudes[0]
            now[1] = longitudes[0]
        }
        position.begin(count + 1, time, if (placed) durationNanos.coerceAtLeast(0L) else 0L)
        position.set(0, 0, now[0])
        position.set(0, 1, now[1])
        for (index in 0 until count) {
            position.set(index + 1, 0, latitudes[index])
            position.set(index + 1, 1, longitudes[index])
        }
        position.timeByDistance()
        positionStartedAt = time
        positionNanos = durationNanos
    }

    /**
     * A bearing update at [time]: the puck turns through the first [count] of [bearings], in degrees
     * clockwise from north, in [durationNanos], the short way each time; at once for 0, and for the first.
     */
    fun onBearing(bearings: DoubleArray, count: Int, durationNanos: Long, time: Long) {
        if (count <= 0) return
        val placed = heading.started
        if (placed) heading.valueAt(time, now)
        // Bearings of the same update as the positions: on the positions' clock, reached with their points.
        val withThePositions = position.started && count + 1 == position.size &&
            durationNanos == positionNanos && abs(time - positionStartedAt) <= SAME_UPDATE_NANOS
        heading.begin(count + 1, if (withThePositions) positionStartedAt else time, if (placed) durationNanos.coerceAtLeast(0L) else 0L)
        var previous = if (placed) now[0] else bearings[0]
        heading.set(0, 0, previous)
        for (index in 0 until count) {
            // The short way round from the bearing before.
            previous = Angles.nearest(bearings[index], previous)
            heading.set(index + 1, 0, previous)
        }
        if (withThePositions) heading.timesOf(position) else heading.timeEvenly()
    }

    /**
     * Moves the puck to [time]: [latitude], [longitude] and [bearing] say where it is then. Returns
     * whether it still moves after [time].
     */
    fun at(time: Long): Boolean {
        if (position.started) {
            position.valueAt(time, now)
            latitude = now[0]
            longitude = now[1]
        }
        if (heading.started) {
            heading.valueAt(time, now)
            bearing = Angles.wrap360(now[0])
        }
        return position.moving(time) || heading.moving(time)
    }

    /**
     * A glide of points with [values] numbers each, through [size] points: from the first, where the
     * puck was, to the last, the target. Each point is reached at its share of the time.
     */
    private class Glide(private val values: Int) {
        var size = 0
            private set
        var started = false
            private set
        private var startedAt = 0L
        private var nanos = 0L
        private var points = DoubleArray(values * INITIAL_POINTS)

        /** When each point is reached, as a share of the time: 0 for the first, 1 for the last. */
        var shares = DoubleArray(INITIAL_POINTS)
            private set

        fun begin(size: Int, time: Long, nanos: Long) {
            this.size = size
            started = true
            startedAt = time
            this.nanos = nanos
            if (points.size < size * values) points = DoubleArray(size * values * 2)
            if (shares.size < size) shares = DoubleArray(size * 2)
        }

        fun set(point: Int, value: Int, number: Double) {
            points[point * values + value] = number
        }

        /** Each part of the way takes its share of the distance, in degrees: one speed all the way. */
        fun timeByDistance() {
            var total = 0.0
            shares[0] = 0.0
            for (point in 1 until size) {
                total += hypot(points[point * values] - points[(point - 1) * values], points[point * values + 1] - points[(point - 1) * values + 1])
                shares[point] = total
            }
            if (total > 0.0) {
                for (point in 1 until size) shares[point] /= total
            } else {
                timeEvenly()
            }
        }

        fun timeEvenly() {
            for (point in 0 until size) shares[point] = point.toDouble() / (size - 1).coerceAtLeast(1)
        }

        /** The same shares of the time as [other], which has as many points. */
        fun timesOf(other: Glide) {
            for (point in 0 until size) shares[point] = other.shares[point]
        }

        /** Writes the glide's numbers at [time] into [into]. */
        fun valueAt(time: Long, into: DoubleArray) {
            val share = if (nanos <= 0L) 1.0 else ((time - startedAt).toDouble() / nanos).coerceIn(0.0, 1.0)
            var point = 1
            while (point < size - 1 && shares[point] < share) point += 1
            val from = shares[point - 1]
            val span = shares[point] - from
            val along = if (span <= 0.0) 1.0 else ((share - from) / span).coerceIn(0.0, 1.0)
            for (value in 0 until values) {
                val a = points[(point - 1) * values + value]
                val b = points[point * values + value]
                into[value] = a + (b - a) * along
            }
        }

        /** Whether the glide still changes after [time]: under way, and not from a place to the same place. */
        fun moving(time: Long): Boolean {
            if (!started || nanos <= 0L || time >= startedAt + nanos) return false
            for (point in 1 until size) {
                for (value in 0 until values) {
                    if (points[point * values + value] != points[value]) return true
                }
            }
            return false
        }
    }

    private companion object {
        const val INITIAL_POINTS = 8

        /** A bearing update this close in time to the positions' is of the same update. */
        const val SAME_UPDATE_NANOS = 2_000_000L
    }
}
