package io.github.purplepalm.custommaps.nav.camera

/**
 * For a camera that frames the car with the navigation SDK's data source: after a seek of a
 * simulated drive, the frame of the car's new place is computed on that place's route progress,
 * and the camera goes there at once.
 *
 * The navigator sends the location of an update first and its progress right after. At the new
 * place's first location the data source still holds the progress of the place the car left: the
 * rest of that step, its maneuver, the road past it. A frame computed then would hold both places,
 * or slide across the map from the old one. So the locations frame nothing until the new place's
 * progress has come, unless it is overdue ([waitMillis]), and that progress puts the camera on
 * its frame at once.
 *
 * Times are monotonic milliseconds.
 */
class JumpFrame(private val waitMillis: Long = OncePerUpdate.PROGRESS_WAIT_MILLIS) {

    private var jumpedAt = NONE

    /** A seek put the car somewhere else; its next location is the new place. */
    fun onJump(nowMillis: Long) {
        jumpedAt = nowMillis
    }

    /** Whether a location that came at [nowMillis] may move the camera as usual. */
    fun locationFrames(nowMillis: Long): Boolean = jumpedAt == NONE || nowMillis - jumpedAt > waitMillis

    /** A route progress was framed: true when it is the new place's first, and the camera goes to its frame at once. */
    fun progressJumps(): Boolean {
        val jump = jumpedAt != NONE
        jumpedAt = NONE
        return jump
    }

    /** The drive ended: no seek is waiting for its progress. */
    fun reset() {
        jumpedAt = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
