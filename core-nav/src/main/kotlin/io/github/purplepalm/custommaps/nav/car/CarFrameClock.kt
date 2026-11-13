package io.github.purplepalm.custommaps.nav.car

/**
 * When the car map's camera and puck get their frames. Both move on the main thread, once a frame,
 * and Android gives the main thread its frames from the phone's display ("vsync", through
 * Choreographer). On the first car test those stopped for 18 s while the app was in the background
 * and the car's screen still showed the map: the camera and the puck stood still although the drive
 * went on. So the car's map takes its frames from this clock: from the display while it sends them
 * ([Source.VSYNC]), and, once none has come for [quietNanos] while frames are wanted, from a timer
 * every [timerNanos] ([Source.TIMER]) until the display's next frame comes. Two ticks are never closer
 * than [minGapNanos]: at most about 60 a second, also on a display at 120 Hz.
 *
 * This is the clock's decisions only. Its Android part posts the display callback while
 * [vsyncWanted] says so, runs one timer for [timerAtNanos], reports both back ([onVsync], [onTimer])
 * and hands each tick to whoever asked for a frame. Times are nanoseconds on one clock,
 * `System.nanoTime`, which is also the clock of the display's frame times. Nothing here allocates:
 * it runs on every frame. Main thread only.
 */
class CarFrameClock(
    private val quietNanos: Long = QUIET_MILLIS * NANOS_PER_MILLI,
    private val timerNanos: Long = TIMER_MILLIS * NANOS_PER_MILLI,
    private val minGapNanos: Long = MIN_GAP_MILLIS * NANOS_PER_MILLI,
) {

    /** Where the ticks come from. */
    enum class Source { VSYNC, TIMER }

    /** Where the ticks come from now. */
    var source: Source = Source.VSYNC
        private set

    /** True from [start] to [stop]: while a car surface shows the map. */
    var running = false
        private set

    /** Whether someone waits for a frame. */
    var wanted = false
        private set

    /**
     * Whether the display's frame callback should be posted: while frames are wanted, and all the
     * time on the timer, to see the display's frames come back.
     */
    val vsyncWanted: Boolean get() = running && (wanted || source == Source.TIMER)

    /** When [onTimer] is due next, or [NONE]: while frames are wanted only. */
    var timerAtNanos: Long = NONE
        private set

    /** Ticks from the display since [takeTicks], for the camera line. */
    var vsyncTicks = 0
        private set

    /** Ticks from the timer since [takeTicks]. */
    var timerTicks = 0
        private set

    /** The switches between the two sources so far; a new one is described by the three fields after it. */
    var switches = 0
        private set

    /** How long the display had been quiet at the last switch, in milliseconds: waited for on the way to the timer, gone for on the way back. */
    var switchQuietMillis = 0L
        private set

    /** On a switch back to the display, the ticks the timer gave since the switch before. */
    var switchTimerTicks = 0
        private set

    /** When the display's frame callback last came; when frames were wanted since, if that was later. */
    private var lastVsyncAt = NONE
    private var wantedSince = NONE
    private var lastTick = NONE
    private var stretchTicks = 0

    /** A car surface shows the map from [now]: from the display, nothing wanted yet. */
    fun start(now: Long) {
        running = true
        source = Source.VSYNC
        wanted = false
        lastVsyncAt = now
        wantedSince = now
        lastTick = NONE
        stretchTicks = 0
        vsyncTicks = 0
        timerTicks = 0
        update()
    }

    /** The surface is gone: no callback, no timer, until the next [start]. */
    fun stop() {
        running = false
        wanted = false
        source = Source.VSYNC
        update()
    }

    /** Whether someone waits for a frame, from [now] on. */
    fun setWanted(wanted: Boolean, now: Long) {
        if (!running) return
        if (wanted && !this.wanted) wantedSince = now
        this.wanted = wanted
        update()
    }

    /**
     * The display's frame callback came at [now] for the frame of [frameTimeNanos]. Returns the time
     * to tick with, the frame's own, or [NONE] for no tick: nothing is wanted, or the last tick was
     * too recent.
     */
    fun onVsync(frameTimeNanos: Long, now: Long): Long {
        if (!running) return NONE
        if (source == Source.TIMER) {
            source = Source.VSYNC
            switched(quietMillis = (now - lastVsyncAt) / NANOS_PER_MILLI, timerTicks = stretchTicks)
        }
        lastVsyncAt = now
        val tick = if (wanted && spaced(frameTimeNanos)) frameTimeNanos else NONE
        if (tick != NONE) {
            lastTick = tick
            vsyncTicks += 1
        }
        update()
        return tick
    }

    /**
     * The timer came at [now]. Returns the time to tick with, [now], or [NONE]: on the display it only
     * looks whether the display has been quiet too long, and switches to the timer then.
     */
    fun onTimer(now: Long): Long {
        if (!running || !wanted) {
            update()
            return NONE
        }
        if (source == Source.VSYNC) {
            val waited = now - quietSince()
            if (waited < quietNanos) {
                update()
                return NONE
            }
            source = Source.TIMER
            stretchTicks = 0
            switched(quietMillis = waited / NANOS_PER_MILLI, timerTicks = 0)
        }
        val tick = if (spaced(now)) now else NONE
        if (tick != NONE) {
            lastTick = tick
            timerTicks += 1
            stretchTicks += 1
        }
        update()
        return tick
    }

    /** Starts the next window of [vsyncTicks] and [timerTicks]. */
    fun takeTicks() {
        vsyncTicks = 0
        timerTicks = 0
    }

    /** Whether a tick at [time] keeps [minGapNanos] from the last one. */
    private fun spaced(time: Long): Boolean = lastTick == NONE || time - lastTick >= minGapNanos

    /** Since when the display has not sent a frame that was wanted. */
    private fun quietSince(): Long = maxOf(lastVsyncAt, wantedSince)

    private fun switched(quietMillis: Long, timerTicks: Int) {
        switches += 1
        switchQuietMillis = quietMillis
        switchTimerTicks = timerTicks
    }

    private fun update() {
        timerAtNanos = when {
            !running || !wanted -> NONE
            source == Source.VSYNC -> quietSince() + quietNanos
            lastTick == NONE -> quietSince()
            else -> lastTick + timerNanos
        }
    }

    companion object {
        /** No time: no timer due, no tick. */
        const val NONE = Long.MIN_VALUE

        /** How long the display may stay quiet while frames are wanted before the timer takes over. */
        const val QUIET_MILLIS = 50L

        /** The timer's step. */
        const val TIMER_MILLIS = 16L

        /** The least time between two ticks, from either source. */
        const val MIN_GAP_MILLIS = 15L

        const val NANOS_PER_MILLI = 1_000_000L
    }
}
