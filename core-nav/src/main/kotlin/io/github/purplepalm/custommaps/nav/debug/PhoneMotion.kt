package io.github.purplepalm.custommaps.nav.debug

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The phone's own motion on freeways during guidance, for the "motion" object of the "progress"
 * line. Research only: whether lane changes show in the phone's turn rate and sideways
 * acceleration, read after a drive next to how far the phone's own positions lay to the side
 * ("match.raw"). Nothing on the screen uses it, and nothing here tells a lane. Numbers only: no
 * sample, series or position is kept or written, and each window's sums are forgotten when it is
 * taken.
 *
 * Vectors are in the phone's own axes, as Android's sensors report them: x to the right of the
 * screen, y up the screen, z out of the screen toward the person looking at it. The phone's back,
 * its camera side, is minus z, and its top edge plus y.
 */

/** A direction in the phone's own axes, as a unit vector: x to the right of the screen, y up the screen, z out of it. */
data class PhoneDirection(val x: Double, val y: Double, val z: Double) {

    /** The part of the vector [x], [y], [z] along this direction. */
    fun dot(x: Double, y: Double, z: Double): Double = this.x * x + this.y * y + this.z * z
}

/**
 * Which axis of the phone the motion object measures along, by its level direction: the phone's
 * back, or its top edge ([LevelFrame.of]).
 */
enum class MotionReference(val logName: String) {
    /** The back, the camera's side, which faces the road ahead in a windshield or vent mount. */
    BACK("back"),

    /** The top edge, for a phone that lies about flat. */
    TOP("top"),
}

/**
 * The level frame of the phone, from the gravity sensor's vector in the phone's own axes: [up];
 * [ahead], the level direction of the [reference] axis; [right], the level direction 90 degrees
 * clockwise of [ahead] seen from above; and [tiltDegrees], how far the reference axis points above
 * the level, negative below it. With the phone's back facing the road ahead, [ahead] is the car's
 * way within the mount's own small angle, and [right] the car's right.
 */
class LevelFrame private constructor(
    val up: PhoneDirection,
    val ahead: PhoneDirection,
    val right: PhoneDirection,
    val reference: MotionReference,
    val tiltDegrees: Double,
) {

    /**
     * The turn rate about the vertical, in degrees a second, clockwise seen from above (the way a
     * bearing grows: a right turn is positive), from a rate in radians a second about the phone's x,
     * y and z axes, each counterclockwise positive, as Android's gyroscope gives it.
     */
    fun yawDegrees(x: Double, y: Double, z: Double): Double = -Math.toDegrees(up.dot(x, y, z))

    /** The level part of a vector in the phone's axes, such as an acceleration, along [ahead]. */
    fun ahead(x: Double, y: Double, z: Double): Double = ahead.dot(x, y, z)

    /** The level part of a vector in the phone's axes along [right]: to the right positive. */
    fun right(x: Double, y: Double, z: Double): Double = right.dot(x, y, z)

    companion object {

        /**
         * The back is the reference axis while it points at most this far above or below the
         * level, and beyond that the top edge, which then lies flatter. Upright in portrait this is
         * whichever of the two lies flatter; in landscape the back stays the reference, since the
         * top edge then lies level whatever the phone's lean.
         */
        const val BACK_MAX_DEGREES = 45.0

        /**
         * The frame for the gravity vector [x], [y], [z], as the gravity sensor reads it: pointing up,
         * in metres a second squared or any other unit. Null for a vector without a length or with a
         * part that is no number.
         */
        fun of(x: Double, y: Double, z: Double): LevelFrame? {
            val length = sqrt(x * x + y * y + z * z)
            if (!(length > 0.0) || !length.isFinite()) return null
            val up = PhoneDirection(x / length, y / length, z / length)
            // How far each candidate axis points up: the back is minus z, the top edge plus y.
            val backUp = -up.z
            val back = abs(backUp) <= sin(Math.toRadians(BACK_MAX_DEGREES))
            val axisY = if (back) 0.0 else 1.0
            val axisZ = if (back) -1.0 else 0.0
            // Within [-1, 1]: the dot product of two unit vectors.
            val axisUp = (if (back) backUp else up.y).coerceIn(-1.0, 1.0)
            // The axis (no x part) less its vertical part. Never short: the axis taken points at
            // most 45 degrees off the level, since two axes at right angles cannot both point more
            // steeply.
            val levelX = -axisUp * up.x
            val levelY = axisY - axisUp * up.y
            val levelZ = axisZ - axisUp * up.z
            val levelLength = sqrt(levelX * levelX + levelY * levelY + levelZ * levelZ)
            val ahead = PhoneDirection(levelX / levelLength, levelY / levelLength, levelZ / levelLength)
            // Ahead crossed with up: 90 degrees clockwise of ahead, seen from above.
            val right = PhoneDirection(
                ahead.y * up.z - ahead.z * up.y,
                ahead.z * up.x - ahead.x * up.z,
                ahead.x * up.y - ahead.y * up.x,
            )
            return LevelFrame(
                up = up,
                ahead = ahead,
                right = right,
                reference = if (back) MotionReference.BACK else MotionReference.TOP,
                tiltDegrees = Math.toDegrees(asin(axisUp)),
            )
        }
    }
}

/**
 * The phone's motion over one window, from one "progress" line to the next, for the line's "motion"
 * object. Samples of the gyroscope, the gravity sensor and the linear acceleration sensor (the
 * acceleration less gravity) are only added to running sums: nothing of a sample is kept, and
 * nothing is allocated for one. [take] gives the window's summary and starts the next window. Not
 * thread-safe: where the sensors' thread adds and another thread takes, guard it with one lock.
 */
class PhoneMotion {

    private var gyroscopeSamples = 0
    private var firstNanos = 0L
    private var lastNanos = 0L
    private var rateX = 0.0
    private var rateY = 0.0
    private var rateZ = 0.0

    // The sums of the products of the rates, for the spread of the turn rate about the vertical.
    private var rateXX = 0.0
    private var rateYY = 0.0
    private var rateZZ = 0.0
    private var rateXY = 0.0
    private var rateXZ = 0.0
    private var rateYZ = 0.0

    private var gravitySamples = 0
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0

    private var linearSamples = 0
    private var linearX = 0.0
    private var linearY = 0.0
    private var linearZ = 0.0

    /**
     * One gyroscope sample at [nanos], the sample's own time: the rate in radians a second about the
     * phone's x, y and z axes, counterclockwise positive.
     */
    fun onGyroscope(nanos: Long, x: Float, y: Float, z: Float) {
        if (gyroscopeSamples == 0) firstNanos = nanos
        lastNanos = nanos
        gyroscopeSamples += 1
        val dx = x.toDouble()
        val dy = y.toDouble()
        val dz = z.toDouble()
        rateX += dx
        rateY += dy
        rateZ += dz
        rateXX += dx * dx
        rateYY += dy * dy
        rateZZ += dz * dz
        rateXY += dx * dy
        rateXZ += dx * dz
        rateYZ += dy * dz
    }

    /** One sample of the gravity sensor, which points up, in metres a second squared. */
    fun onGravity(x: Float, y: Float, z: Float) {
        gravitySamples += 1
        gravityX += x
        gravityY += y
        gravityZ += z
    }

    /** One sample of the linear acceleration sensor, gravity taken out, in metres a second squared. */
    fun onLinearAcceleration(x: Float, y: Float, z: Float) {
        linearSamples += 1
        linearX += x
        linearY += y
        linearZ += z
    }

    /**
     * The window's "motion" object, and the next window starts empty:
     * - `n`, the gyroscope's samples in the window;
     * - `ms`, from the first of them to the last, by the samples' own times;
     * - `yaw`, the mean turn rate about the vertical in degrees a second, clockwise seen from above
     *   (a right turn positive, the way a bearing grows), and `yawSd`, its standard deviation over
     *   the window's samples (about their mean, divided by their number);
     * - `fwd` and `side`, the mean acceleration in the level plane, gravity taken out, in metres a
     *   second squared, along the reference direction and 90 degrees clockwise of it (to the right
     *   positive, like the side of `match.raw`);
     * - `ref`, the reference axis ([MotionReference]), and `tilt`, how far it points above the level
     *   in whole degrees, negative below ([LevelFrame]).
     *
     * The level frame is that of the window's mean gravity. A field is null when a sensor it needs
     * gave nothing in the window: `ms` the gyroscope; `yaw` and `yawSd` the gyroscope and gravity;
     * `fwd` and `side` the linear acceleration and gravity; `ref` and `tilt` gravity. Turn rates and
     * accelerations have 2 decimals.
     */
    fun take(): Map<String, Any?> {
        val frame = if (gravitySamples > 0) {
            LevelFrame.of(gravityX / gravitySamples, gravityY / gravitySamples, gravityZ / gravitySamples)
        } else {
            null
        }
        val n = gyroscopeSamples
        var yaw: Double? = null
        var yawSd: Double? = null
        if (n > 0 && frame != null) {
            val up = frame.up
            val meanRate = up.dot(rateX / n, rateY / n, rateZ / n)
            // The mean square of the rate about up, from the sums of the products of its parts.
            val meanSquare = (
                up.x * up.x * rateXX + up.y * up.y * rateYY + up.z * up.z * rateZZ +
                    2.0 * (up.x * up.y * rateXY + up.x * up.z * rateXZ + up.y * up.z * rateYZ)
                ) / n
            yaw = frame.yawDegrees(rateX / n, rateY / n, rateZ / n)
            yawSd = Math.toDegrees(sqrt(max(0.0, meanSquare - meanRate * meanRate)))
        }
        var fwd: Double? = null
        var side: Double? = null
        if (linearSamples > 0 && frame != null) {
            fwd = frame.ahead(linearX / linearSamples, linearY / linearSamples, linearZ / linearSamples)
            side = frame.right(linearX / linearSamples, linearY / linearSamples, linearZ / linearSamples)
        }
        val fields = mapOf(
            "n" to n,
            "ms" to if (n > 0) Math.round((lastNanos - firstNanos) / NANOS_PER_MILLI) else null,
            "yaw" to yaw?.let(::rounded),
            "yawSd" to yawSd?.let(::rounded),
            "fwd" to fwd?.let(::rounded),
            "side" to side?.let(::rounded),
            "ref" to frame?.reference?.logName,
            "tilt" to frame?.tiltDegrees?.let { Math.round(it).toInt() },
        )
        clear()
        return fields
    }

    /** Forgets the window under way, as when the sensors start or stop. */
    fun clear() {
        gyroscopeSamples = 0
        firstNanos = 0L
        lastNanos = 0L
        rateX = 0.0
        rateY = 0.0
        rateZ = 0.0
        rateXX = 0.0
        rateYY = 0.0
        rateZZ = 0.0
        rateXY = 0.0
        rateXZ = 0.0
        rateYZ = 0.0
        gravitySamples = 0
        gravityX = 0.0
        gravityY = 0.0
        gravityZ = 0.0
        linearSamples = 0
        linearX = 0.0
        linearY = 0.0
        linearZ = 0.0
    }

    /**
     * When the phone's motion sensors are listened to: from the first progress update of a drive on
     * a freeway to the first one off it, and never after the drive or its navigator ended. A phone
     * that cannot listen is asked once a drive.
     */
    class Listening {

        /** What a progress update asks of the sensors. */
        enum class Step { START, STOP, NONE }

        /** True from a start that worked until the next stop or [end]. */
        var listening: Boolean = false
            private set

        /** True when the sensors could not start, or failed, in the drive under way. */
        private var refused = false

        /**
         * What to do for a progress update: [onFreeway] true while guidance runs and the car is on a
         * freeway.
         */
        fun onProgress(onFreeway: Boolean): Step = when {
            onFreeway && !listening && !refused -> Step.START
            !onFreeway && listening -> Step.STOP
            else -> Step.NONE
        }

        /** The answer to a [Step.START]: a phone whose sensors did not start is not asked again in this drive. */
        fun started(started: Boolean) {
            if (started) listening = true else refused = true
        }

        /** The sensors were stopped for a [Step.STOP]. */
        fun stopped() {
            listening = false
        }

        /** Listening failed: no more of it in this drive. */
        fun fail() {
            listening = false
            refused = true
        }

        /** The drive ended, or its navigator went: the sensors are to stop, and the next drive may start them again. */
        fun end() {
            listening = false
            refused = false
        }
    }

    companion object {

        /** The time between two samples the sensors are asked for, in microseconds: 25 a second. */
        const val SAMPLING_PERIOD_MICROS = 40_000

        /** The line written each time the sensors are asked to start. */
        const val SENSORS_TYPE = "motion_sensors"

        /**
         * The "motion_sensors" line: which of the three sensors the phone has, the sampling period
         * asked for, and whether listening [started]. No vendor, model or sensor name.
         */
        fun sensorsFields(gyroscope: Boolean, gravity: Boolean, linearAcceleration: Boolean, started: Boolean): Map<String, Any?> =
            mapOf(
                "gyroscope" to gyroscope,
                "gravity" to gravity,
                "linearAcceleration" to linearAcceleration,
                "periodUs" to SAMPLING_PERIOD_MICROS,
                "started" to started,
            )

        private const val NANOS_PER_MILLI = 1e6

        /** Two decimals, and never minus zero, which would only lengthen the line. */
        private fun rounded(value: Double): Double {
            val rounded = Motion.round(value, 2)
            return if (rounded == 0.0) 0.0 else rounded
        }
    }
}
