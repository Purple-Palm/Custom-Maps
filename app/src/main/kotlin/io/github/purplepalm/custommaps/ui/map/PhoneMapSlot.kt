package io.github.purplepalm.custommaps.ui.map

/**
 * Where the main screen keeps its phone map ([MapScreen]): made the first time the map is to show,
 * then the same map for as long as the screen lasts, also while the list of the maneuvers ahead shows
 * in its place. A screen that starts on the list, as one made again mid-drive while a car shows the
 * map, has no map until the map is to show.
 *
 * Mapbox stops and destroys a map's view with the activity only once the view has been in the window:
 * its lifecycle plugin takes the activity's lifecycle on the view's first attach. A map made while the
 * list shows would have its view out of the window from the start, and so would never stop: its render
 * thread would run, its puck would glide to every position of the drive, and nothing would destroy it
 * with the activity. The screen puts the view of a map made here in the window in the same composition.
 *
 * Not a Compose state: the screen composes again whenever what it shows changes, and uses the map it
 * made in that same composition. Main thread only.
 */
internal class PhoneMapSlot<M : Any> {

    /** The map once made; null before. */
    var map: M? = null
        private set

    /**
     * The map for a composition that shows it ([shown]) or not: made with [make] now if it is to show
     * for the first time, the one made before otherwise, and null while it has never been to show.
     */
    fun take(shown: Boolean, make: () -> M): M? {
        if (shown && map == null) map = make()
        return map
    }
}
