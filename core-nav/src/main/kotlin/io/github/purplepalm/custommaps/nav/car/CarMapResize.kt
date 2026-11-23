package io.github.purplepalm.custommaps.nav.car

/**
 * The car map's size ([CarMapSize]) as the car map applies it: at once, on the map of the surface there is, and on
 * every later surface. A new size sets the options every map is made from ([Port.useSize]). With a surface, its map is
 * made again on it ([Port.remake]), as for a surface the host gave anew: the camera of the old map is read first
 * ([Port.camera]) and kept for the new one ([keptCamera]) while the old map goes and the new one comes, and meanwhile
 * ([remaking]) the surface counts as showing the map throughout, so the phone keeps its list of the turns. Without a
 * surface the next one takes the size; a remake that fails leaves it to the next surface too. The same size again does
 * nothing.
 *
 * Plain state on the main thread; [C] is the map's camera. Every call of the [Port] may throw where the map library
 * refuses, and the caller's guard catches it.
 */
class CarMapResize<C : Any>(private val port: Port<C>) {

    /** The car map's side of a new size. */
    interface Port<C : Any> {
        /** Whether a surface shows the car's map now. */
        val hasSurface: Boolean

        /** The camera of the surface's map now, where the new map is to start; null when none is worth keeping. */
        fun camera(): C?

        /** Every map made from now on, on the surface there is and on every later one, is made at [size]. */
        fun useSize(size: CarMapSize)

        /** The map of the surface there is is made again on it, as if the host had given the surface anew: whether a new map is on it. */
        fun remake(): Boolean
    }

    /** What became of a new size, for its "car_surface" line. */
    enum class Outcome(val logName: String) {
        /** No surface now: the next one is made at the size. */
        NEXT_SURFACE("next_surface"),

        /** The map of the surface was made again at the size. */
        REMADE("remade"),

        /** The map could not be made again: the next surface is made at the size. */
        FAILED("failed"),
    }

    /** The size every map is made at from now on: [CarMapSize.DEFAULT] until a size comes. */
    var size: CarMapSize = CarMapSize.DEFAULT
        private set

    /** Whether the map of the surface is being made again for a new size: the surface counts as showing the map throughout. */
    var remaking: Boolean = false
        private set

    /** While [remaking]: the camera the old map showed, where the new one starts; null for none worth keeping. */
    var keptCamera: C? = null
        private set

    /**
     * The size [wanted] from now on: what became of it, or null when it is the size there is. The options come first, so
     * that the new map and every later one are made at it whatever the remake does.
     */
    fun request(wanted: CarMapSize): Outcome? {
        if (wanted == size) return null
        size = wanted
        port.useSize(wanted)
        if (!port.hasSurface) return Outcome.NEXT_SURFACE
        keptCamera = port.camera()
        remaking = true
        return try {
            if (port.remake()) Outcome.REMADE else Outcome.FAILED
        } finally {
            remaking = false
            keptCamera = null
        }
    }
}
