package io.github.purplepalm.custommaps.engine.mapbox.car

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.FrameSource
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.car.CarFrameClock
import io.github.purplepalm.custommaps.nav.debug.DebugLog

/**
 * The car map's frames: [CarFrameClock]'s decisions on Android. Whoever needs a frame posts a
 * callback ([post]), as with Choreographer, and gets one tick, with a time on `System.nanoTime`'s
 * clock: the display's own frame time while the main thread gets the display's frames, the time of a
 * main-thread timer tick once it has had none for a while. The car's camera and puck take their
 * frames from here, so they keep moving while Android gives the main thread no frames. A
 * "frame_clock" line says each switch between the two: `source` (vsync or timer), `quietMs` (how long
 * the display's frames were awaited, or gone, until then) and, back on the display, `timerTicks`.
 *
 * It runs only between [start] and [stop], the life of the surface attached, and only while a frame
 * is wanted. Nothing here allocates on a frame. Every call that Android makes into it catches what is
 * thrown, writes a "car_error" line and goes on: the car, the phone and the drive share one process.
 * Main thread only.
 */
internal class CarFrames(private val log: DebugLog) : FrameSource {

    private val clock = CarFrameClock()
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())

    /** Who waits for the next tick, in the order they asked. */
    private val pending = ArrayList<Choreographer.FrameCallback>(CAPACITY)

    /** Who gets the tick under way; one removed meanwhile is null. */
    private val ticking = ArrayList<Choreographer.FrameCallback?>(CAPACITY)
    private var dispatching = false

    private var vsyncPosted = false

    /** When the timer posted to the main thread runs, or none is posted. */
    private var timerAt = CarFrameClock.NONE

    /** The clock's switches already written as "frame_clock" lines. */
    private var switchesLogged = 0

    // The first failure is written at once, later ones at most once each FAILURE_LINE_NANOS, with a count.
    private var failureWrittenAt = CarFrameClock.NONE
    private var failures = 0

    private val vsync = Choreographer.FrameCallback { frameTimeNanos -> onVsync(frameTimeNanos) }
    private val timer = Runnable { onTimer() }

    /** A surface is attached: ticks may come from now on. */
    fun start() = handle("frameClockStart") {
        clock.start(System.nanoTime())
        switchesLogged = clock.switches
        clock.setWanted(pending.isNotEmpty(), System.nanoTime())
        sync()
    }

    /** The surface goes: no tick, no display callback and no timer until the next [start]; whoever waited is forgotten. */
    fun stop() = handle("frameClockStop") {
        pending.clear()
        for (index in ticking.indices) ticking[index] = null
        clock.stop()
        sync()
    }

    /** [callback] gets the next tick, once; asking again before it comes changes nothing. */
    override fun post(callback: Choreographer.FrameCallback) {
        if (pending.contains(callback)) return
        pending.add(callback)
        if (!dispatching) wantFrames()
    }

    /**
     * [callback] gets the next tick, once, ahead of everyone else who asks for it: the puck, which the
     * camera of the same tick then sees where it is.
     */
    fun postFirst(callback: Choreographer.FrameCallback) {
        pending.remove(callback)
        pending.add(0, callback)
        if (!dispatching) wantFrames()
    }

    /** [callback] gets no tick it asked for, also not the one under way. */
    override fun remove(callback: Choreographer.FrameCallback) {
        pending.remove(callback)
        val index = ticking.indexOf(callback)
        if (index >= 0) ticking[index] = null
        if (!dispatching) wantFrames()
    }

    /** The ticks since the last call, from the display and from the timer, for the camera line. */
    fun takeTicks(): Map<String, Any?> {
        val fields = mapOf("vsyncTicks" to clock.vsyncTicks, "timerTicks" to clock.timerTicks)
        clock.takeTicks()
        return fields
    }

    private fun wantFrames() = handle("frameClockWanted") {
        clock.setWanted(pending.isNotEmpty(), System.nanoTime())
        sync()
    }

    private fun onVsync(frameTimeNanos: Long) {
        vsyncPosted = false
        handle("frameClockVsync") {
            val tick = clock.onVsync(frameTimeNanos, System.nanoTime())
            logSwitch()
            if (tick != CarFrameClock.NONE) dispatch(tick)
        }
        afterEvent()
    }

    private fun onTimer() {
        timerAt = CarFrameClock.NONE
        handle("frameClockTimer") {
            val tick = clock.onTimer(System.nanoTime())
            logSwitch()
            if (tick != CarFrameClock.NONE) dispatch(tick)
        }
        afterEvent()
    }

    /** After a display frame or a timer tick: frames wanted by whoever asked again, and the callback and the timer for them. */
    private fun afterEvent() = handle("frameClockNext") {
        clock.setWanted(pending.isNotEmpty(), System.nanoTime())
        sync()
    }

    /** One tick at [tickNanos] to everyone who waited for it; who asks again during it waits for the next. */
    private fun dispatch(tickNanos: Long) {
        for (index in pending.indices) ticking.add(pending[index])
        pending.clear()
        dispatching = true
        try {
            for (index in ticking.indices) {
                val callback = ticking[index] ?: continue
                ticking[index] = null
                try {
                    callback.doFrame(tickNanos)
                } catch (e: RuntimeException) {
                    failed("frame", e)
                }
            }
        } finally {
            ticking.clear()
            dispatching = false
        }
    }

    /** Posts the display callback and the timer as the clock wants them, and takes back what it does not. */
    private fun sync() {
        if (clock.vsyncWanted) {
            if (!vsyncPosted) {
                choreographer.postFrameCallback(vsync)
                vsyncPosted = true
            }
        } else if (vsyncPosted) {
            choreographer.removeFrameCallback(vsync)
            vsyncPosted = false
        }
        val due = clock.timerAtNanos
        if (due == CarFrameClock.NONE) {
            if (timerAt != CarFrameClock.NONE) {
                handler.removeCallbacks(timer)
                timerAt = CarFrameClock.NONE
            }
        } else if (timerAt == CarFrameClock.NONE || due < timerAt) {
            // A timer due later than the one posted is left to that one: it runs, finds nothing due
            // yet and posts the next.
            handler.removeCallbacks(timer)
            handler.postDelayed(timer, delayMillis(due))
            timerAt = due
        }
    }

    /** Whole milliseconds from now until [due], rounded up, so the timer never runs before it. */
    private fun delayMillis(due: Long): Long =
        ((due - System.nanoTime() + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceAtLeast(0L)

    private fun logSwitch() {
        if (clock.switches == switchesLogged) return
        switchesLogged = clock.switches
        val fields = if (clock.source == CarFrameClock.Source.TIMER) {
            mapOf("source" to SOURCE_TIMER, "quietMs" to clock.switchQuietMillis)
        } else {
            mapOf("source" to SOURCE_VSYNC, "quietMs" to clock.switchQuietMillis, "timerTicks" to clock.switchTimerTicks)
        }
        log.log(LINE, fields)
    }

    private inline fun handle(call: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            failed(call, e)
        }
    }

    /** A "car_error" line: the first at once, then at most one each [FAILURE_LINE_NANOS], with how many came. */
    private fun failed(call: String, e: RuntimeException) {
        failures += 1
        val now = System.nanoTime()
        if (failureWrittenAt != CarFrameClock.NONE && now - failureWrittenAt < FAILURE_LINE_NANOS) return
        log.log(ERROR_LINE, CarFailure.fields(call, e) + ("failures" to failures))
        failureWrittenAt = now
        failures = 0
    }

    private companion object {
        const val LINE = "frame_clock"
        const val ERROR_LINE = "car_error"
        const val SOURCE_VSYNC = "vsync"
        const val SOURCE_TIMER = "timer"

        /** The camera and the puck, with room to spare. */
        const val CAPACITY = 4

        const val NANOS_PER_MILLI = 1_000_000L
        const val FAILURE_LINE_NANOS = 10_000_000_000L
    }
}
