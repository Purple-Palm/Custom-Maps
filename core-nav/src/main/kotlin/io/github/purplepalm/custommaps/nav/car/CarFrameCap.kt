package io.github.purplepalm.custommaps.nav.car

import kotlin.math.abs
import kotlin.math.max

/**
 * The cap on the frames of the car's map, worked out from what the map library itself counts.
 *
 * Mapbox paces the car's surface by its render thread's ticks: each call of the render thread, plus
 * the display frames it counts as missed when a call comes more than one of its own frames and a
 * millisecond after the one before, all against the 60 Hz it believes the surface has
 * ([CarFrameRate.ASSUMED_HZ]). Under a cap of `c` it draws on `c` of every 60 ticks, so it draws
 * `c × ticks a second / 60` frames a second. On a phone at 120 Hz a frame that takes longer than one
 * display frame to draw makes the next call wait for the display frame after next, which it counts as
 * one tick: its ticks then come slower than the display's frames, and the cap for the display's rate
 * alone (15 at 120 Hz) draws fewer than 30 frames a second, about 24 when frames take 9 to 16 ms.
 *
 * So the cap follows the render thread's own ticks, `round(target × 60 / ticks a second)` within 1 and
 * 60 ([CarFrameRate.maxFps]), for the target of [targetFps] frames a second: 30, or 60 with the Debug
 * switch ([setTarget]), which takes effect at once:
 * - **During guidance** each window of the render thread's statistics that the camera line takes,
 *   about a second, brings its ticks ([onRenderWindow]). A window counts when the frame clock stayed
 *   on the display all through it, the cap was not changed in it, and the render thread was called on
 *   at least half of the display's frames: a map that drew now and then, standing at a light, says
 *   nothing about how fast it draws. The cap goes down at once when the map would draw more than a
 *   tenth of the target over it, and up only when two windows in a row say it would draw more than a
 *   tenth under, for the faster of the two, so a window that was busy only in part never brings
 *   more frames by itself.
 * - **Once a second** ([onSecond]): 60 while the frame clock runs on its timer, when the main thread
 *   has no display rate; else the render thread's rate the cap was last set or held for, while the
 *   display's rate is within a tenth of the one it was counted at, which brings that cap back after
 *   the timer and keeps it after the drive; else the display's rate itself, as measured from its
 *   frames on the main thread, which is what there is before the first guidance.
 *
 * A cap is set again only when the frames the map would draw at the one set now are more than a
 * tenth off the target. Pure: the car map sets the cap on its surface and writes the lines. Nothing
 * here allocates. Main thread only.
 */
class CarFrameCap {

    /** Where the rate a cap is worked out for comes from. */
    enum class Basis { DISPLAY, RENDER, TIMER }

    /**
     * The frames a second the cap is worked out for: [CarFrameRate.TARGET_FPS], or the other target
     * of the Debug switch ([setTarget]).
     */
    var targetFps: Int = CarFrameRate.TARGET_FPS
        private set

    /** The cap set now: the one for the 60 Hz the map library believes, until a rate is taken. */
    var maxFps: Int = CarFrameRate.maxFps(CarFrameRate.ASSUMED_HZ, targetFps)
        private set

    /** The rate [maxFps] was worked out for, ticks or frames a second; NaN until one was taken. */
    var rateHz: Double = Double.NaN
        private set

    /** Where [rateHz] came from; null until a rate was taken. */
    var basis: Basis? = null
        private set

    /**
     * The render thread's ticks a second as last counted: the window that set the cap or held it, or
     * the faster of the two that raised it; NaN before one did.
     */
    var renderHz: Double = Double.NaN
        private set

    /** The display's rate when [renderHz] was counted. */
    private var renderAtDisplayHz = Double.NaN

    /** The ticks a second of the window before, when it asked for more frames; NaN otherwise. */
    private var raiseHz = Double.NaN

    /** The caps set so far, and how many there were as the window under way began. */
    private var caps = 0
    private var capsAtWindowStart = 0

    /** The frames a second around the target within which a cap stays: a tenth of the target either way. */
    private val lowFps: Double get() = targetFps * (1.0 - CarFrameRate.CHANGE_SHARE)
    private val highFps: Double get() = targetFps * (1.0 + CarFrameRate.CHANGE_SHARE)

    /**
     * The Debug switch's target, [fps] frames a second (within 1 and 60): the cap is worked out for it
     * at once, for the rate taken (the 60 Hz the map library believes before one). A new cap is a cap
     * changed in the window under way, which then says nothing, and a raise that was waiting for a
     * second window goes with it. Returns whether the target changed, for the car map to set the cap
     * and say so.
     */
    fun setTarget(fps: Int): Boolean {
        val target = fps.coerceIn(CarFrameRate.MIN_FPS, CarFrameRate.MAX_FPS)
        if (target == targetFps) return false
        targetFps = target
        val cap = CarFrameRate.maxFps(if (basis == null) CarFrameRate.ASSUMED_HZ else rateHz, target)
        if (cap != maxFps) {
            maxFps = cap
            caps += 1
        }
        return true
    }

    /**
     * A new surface, with a render thread of its own: it starts with the cap set now, and the rate
     * counted on the surface before is kept, but no raise that was waiting for a second window is.
     */
    fun onSurface() {
        raiseHz = Double.NaN
        capsAtWindowStart = caps
    }

    /** A window of the render thread's statistics starts now: the camera line's first, or the next one. */
    fun onWindowStart() {
        capsAtWindowStart = caps
    }

    /**
     * A window of the render thread's statistics: [ticks], its calls and the display frames it
     * counted as missed, in [windowMillis]; [displayHz], the display's rate measured on the main
     * thread (null before there is one); [onDisplay], whether the frame clock stayed on the display's
     * frames all through the window. Returns whether a new cap is to be set.
     */
    fun onRenderWindow(ticks: Long, windowMillis: Long, displayHz: Double?, onDisplay: Boolean): Boolean {
        val capHeld = caps == capsAtWindowStart
        val hz = if (windowMillis > 0L) ticks * MILLIS_PER_SECOND / windowMillis else Double.NaN
        val counts = onDisplay && capHeld && displayHz != null && displayHz > 0.0 &&
            windowMillis >= MIN_WINDOW_MILLIS && hz >= STEADY_SHARE * displayHz
        val changed = if (!counts || displayHz == null) {
            raiseHz = Double.NaN
            false
        } else {
            val drawn = drawnAt(hz)
            when {
                drawn > highFps -> {
                    raiseHz = Double.NaN
                    hold(hz, displayHz)
                    take(hz, Basis.RENDER)
                }
                // A raise waits for a second window; the rate held meanwhile is the one before.
                drawn < lowFps ->
                    if (raiseHz.isNaN()) {
                        raiseHz = hz
                        false
                    } else {
                        val rate = max(hz, raiseHz)
                        raiseHz = Double.NaN
                        hold(rate, displayHz)
                        take(rate, Basis.RENDER)
                    }
                else -> {
                    raiseHz = Double.NaN
                    hold(hz, displayHz)
                    false
                }
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
        if (onTimer) {
            raiseHz = Double.NaN
            return follow(CarFrameRate.ASSUMED_HZ, Basis.TIMER)
        }
        val hz = displayHz ?: return false
        // The render thread's rate is the one the cap was last set or held for: while windows come it
        // agrees with the cap, and back from the timer it brings that cap again.
        val renderHolds = !renderHz.isNaN() && abs(hz - renderAtDisplayHz) <= CarFrameRate.CHANGE_SHARE * renderAtDisplayHz
        return if (renderHolds) follow(renderHz, Basis.RENDER) else follow(hz, Basis.DISPLAY)
    }

    /** Keeps [rate], counted with the display at [displayHz], as the render thread's rate. */
    private fun hold(rate: Double, displayHz: Double) {
        renderHz = rate
        renderAtDisplayHz = displayHz
    }

    /** The frames a second the map draws under the cap set now, with ticks coming at [rate]. */
    private fun drawnAt(rate: Double): Double = maxFps * rate / CarFrameRate.ASSUMED_HZ

    /**
     * [rate] from [basis], taken when the cap set now would draw more than a tenth off the target, or
     * as the first rate. A cap that holds on and off the timer keeps its number, and says where it is
     * from.
     */
    private fun follow(rate: Double, basis: Basis): Boolean {
        val held = this.basis
        if (held != null && drawnAt(rate) in lowFps..highFps) {
            if (held != basis && (held == Basis.TIMER || basis == Basis.TIMER)) {
                rateHz = rate
                this.basis = basis
            }
            return false
        }
        return take(rate, basis)
    }

    /** Works the cap out for [rate]; true when it changed, or when this is the first rate. */
    private fun take(rate: Double, basis: Basis): Boolean {
        val first = this.basis == null
        rateHz = rate
        this.basis = basis
        val cap = CarFrameRate.maxFps(rate, targetFps)
        if (cap == maxFps) return first
        maxFps = cap
        caps += 1
        return true
    }

    companion object {
        /** The least share of the display's frames the render thread is called on in a window that counts. */
        const val STEADY_SHARE = 0.5

        /** The shortest window that counts. */
        const val MIN_WINDOW_MILLIS = 500L

        private const val MILLIS_PER_SECOND = 1_000.0
    }
}
