package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.view.Choreographer

/**
 * Where a camera that moves the map on every frame gets its frames, the way [Choreographer] gives
 * them: [post] asks for the next frame, once, and [remove] takes the request back. Each frame comes
 * with its time in nanoseconds on `System.nanoTime`'s clock. The phone's map takes the display's
 * frames ([DisplayFrames]); the car's map takes those of its frame clock, which goes on while Android
 * gives the main thread none. Main thread only.
 */
internal interface FrameSource {

    /** [callback] gets the next frame, once. */
    fun post(callback: Choreographer.FrameCallback)

    /** [callback] gets no frame it asked for. */
    fun remove(callback: Choreographer.FrameCallback)
}

/** The display's frames on the main thread, through the main thread's own [Choreographer]. */
internal class DisplayFrames(private val choreographer: Choreographer = Choreographer.getInstance()) : FrameSource {

    override fun post(callback: Choreographer.FrameCallback) = choreographer.postFrameCallback(callback)

    override fun remove(callback: Choreographer.FrameCallback) = choreographer.removeFrameCallback(callback)
}
