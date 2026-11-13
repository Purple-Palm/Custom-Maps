package io.github.purplepalm.custommaps.nav.car

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The cap on the car map's frame rate. Mapbox paces the frames of the map on the car's surface
 * against the display rate it believes that surface has, 60 Hz (the car app library's own virtual
 * display), while the map's render thread is called at the phone's display rate. A cap of 30 so draws
 * 60 frames a second on a phone at 120 Hz, while Mapbox's own estimate still reads 30. So the cap
 * the car map sets is scaled by the rate at which the display's frames really come:
 * `round(30 x 60 / rate)`, within 1 and 60, for a real 30 frames a second. While the frame clock runs
 * on its timer the main thread has no display rate to measure, and 60 is taken. The cap is set again
 * only when the rate moves by more than a tenth.
 */
object CarFrameRate {

    /** The frames a second the car's map draws. */
    const val TARGET_FPS = 30

    /** The display rate the map library believes the car's surface has, and the rate taken on the timer. */
    const val ASSUMED_HZ = 60.0

    const val MIN_FPS = 1
    const val MAX_FPS = 60

    /** A change of the rate by more than this share sets the cap again. */
    const val CHANGE_SHARE = 0.10

    /** The cap to set for a display that sends its frames at [displayHz]. */
    fun maxFps(displayHz: Double): Int {
        if (!(displayHz > 0.0) || displayHz.isInfinite()) return TARGET_FPS
        return (TARGET_FPS * ASSUMED_HZ / displayHz).roundToInt().coerceIn(MIN_FPS, MAX_FPS)
    }

    /** Whether a cap set for [appliedHz], or none yet set for a measured rate, is set again for [displayHz]. */
    fun changed(appliedHz: Double?, displayHz: Double): Boolean =
        appliedHz == null || abs(displayHz - appliedHz) > CHANGE_SHARE * appliedHz

    /** The display rate to cap for: [vsyncHz] as measured from the display's frames, [ASSUMED_HZ] while the clock runs on its timer. */
    fun displayHz(source: CarFrameClock.Source, vsyncHz: Double?): Double? =
        if (source == CarFrameClock.Source.TIMER) ASSUMED_HZ else vsyncHz
}

/**
 * How fast the display sends its frames to the main thread, measured from the frame times of
 * display callbacks posted one right after the other: the lower quartile of the last [SAMPLES]
 * intervals between them. A frame the main thread misses only makes an interval longer, so the
 * lower quartile is the display's period also when a few of four are missed; the few intervals
 * shortened by the display's own timing fall below it. Nothing here allocates. Main thread only.
 */
class VsyncRate {

    private val intervals = LongArray(SAMPLES)
    private val sorted = LongArray(SAMPLES)
    private var count = 0
    private var next = 0
    private var lastFrame = Long.MIN_VALUE

    /** A display frame at [frameTimeNanos]; [backToBack] when its callback was asked for in the frame before. */
    fun onFrame(frameTimeNanos: Long, backToBack: Boolean) {
        val last = lastFrame
        lastFrame = frameTimeNanos
        if (!backToBack || last == Long.MIN_VALUE) return
        val interval = frameTimeNanos - last
        if (interval < SHORTEST_NANOS || interval > LONGEST_NANOS) return
        intervals[next] = interval
        next = (next + 1) % SAMPLES
        if (count < SAMPLES) count += 1
    }

    /** The display's rate in hertz, or null before [MIN_SAMPLES] intervals were measured. */
    fun hz(): Double? {
        if (count < MIN_SAMPLES) return null
        for (index in 0 until count) sorted[index] = intervals[index]
        // Insertion sort of at most SAMPLES numbers: no allocation, once a second.
        for (index in 1 until count) {
            val value = sorted[index]
            var at = index - 1
            while (at >= 0 && sorted[at] > value) {
                sorted[at + 1] = sorted[at]
                at -= 1
            }
            sorted[at + 1] = value
        }
        return NANOS_PER_SECOND / sorted[(count - 1) / 4]
    }

    /** Forgets every interval, as for a new surface. */
    fun reset() {
        count = 0
        next = 0
        lastFrame = Long.MIN_VALUE
    }

    companion object {
        /** Intervals kept: a quarter of a second at 60 Hz, an eighth at 120. */
        const val SAMPLES = 16

        /** Intervals measured before there is a rate. */
        const val MIN_SAMPLES = 8

        /** No display sends frames faster than 250 a second or, while it sends them, slower than 10 a second. */
        const val SHORTEST_NANOS = 4_000_000L
        const val LONGEST_NANOS = 100_000_000L

        private const val NANOS_PER_SECOND = 1e9
    }
}
