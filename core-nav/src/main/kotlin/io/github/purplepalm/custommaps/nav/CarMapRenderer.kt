package io.github.purplepalm.custommaps.nav

/**
 * The map on the car's screen: the same map as the phone's ([MapRenderer]), drawn on the surface the
 * car's host gives the app. One per car session; the host's areas set its padding by themselves, so
 * [setViewportPadding] does nothing here.
 *
 * Its look comes through [setLook], as the phone's does, with the look the car's host asks for (day or
 * night). The car map writes the light and the colours of that look at once and lets the basemap's
 * theme follow only once the host has held its mode for a while, so a flip in a tunnel redraws no
 * tiles ([io.github.purplepalm.custommaps.nav.car.CarLook]).
 */
interface CarMapRenderer : MapRenderer {

    /**
     * The zoom buttons of the map's action strip: zooms by [delta] levels, staying within the car's
     * limits ([io.github.purplepalm.custommaps.nav.car.CarZoom]). The camera stops following first,
     * as for any move of the map by the driver, and the strip then offers Recenter.
     */
    fun zoomBy(delta: Double)

    /**
     * The car session ends: the map lets go of the surface, the navigator and everything it drew.
     * Nothing it does stops the drive. Calling it again does nothing.
     */
    fun release()
}
