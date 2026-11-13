package io.github.purplepalm.custommaps.car.routing

/**
 * The newest [capacity] images by what they show, so that an image is drawn once for each change of
 * what it shows and never again on a progress update. Main thread only, as the drawing is.
 */
class ImageCache<K : Any, V : Any>(private val capacity: Int) {

    init {
        require(capacity > 0) { "Capacity must be positive: $capacity" }
    }

    /** In order of use, the least recently used first. */
    private val entries = LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true)

    /** How many images were drawn, for the tests and the log. */
    var drawn: Int = 0
        private set

    /** The image for [key], drawn by [draw] only if it is not kept; a null drawing is not kept. */
    fun get(key: K, draw: () -> V?): V? {
        entries[key]?.let { return it }
        val image = draw() ?: return null
        drawn += 1
        entries[key] = image
        if (entries.size > capacity) entries.remove(entries.keys.first())
        return image
    }

    val size: Int get() = entries.size

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
