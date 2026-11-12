package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.mapbox.api.directions.v5.models.LegStep
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.PhoneMotion
import io.github.purplepalm.custommaps.nav.debug.error

/**
 * The phone's gyroscope, gravity sensor and linear acceleration sensor, for the "motion" object of
 * the "progress" line ([PhoneMotion]): research, log only. Listened to only while guidance runs on
 * a freeway, at [PhoneMotion.SAMPLING_PERIOD_MICROS] with no batching, on a thread of their own
 * that lives from [start] to [stop]. Each sample there is only added to the window's sums, under
 * one lock; the main thread takes the window once per progress line. Registering and unregistering,
 * which wait for the phone's sensor service, run on that thread too, never on the main thread. The
 * gravity and linear acceleration sensors are Android's own, worked out from the others; none of
 * the three needs a permission at this rate. [start], [stop] and [take] are for the main thread;
 * none of them throws.
 */
internal class MotionSensors(context: Context, private val debugLog: DebugLog) {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)

    /** Guards [window] and [summing]. */
    private val lock = Any()

    /** The sums of the window under way. Guarded by [lock]. */
    private val window = PhoneMotion()

    /** The listener whose samples go into [window]; null while none do. Guarded by [lock]. */
    private var summing: Listener? = null

    /** The listener started last and not stopped since, with its thread. Main thread only. */
    private var running: Listener? = null

    /**
     * Starts listening, and writes a "motion_sensors" line once the sensors are registered, or
     * could not be. False, with that line at once, on a phone without a gyroscope or a gravity
     * sensor, where nothing is registered; without a linear acceleration sensor listening starts,
     * and the motion object's `fwd` and `side` stay null. True while it runs already.
     */
    fun start(): Boolean {
        if (running != null) return true
        return try {
            val manager = sensorManager
            // From the list of sensors the phone keeps in memory: no wait for the sensor service.
            val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val linear = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            if (manager == null || gyroscope == null || gravity == null) {
                debugLog.log(
                    PhoneMotion.SENSORS_TYPE,
                    PhoneMotion.sensorsFields(gyroscope != null, gravity != null, linear != null, started = false),
                )
                return false
            }
            val listener = Listener()
            synchronized(lock) {
                window.clear()
                summing = listener
            }
            running = listener
            listener.handler.post { listener.register(manager, gyroscope, gravity, linear) }
            true
        } catch (e: RuntimeException) {
            debugLog.error("motionSensors", e)
            stop()
            false
        }
    }

    /**
     * Stops listening: no sample counts from now on, the window under way is forgotten, and the
     * sensors are unregistered on their thread, which then ends.
     */
    fun stop() {
        val listener = running ?: return
        running = null
        synchronized(lock) {
            if (summing === listener) summing = null
            window.clear()
        }
        try {
            // Runs after a registration still waiting on the thread, then the thread ends.
            listener.handler.post { listener.unregister() }
            listener.thread.quitSafely()
        } catch (e: RuntimeException) {
            debugLog.error("motionSensors", e)
        }
    }

    /** The window since the last take ([PhoneMotion.take]), and the next one starts; null while not listening. */
    fun take(): Map<String, Any?>? = synchronized(lock) { if (summing != null) window.take() else null }

    /** The sensors of one [start], with the thread they deliver on. Its samples count only while it is [summing]. */
    private inner class Listener : SensorEventListener {

        val thread = HandlerThread(THREAD_NAME).apply { start() }
        val handler = Handler(thread.looper)
        private var manager: SensorManager? = null

        /** On [thread]: registers the three sensors with it and writes the "motion_sensors" line. */
        fun register(manager: SensorManager, gyroscope: Sensor, gravity: Sensor, linear: Sensor?) {
            this.manager = manager
            val started = try {
                val period = PhoneMotion.SAMPLING_PERIOD_MICROS
                val required = manager.registerListener(this, gyroscope, period, NO_BATCHING, handler) &&
                    manager.registerListener(this, gravity, period, NO_BATCHING, handler)
                // Without it, only fwd and side stay null.
                if (required && linear != null) manager.registerListener(this, linear, period, NO_BATCHING, handler)
                required
            } catch (e: RuntimeException) {
                debugLog.error("motionSensors", e)
                false
            }
            if (!started) {
                unregister()
                synchronized(lock) {
                    if (summing === this) {
                        summing = null
                        window.clear()
                    }
                }
            }
            debugLog.log(PhoneMotion.SENSORS_TYPE, PhoneMotion.sensorsFields(gyroscope = true, gravity = true, linearAcceleration = linear != null, started = started))
        }

        /** On [thread]: unregisters whatever of the three this listener has. */
        fun unregister() {
            try {
                manager?.unregisterListener(this)
            } catch (e: RuntimeException) {
                debugLog.error("motionSensors", e)
            }
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            val values = event.values ?: return
            val type = event.sensor?.type ?: return
            if (values.size < AXES) return
            synchronized(lock) {
                // A sample of a listener that was stopped, still on its way.
                if (summing !== this) return
                when (type) {
                    Sensor.TYPE_GYROSCOPE -> window.onGyroscope(event.timestamp, values[0], values[1], values[2])
                    Sensor.TYPE_GRAVITY -> window.onGravity(values[0], values[1], values[2])
                    Sensor.TYPE_LINEAR_ACCELERATION -> window.onLinearAcceleration(values[0], values[1], values[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private companion object {
        const val THREAD_NAME = "phone-motion"

        /** Each sample as soon as it is measured. */
        const val NO_BATCHING = 0

        const val AXES = 3
    }
}

/**
 * Whether the car is on a freeway: the intersection it passed last, [intersectionIndex] of [step]
 * as the navigator counts them on the step under way, leads onto a freeway ([isFreeway]).
 */
internal fun onFreeway(step: LegStep?, intersectionIndex: Int): Boolean =
    step?.intersections()?.getOrNull(intersectionIndex)?.isFreeway() == true
