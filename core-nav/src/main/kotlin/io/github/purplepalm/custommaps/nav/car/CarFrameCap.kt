package io.github.purplepalm.custommaps.nav.car

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cap on the frames of the car's map: the display's own cap, moved only by the frames the map
 * really draws.
 *
 * Mapbox paces the car's surface by its render thread's ticks: each call of the render thread, plus
 * the display frames it counts as missed when a call comes more than one of its own frames and a
 * millisecond after the one before, all against the 60 Hz it believes the surface has
 * ([CarFrameRate.ASSUMED_HZ]). Under a cap of `c` it draws on `c` of every 60 ticks. On a display at
 * 60 Hz the ticks follow the display's frames, so a cap draws about its own number of frames a second;
 * but a call that comes a little late counts a frame as missed that was not, as while the phone's
 * screen is off, so the ticks are no measure of the render thread's rate there. On a display at
 * 120 Hz a frame that takes longer than one display frame makes the next call wait for the display
 * frame after next, which counts as one tick, so the cap for the display's rate alone (15) draws about
 * 24 frames a second when frames take 9 to 16 ms. So:
 * - **The base** is the cap for the display's rate, `round(target × 60 / rate)` within 1 and 60
 *   ([CarFrameRate.maxFps]): 30 at 60 Hz and 15 at 120 Hz for the target 30. It is worked out once a
 *   second ([onSecond]) for the rate of the display's frames as the main thread measures it, for
 *   60 Hz while the frame clock runs on its timer, and for the 60 Hz the map library believes before
 *   there is a rate; and again only when the base would draw more than a tenth off the target at the
 *   display's new rate, so a rate measured a little off changes nothing.
 * - **Each window** of the render thread's statistics that the camera line takes during guidance,
 *   about a second ([onRenderWindow]), brings the frames it drew, its ticks and the 95th percentile of
 *   its frame times. A window counts when the base has a rate, the frame clock stayed on the display
 *   all through it, the cap was not changed in it, it is at least [MIN_WINDOW_MILLIS] long and the
 *   render thread was called on at least half of the display's frames: a map that drew now and then,
 *   standing at a light, says nothing.
 * - **The cap moves** only when two counted windows in a row ask for the same: up when both drew under
 *   [RAISE_BELOW_SHARE] of the target and their 95th percentile was under one frame of the target
 *   (33 ms at 30), as frames that take longer are the limit themselves and no cap helps them; down
 *   when both drew over [LOWER_ABOVE_SHARE] of the target. It goes to `round(cap × target / drawn)`
 *   within 1 and 60, for the one of the two windows that asks for the smaller step, and never below
 *   the base, whatever the ticks say.
 * - **A cap moved** is kept while the display's rate stays within a tenth of the one it was moved at:
 *   the timer takes the cap to the timer's base, and back on the display the moved cap comes again;
 *   after the drive it stays for the next one. A display whose rate moved further takes the new base.
 *
 * The target is [CarFrameRate.TARGET_FPS] frames a second, or 60 with the Debug switch ([setTarget]),
 * which starts from its own base at once. The ticks are kept for the lines ([renderHz]) and set no
 * cap. Pure: the car map sets the cap on its surface and writes the lines. Nothing here allocates.
 * Main thread only.
 */
class CarFrameCap {

    /** Where the cap set now comes from. */
    enum class Basis {
        /** The display's cap, for the rate of its frames. */
        DISPLAY,

        /** The display's cap moved by the frames the map drew. */
        DRAWN,

        /** The display's cap for 60 Hz, while the frame clock runs on its timer. */
        TIMER,
    }

    /**
     * The frames a second the cap is worked out for: [CarFrameRate.TARGET_FPS], or the other target
     * of the Debug switch ([setTarget]).
     */
    var targetFps: Int = CarFrameRate.TARGET_FPS
        private set

    /** The cap set now: the base for the 60 Hz the map library believes, until a rate is taken. */
    var maxFps: Int = CarFrameRate.maxFps(CarFrameRate.ASSUMED_HZ, targetFps)
        private set

    /** The display's cap, which the cap starts from and never goes below. */
    var baseFps: Int = maxFps
        private set

    /** The display rate [baseFps] is worked out for: as measured, 60 on the timer; NaN until one was taken. */
    var displayRateHz: Double = Double.NaN
        private set

    /** Where [maxFps] comes from; null until a rate was taken. */
    var basis: Basis? = null
        private set

    /**
     * The frames a second drawn that moved the cap to where it is now, of the one of the two windows
     * that asked for the smaller step; also for a cap moved before that comes back after the timer.
     * NaN while the base alone set the cap: a new display rate, the timer, a new target, and before.
     */
    var drawnFps: Double = Double.NaN
        private set

    /**
     * The render thread's ticks a second in the last window that counted: its calls and the display
     * frames it counted as missed. For the lines only: they set no cap. NaN before such a window.
     */
    var renderHz: Double = Double.NaN
        private set

    /**
     * The rate the lines give for the cap ("measuredHz"): the render thread's ticks a second while
     * the frames drawn hold the cap above the base, else the display's rate the base is for.
     */
    val rateHz: Double get() = if (basis == Basis.DRAWN) renderHz else displayRateHz

    /** Where the base comes from: the display or the timer; null before a rate. */
    private var baseBasis: Basis? = null

    /** The cap the frames drawn moved it to, above the base, the display rate then and the frames drawn that moved it; 0 for none. */
    private var movedFps = 0
    private var movedAtHz = Double.NaN
    private var movedDrawnFps = Double.NaN

    /** What the window before asked for, waiting for a second window to ask the same, and what it drew a second. */
    private var asked = ASK_NONE
    private var askedDrawnFps = Double.NaN

    /** The caps set so far, and how many there were as the window under way began. */
    private var caps = 0
    private var capsAtWindowStart = 0

    /** A window that draws under this asks for a higher cap, if its frames are quick enough. */
    private val raiseBelowFps: Double get() = targetFps * RAISE_BELOW_SHARE

    /** A window that draws over this asks for a lower cap. */
    private val lowerAboveFps: Double get() = targetFps * LOWER_ABOVE_SHARE

    /** One frame at the target, in milliseconds: frames that take longer are the limit themselves. */
    private val frameMillis: Double get() = MILLIS_PER_SECOND / targetFps

    /**
     * The Debug switch's target, [fps] frames a second (within 1 and 60): the cap starts from the new
     * target's base at once, for the display rate taken (the 60 Hz the map library believes before
     * one); a cap the frames drawn moved and a step that waits for a second window go. The new cap is a
     * cap changed in the window under way, which then says nothing. Returns whether the target changed,
     * for the car map to set the cap and say so.
     */
    fun setTarget(fps: Int): Boolean {
        val target = fps.coerceIn(CarFrameRate.MIN_FPS, CarFrameRate.MAX_FPS)
        if (target == targetFps) return false
        targetFps = target
        baseFps = CarFrameRate.maxFps(if (baseBasis == null) CarFrameRate.ASSUMED_HZ else displayRateHz, target)
        movedFps = 0
        asked = ASK_NONE
        apply()
        drawnFps = Double.NaN
        return true
    }

    /**
     * A new surface, with a render thread of its own: it starts with the cap set now, and the rates
     * taken on the surface before are kept, but no step that was waiting for a second window is.
     */
    fun onSurface() {
        asked = ASK_NONE
        capsAtWindowStart = caps
    }

    /** A window of the render thread's statistics starts now: the camera line's first, or the next one. */
    fun onWindowStart() {
        capsAtWindowStart = caps
    }

    /**
     * A window of the render thread's statistics: [ticks], its calls and the display frames it counted
     * as missed, of which [drawn] calls drew, and [p95Ms] the 95th percentile of the calls' times
     * (null for none), in [windowMillis]; [displayHz], the display's rate measured on the main thread
     * (null before there is one); [onDisplay], whether the frame clock stayed on the display's frames
     * all through the window. Returns whether a new cap is to be set.
     */
    fun onRenderWindow(ticks: Long, drawn: Long, p95Ms: Double?, windowMillis: Long, displayHz: Double?, onDisplay: Boolean): Boolean {
        val capHeld = caps == capsAtWindowStart
        val seconds = windowMillis / MILLIS_PER_SECOND
        val ticksHz = if (windowMillis > 0L) ticks / seconds else Double.NaN
        val counts = baseBasis != null && onDisplay && capHeld && displayHz != null && displayHz > 0.0 &&
            windowMillis >= MIN_WINDOW_MILLIS && ticksHz >= STEADY_SHARE * displayHz
        var changed = false
        if (!counts || displayHz == null) {
            asked = ASK_NONE
        } else {
            renderHz = ticksHz
            val drawnHz = drawn / seconds
            val ask = when {
                drawnHz > 0.0 && drawnHz < raiseBelowFps && p95Ms != null && p95Ms < frameMillis -> ASK_UP
                drawnHz > lowerAboveFps -> ASK_DOWN
                else -> ASK_NONE
            }
            if (ask == ASK_NONE || ask != asked) {
                asked = ask
                askedDrawnFps = drawnHz
            } else {
                // The second window in a row that asks for it: the smaller of the two steps.
                val by = if (ask == ASK_UP) max(drawnHz, askedDrawnFps) else min(drawnHz, askedDrawnFps)
                asked = ASK_NONE
                changed = move(by, displayHz)
            }
        }
        // The next window starts now, with the cap as it is after this one.
        capsAtWindowStart = caps
        return changed
    }

    /**
     * Once a second: [onTimer], whether the frame clock runs on its timer now; [displayHz], the
     * display's rate measured on the main thread (null before there is one). Returns whether a new cap
     * is to be set, or the first rate was taken.
     */
    fun onSecond(onTimer: Boolean, displayHz: Double?): Boolean {
        if (onTimer) asked = ASK_NONE
        val rate = if (onTimer) CarFrameRate.ASSUMED_HZ else displayHz ?: return false
        val next = if (onTimer) Basis.TIMER else Basis.DISPLAY
        val first = baseBasis == null
        // A base that draws within a tenth of the target at the new rate stays, and says where it is from.
        val drawnAtBase = baseFps * rate / CarFrameRate.ASSUMED_HZ
        val held = drawnAtBase >= targetFps * (1.0 - CarFrameRate.CHANGE_SHARE) && drawnAtBase <= targetFps * (1.0 + CarFrameRate.CHANGE_SHARE)
        if (first || !held) {
            baseFps = CarFrameRate.maxFps(rate, targetFps)
            displayRateHz = rate
        } else if (baseBasis != next) {
            displayRateHz = rate
        }
        baseBasis = next
        // A cap the frames drawn moved is for the display rate it was moved at.
        if (!onTimer && movedFps > 0 && abs(rate - movedAtHz) > CarFrameRate.CHANGE_SHARE * movedAtHz) movedFps = 0
        val changed = apply()
        if (changed) drawnFps = if (basis == Basis.DRAWN) movedDrawnFps else Double.NaN
        return changed || first
    }

    /** Moves the cap for [drawnHz] frames drawn a second; one at or below the base is the base. True when it changed. */
    private fun move(drawnHz: Double, displayHz: Double): Boolean {
        val cap = (maxFps * targetFps / drawnHz).roundToInt().coerceIn(CarFrameRate.MIN_FPS, CarFrameRate.MAX_FPS)
        movedFps = if (cap > baseFps) cap else 0
        movedAtHz = displayHz
        movedDrawnFps = drawnHz
        val changed = apply()
        if (changed) drawnFps = drawnHz
        return changed
    }

    /** The cap from the base and a cap moved above it, and where it comes from; true when it changed. */
    private fun apply(): Boolean {
        val moved = baseBasis != Basis.TIMER && movedFps > baseFps
        basis = if (moved) Basis.DRAWN else baseBasis
        val cap = if (moved) movedFps else baseFps
        if (cap == maxFps) return false
        maxFps = cap
        caps += 1
        return true
    }

    companion object {
        /** The least share of the display's frames the render thread is called on in a window that counts. */
        const val STEADY_SHARE = 0.5

        /** The shortest window that counts. */
        const val MIN_WINDOW_MILLIS = 500L

        /** Two windows that draw under this share of the target, with quick frames, raise the cap. */
        const val RAISE_BELOW_SHARE = 0.9

        /** Two windows that draw over this share of the target lower the cap, never below the base. */
        const val LOWER_ABOVE_SHARE = 1.25

        private const val ASK_NONE = 0
        private const val ASK_UP = 1
        private const val ASK_DOWN = 2

        private const val MILLIS_PER_SECOND = 1_000.0
    }
}
