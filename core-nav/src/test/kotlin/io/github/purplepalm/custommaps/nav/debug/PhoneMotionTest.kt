package io.github.purplepalm.custommaps.nav.debug

import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone's motion on synthetic vectors: a phone in made-up poses in a car that turns right at
 * 2 degrees a second or is pulled forward or to its right, as its sensors would report it. The car's
 * own frame is right, forward and up; a pose says where the phone's x (right of the screen), y (up
 * the screen) and z (out of the screen) axes point in it.
 */
class PhoneMotionTest {

    /** A direction in the car's frame: to its right, forward, up. */
    private data class Car(val right: Double, val forward: Double, val up: Double) {
        fun dot(other: Car) = right * other.right + forward * other.forward + up * other.up
    }

    /** Where the phone's axes point in the car. */
    private class Pose(val name: String, val x: Car, val y: Car, val z: Car) {
        /** The car's vector ([right], [forward], [up]) in the phone's own axes, as its sensors read it. */
        fun phone(right: Double, forward: Double, up: Double): DoubleArray {
            val vector = Car(right, forward, up)
            return doubleArrayOf(vector.dot(x), vector.dot(y), vector.dot(z))
        }

        override fun toString() = name
    }

    private fun rad(degrees: Double) = Math.toRadians(degrees)

    /** Upright in portrait with its screen to the driver, leaning back by [degrees]: its top edge goes forward. */
    private fun leaningBack(degrees: Double) = Pose(
        "leaning back $degrees degrees",
        x = Car(1.0, 0.0, 0.0),
        y = Car(0.0, sin(rad(degrees)), cos(rad(degrees))),
        z = Car(0.0, -cos(rad(degrees)), sin(rad(degrees))),
    )

    private val flat = Pose("flat, screen up, top edge ahead", Car(1.0, 0.0, 0.0), Car(0.0, 1.0, 0.0), Car(0.0, 0.0, 1.0))
    private val upright = leaningBack(0.0)
    private val leaningBack30 = leaningBack(30.0)

    /** Turned 90 degrees in its mount: landscape, the top edge to the left, the screen still to the driver. */
    private val landscape = Pose("landscape", Car(0.0, 0.0, 1.0), Car(-1.0, 0.0, 0.0), Car(0.0, -1.0, 0.0))

    /** In landscape and leaning back 15 degrees, so that its top edge lies level and its back does not. */
    private val landscapeLeaningBack = Pose(
        "landscape leaning back 15 degrees",
        x = Car(0.0, sin(rad(15.0)), cos(rad(15.0))),
        y = Car(-1.0, 0.0, 0.0),
        z = Car(0.0, -cos(rad(15.0)), sin(rad(15.0))),
    )

    private val poses = listOf(flat, upright, leaningBack30, landscape, landscapeLeaningBack)

    private fun frame(pose: Pose): LevelFrame {
        val gravity = pose.phone(0.0, 0.0, GRAVITY)
        return requireNotNull(LevelFrame.of(gravity[0], gravity[1], gravity[2]))
    }

    private fun assertDirection(expected: DoubleArray, actual: PhoneDirection, what: String) {
        assertEquals(expected[0], actual.x, 1e-9, "$what x")
        assertEquals(expected[1], actual.y, 1e-9, "$what y")
        assertEquals(expected[2], actual.z, 1e-9, "$what z")
    }

    /** [samples] gyroscope samples 40 ms apart from [startNanos], each of [rate] (a vector in the phone's axes, radians a second). */
    private fun PhoneMotion.gyroscope(rate: DoubleArray, samples: Int = 25, startNanos: Long = 1_000_000_000L) {
        repeat(samples) { onGyroscope(startNanos + it * 40_000_000L, rate[0].toFloat(), rate[1].toFloat(), rate[2].toFloat()) }
    }

    private fun PhoneMotion.gravity(vector: DoubleArray, samples: Int = 25) {
        repeat(samples) { onGravity(vector[0].toFloat(), vector[1].toFloat(), vector[2].toFloat()) }
    }

    private fun PhoneMotion.linear(vector: DoubleArray, samples: Int = 25) {
        repeat(samples) { onLinearAcceleration(vector[0].toFloat(), vector[1].toFloat(), vector[2].toFloat()) }
    }

    // ------------------------------------------------------------------ the level frame

    @Test
    fun `a phone upright in its mount looks out of its back, with its right to the car's right`() {
        val frame = frame(upright)
        assertEquals(MotionReference.BACK, frame.reference)
        assertEquals(0.0, frame.tiltDegrees, 1e-9)
        assertDirection(doubleArrayOf(0.0, 1.0, 0.0), frame.up, "up: the top edge")
        assertDirection(doubleArrayOf(0.0, 0.0, -1.0), frame.ahead, "ahead: the back")
        assertDirection(doubleArrayOf(1.0, 0.0, 0.0), frame.right, "right: the screen's right")
    }

    @Test
    fun `a phone lying flat looks along its top edge`() {
        val frame = frame(flat)
        assertEquals(MotionReference.TOP, frame.reference)
        assertEquals(0.0, frame.tiltDegrees, 1e-9)
        assertDirection(doubleArrayOf(0.0, 1.0, 0.0), frame.ahead, "ahead: the top edge")
        assertDirection(doubleArrayOf(1.0, 0.0, 0.0), frame.right, "right")
    }

    @Test
    fun `a phone leaning back 30 degrees looks out of its back, 30 degrees below the level`() {
        val frame = frame(leaningBack30)
        assertEquals(MotionReference.BACK, frame.reference)
        assertEquals(-30.0, frame.tiltDegrees, 1e-9)
        // The car's way ahead, in the phone's axes.
        assertDirection(leaningBack30.phone(0.0, 1.0, 0.0), frame.ahead, "ahead")
        assertDirection(leaningBack30.phone(1.0, 0.0, 0.0), frame.right, "right")
    }

    @Test
    fun `a phone turned 90 degrees in its mount still looks out of its back`() {
        for (pose in listOf(landscape, landscapeLeaningBack)) {
            val frame = frame(pose)
            assertEquals(MotionReference.BACK, frame.reference, "$pose: its top edge lies level, but faces the side")
            assertDirection(pose.phone(0.0, 1.0, 0.0), frame.ahead, "$pose ahead")
            assertDirection(pose.phone(1.0, 0.0, 0.0), frame.right, "$pose right")
        }
        assertEquals(-15.0, frame(landscapeLeaningBack).tiltDegrees, 1e-9)
    }

    @Test
    fun `the back is the reference up to 45 degrees off the level, the top edge beyond`() {
        assertEquals(45.0, LevelFrame.BACK_MAX_DEGREES)
        val at44 = frame(leaningBack(44.0))
        assertEquals(MotionReference.BACK, at44.reference)
        assertEquals(-44.0, at44.tiltDegrees, 1e-9)
        val at46 = frame(leaningBack(46.0))
        assertEquals(MotionReference.TOP, at46.reference)
        assertEquals(44.0, at46.tiltDegrees, 1e-9, "the top edge, 44 degrees above the level")
        // Either way the reference lies along the car's way.
        assertDirection(leaningBack(46.0).phone(0.0, 1.0, 0.0), at46.ahead, "ahead at 46 degrees")
    }

    @Test
    fun `up, ahead and right are at right angles, right clockwise of ahead, in any pose`() {
        for (lean in -80..80 step 20) {
            for (roll in -60..60 step 30) {
                // A phone leaning back by lean, then rolled about its screen's normal by roll.
                val l = rad(lean.toDouble())
                val r = rad(roll.toDouble())
                val y0 = Car(0.0, sin(l), cos(l))
                val z = Car(0.0, -cos(l), sin(l))
                val x0 = Car(1.0, 0.0, 0.0)
                val x = Car(x0.right * cos(r) + y0.right * sin(r), x0.forward * cos(r) + y0.forward * sin(r), x0.up * cos(r) + y0.up * sin(r))
                val y = Car(y0.right * cos(r) - x0.right * sin(r), y0.forward * cos(r) - x0.forward * sin(r), y0.up * cos(r) - x0.up * sin(r))
                val pose = Pose("lean $lean roll $roll", x, y, z)
                val frame = frame(pose)
                val up = frame.up
                val ahead = frame.ahead
                val right = frame.right
                assertEquals(0.0, up.dot(ahead.x, ahead.y, ahead.z), 1e-9, "$pose ahead is level")
                assertEquals(0.0, up.dot(right.x, right.y, right.z), 1e-9, "$pose right is level")
                assertEquals(0.0, ahead.dot(right.x, right.y, right.z), 1e-9, "$pose right is across ahead")
                assertEquals(1.0, ahead.dot(ahead.x, ahead.y, ahead.z), 1e-9, "$pose ahead is a unit vector")
                // Seen from above (up toward the eye), right lies clockwise of ahead: ahead x right points down.
                val down = doubleArrayOf(
                    ahead.y * right.z - ahead.z * right.y,
                    ahead.z * right.x - ahead.x * right.z,
                    ahead.x * right.y - ahead.y * right.x,
                )
                assertEquals(-1.0, up.dot(down[0], down[1], down[2]), 1e-9, "$pose clockwise")
            }
        }
    }

    @Test
    fun `a gravity vector without a length or with no number gives no frame`() {
        assertNull(LevelFrame.of(0.0, 0.0, 0.0))
        assertNull(LevelFrame.of(Double.NaN, 0.0, 9.8))
        assertNull(LevelFrame.of(0.0, Double.POSITIVE_INFINITY, 9.8))
        assertNotNull(LevelFrame.of(0.0, 0.0, 1e-3), "any length will do: only the direction counts")
    }

    // ------------------------------------------------------------------ the turn rate and the pulls

    @Test
    fun `a steady right turn of 2 degrees a second is a yaw of +2 in every pose`() {
        for (pose in poses) {
            val motion = PhoneMotion()
            motion.gravity(pose.phone(0.0, 0.0, GRAVITY))
            // Clockwise seen from above is a rotation about down, counterclockwise about up negative.
            motion.gyroscope(pose.phone(0.0, 0.0, -rad(2.0)))
            val fields = motion.take()
            assertEquals(2.0, fields["yaw"], "$pose")
            assertEquals(0.0, fields["yawSd"], "$pose")
        }
    }

    @Test
    fun `a left turn is a negative yaw, and a roll or pitch of the phone is none`() {
        val motion = PhoneMotion()
        motion.gravity(upright.phone(0.0, 0.0, GRAVITY))
        motion.gyroscope(upright.phone(0.0, 0.0, rad(3.5)))
        assertEquals(-3.5, motion.take()["yaw"])
        motion.gravity(upright.phone(0.0, 0.0, GRAVITY))
        // About the car's forward and right axes: the car rocking, nothing about the vertical.
        motion.gyroscope(upright.phone(rad(5.0), rad(5.0), 0.0))
        assertEquals(0.0, motion.take()["yaw"])
    }

    @Test
    fun `a forward pull is fwd only and a pull to the right side only, in every pose`() {
        for (pose in poses) {
            val forward = PhoneMotion()
            forward.gravity(pose.phone(0.0, 0.0, GRAVITY))
            forward.linear(pose.phone(0.0, 1.5, 0.0))
            val pulled = forward.take()
            assertEquals(1.5, pulled["fwd"], "$pose")
            assertEquals(0.0, pulled["side"], "$pose")

            val right = PhoneMotion()
            right.gravity(pose.phone(0.0, 0.0, GRAVITY))
            right.linear(pose.phone(1.5, 0.0, 0.0))
            val turned = right.take()
            assertEquals(0.0, turned["fwd"], "$pose")
            assertEquals(1.5, turned["side"], "$pose: to the right positive, like match.raw's sideM")
        }
    }

    @Test
    fun `a right turn pulls to the right side and braking backwards`() {
        val motion = PhoneMotion()
        motion.gravity(leaningBack30.phone(0.0, 0.0, GRAVITY))
        // 30 m/s turning right at 2 degrees a second: 1.05 m/s² toward the turn's inside; braking at 0.8.
        val sideways = 30.0 * rad(2.0)
        motion.gyroscope(leaningBack30.phone(0.0, 0.0, -rad(2.0)))
        motion.linear(leaningBack30.phone(sideways, -0.8, 0.0))
        val fields = motion.take()
        assertEquals(2.0, fields["yaw"])
        assertEquals(1.05, fields["side"])
        assertEquals(-0.8, fields["fwd"])
    }

    @Test
    fun `a bump up or down is no level acceleration`() {
        for (pose in poses) {
            val motion = PhoneMotion()
            motion.gravity(pose.phone(0.0, 0.0, GRAVITY))
            motion.linear(pose.phone(0.0, 0.0, 2.0))
            val fields = motion.take()
            assertEquals(0.0, fields["fwd"], "$pose")
            assertEquals(0.0, fields["side"], "$pose")
        }
    }

    // ------------------------------------------------------------------ the window

    @Test
    fun `the window gives its samples, their time, the reference and the tilt`() {
        val motion = PhoneMotion()
        motion.gravity(leaningBack30.phone(0.0, 0.0, GRAVITY))
        motion.gyroscope(leaningBack30.phone(0.0, 0.0, -rad(0.5)))
        motion.linear(leaningBack30.phone(0.2, 0.3, 0.0))
        val fields = motion.take()
        assertEquals(listOf("n", "ms", "yaw", "yawSd", "fwd", "side", "ref", "tilt"), fields.keys.toList())
        assertEquals(25, fields["n"])
        assertEquals(960L, fields["ms"], "24 gaps of 40 ms, by the samples' own times")
        assertEquals(0.5, fields["yaw"])
        assertEquals(0.3, fields["fwd"])
        assertEquals(0.2, fields["side"])
        assertEquals("back", fields["ref"])
        assertEquals(-30, fields["tilt"])
    }

    @Test
    fun `the tilt is rounded to whole degrees`() {
        for ((lean, tilt) in listOf(12.4 to -12, 12.6 to -13, 0.4 to 0)) {
            val motion = PhoneMotion()
            motion.gravity(leaningBack(lean).phone(0.0, 0.0, GRAVITY))
            assertEquals(tilt, motion.take()["tilt"], "leaning back $lean degrees")
        }
    }

    @Test
    fun `an empty window gives n 0 and nulls`() {
        val fields = PhoneMotion().take()
        assertEquals(0, fields["n"])
        for (key in listOf("ms", "yaw", "yawSd", "fwd", "side", "ref", "tilt")) assertNull(fields[key], key)
    }

    @Test
    fun `each take forgets the window and starts the next`() {
        val motion = PhoneMotion()
        motion.gravity(upright.phone(0.0, 0.0, GRAVITY))
        motion.gyroscope(upright.phone(0.0, 0.0, -rad(2.0)))
        motion.linear(upright.phone(0.0, 1.0, 0.0))
        assertEquals(25, motion.take()["n"])
        val next = motion.take()
        assertEquals(0, next["n"])
        assertNull(next["yaw"], "no value of the window before")
        assertNull(next["fwd"])
        assertNull(next["ref"])
        motion.gyroscope(upright.phone(0.0, 0.0, -rad(1.0)), samples = 1, startNanos = 5_000_000_000L)
        val one = motion.take()
        assertEquals(1, one["n"])
        assertEquals(0L, one["ms"], "one sample spans no time")
    }

    @Test
    fun `clear forgets the window under way`() {
        val motion = PhoneMotion()
        motion.gravity(upright.phone(0.0, 0.0, GRAVITY))
        motion.gyroscope(upright.phone(0.0, 0.0, -rad(2.0)))
        motion.clear()
        assertEquals(0, motion.take()["n"])
    }

    @Test
    fun `a field is null when a sensor it needs gave nothing in the window`() {
        val gyroscopeOnly = PhoneMotion()
        gyroscopeOnly.gyroscope(upright.phone(0.0, 0.0, -rad(2.0)))
        val noGravity = gyroscopeOnly.take()
        assertEquals(25, noGravity["n"])
        assertEquals(960L, noGravity["ms"])
        for (key in listOf("yaw", "yawSd", "fwd", "side", "ref", "tilt")) assertNull(noGravity[key], "$key without gravity")

        val noLinear = PhoneMotion()
        noLinear.gravity(upright.phone(0.0, 0.0, GRAVITY))
        noLinear.gyroscope(upright.phone(0.0, 0.0, -rad(2.0)))
        val withoutLinear = noLinear.take()
        assertEquals(2.0, withoutLinear["yaw"])
        assertNull(withoutLinear["fwd"])
        assertNull(withoutLinear["side"])
        assertEquals("back", withoutLinear["ref"])
        assertEquals(0, withoutLinear["tilt"])

        val noGyroscope = PhoneMotion()
        noGyroscope.gravity(upright.phone(0.0, 0.0, GRAVITY))
        noGyroscope.linear(upright.phone(0.0, 1.0, 0.0))
        val withoutGyroscope = noGyroscope.take()
        assertEquals(0, withoutGyroscope["n"])
        assertNull(withoutGyroscope["ms"])
        assertNull(withoutGyroscope["yaw"])
        assertNull(withoutGyroscope["yawSd"])
        assertEquals(1.0, withoutGyroscope["fwd"])
    }

    @Test
    fun `yawSd is the spread of the turn rate about its mean`() {
        val motion = PhoneMotion()
        motion.gravity(leaningBack30.phone(0.0, 0.0, GRAVITY))
        // Alternately 1 and 3 degrees a second to the right: a mean of 2, each sample 1 from it.
        repeat(10) {
            val rate = leaningBack30.phone(0.0, 0.0, -rad(if (it % 2 == 0) 1.0 else 3.0))
            motion.onGyroscope(it * 40_000_000L, rate[0].toFloat(), rate[1].toFloat(), rate[2].toFloat())
        }
        val fields = motion.take()
        assertEquals(2.0, fields["yaw"])
        assertEquals(1.0, fields["yawSd"])
    }

    @Test
    fun `rates and pulls have two decimals and never minus zero`() {
        val motion = PhoneMotion()
        motion.gravity(upright.phone(0.0, 0.0, GRAVITY))
        motion.gyroscope(upright.phone(0.0, 0.0, -rad(0.123456)))
        motion.linear(upright.phone(-0.001, -0.004, 0.0))
        val fields = motion.take()
        // Written as the log writes it: 0.0, not -0.0.
        assertEquals("""{"n":25,"ms":960,"yaw":0.12,"yawSd":0.0,"fwd":0.0,"side":0.0,"ref":"back","tilt":0}""", JsonLines.encode(fields))
    }

    // ------------------------------------------------------------------ when the sensors listen

    @Test
    fun `the sensors start at a drive's first update on a freeway and stop at the first one off it`() {
        val listening = PhoneMotion.Listening()
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = false))
        assertEquals(PhoneMotion.Listening.Step.START, listening.onProgress(onFreeway = true))
        listening.started(true)
        assertTrue(listening.listening)
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = true))
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = true))
        assertEquals(PhoneMotion.Listening.Step.STOP, listening.onProgress(onFreeway = false))
        listening.stopped()
        assertFalse(listening.listening)
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = false))
        assertEquals(PhoneMotion.Listening.Step.START, listening.onProgress(onFreeway = true), "the next freeway of the drive")
    }

    @Test
    fun `the end of a drive stops them, and the next drive starts them again`() {
        val listening = PhoneMotion.Listening()
        listening.onProgress(onFreeway = true)
        listening.started(true)
        listening.end()
        assertFalse(listening.listening)
        assertEquals(PhoneMotion.Listening.Step.START, listening.onProgress(onFreeway = true))
    }

    @Test
    fun `a phone that cannot listen is asked once a drive`() {
        val listening = PhoneMotion.Listening()
        assertEquals(PhoneMotion.Listening.Step.START, listening.onProgress(onFreeway = true))
        listening.started(false)
        assertFalse(listening.listening)
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = true))
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = false))
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = true), "not at the next freeway either")
        listening.end()
        assertEquals(PhoneMotion.Listening.Step.START, listening.onProgress(onFreeway = true), "the next drive asks again")
    }

    @Test
    fun `a failure ends listening for the rest of the drive`() {
        val listening = PhoneMotion.Listening()
        listening.onProgress(onFreeway = true)
        listening.started(true)
        listening.fail()
        assertFalse(listening.listening)
        assertEquals(PhoneMotion.Listening.Step.NONE, listening.onProgress(onFreeway = true))
        listening.end()
        assertEquals(PhoneMotion.Listening.Step.START, listening.onProgress(onFreeway = true))
    }

    // ------------------------------------------------------------------ the lines and their size

    @Test
    fun `the sensors are asked for 25 samples a second`() {
        assertEquals(40_000, PhoneMotion.SAMPLING_PERIOD_MICROS)
        assertEquals(25.0, 1e6 / PhoneMotion.SAMPLING_PERIOD_MICROS)
    }

    @Test
    fun `the motion_sensors line names which sensors exist, the period and whether listening started`() {
        val fields = PhoneMotion.sensorsFields(gyroscope = true, gravity = true, linearAcceleration = false, started = true)
        assertEquals(
            mapOf("gyroscope" to true, "gravity" to true, "linearAcceleration" to false, "periodUs" to 40_000, "started" to true),
            fields,
        )
        // A phone without a gravity sensor: nothing registered.
        assertEquals(
            mapOf("gyroscope" to true, "gravity" to false, "linearAcceleration" to true, "periodUs" to 40_000, "started" to false),
            PhoneMotion.sensorsFields(gyroscope = true, gravity = false, linearAcceleration = true, started = false),
        )
        assertEquals(
            listOf(false, true, false),
            PhoneMotion.sensorsFields(gyroscope = false, gravity = true, linearAcceleration = false, started = false).values.take(3),
        )
        val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), PhoneMotion.SENSORS_TYPE, fields)
        val bytes = line.toByteArray(Charsets.UTF_8).size
        println("motion_sensors line: $bytes bytes: $line")
        assertTrue(bytes < 160, "$bytes bytes")
    }

    @Test
    fun `the motion object adds about 100 bytes to a progress line`() {
        val motion = PhoneMotion()
        // A phone leaning back 12 degrees on a gentle left curve, braking a little: two-digit and
        // negative values where a drive would have them.
        val pose = leaningBack(12.0)
        motion.gravity(pose.phone(0.0, 0.0, GRAVITY))
        repeat(25) {
            val rate = pose.phone(0.0, 0.0, rad(if (it % 2 == 0) 0.3 else 0.55))
            motion.onGyroscope(it * 40_000_000L, rate[0].toFloat(), rate[1].toFloat(), rate[2].toFloat())
        }
        motion.linear(pose.phone(-0.21, -0.34, 0.0))
        val fields = motion.take()
        val time = Instant.parse("2000-01-01T00:00:00Z")
        val without = JsonLines.event(time, "progress", emptyMap())
        val with = JsonLines.event(time, "progress", mapOf("motion" to fields))
        val bytes = with.toByteArray(Charsets.UTF_8).size - without.toByteArray(Charsets.UTF_8).size
        println("motion object: $bytes bytes: ${with.substringAfter("\"progress\"")}")
        assertTrue(bytes in 80..120, "$bytes bytes")
    }

    private companion object {
        const val GRAVITY = 9.81
    }
}
