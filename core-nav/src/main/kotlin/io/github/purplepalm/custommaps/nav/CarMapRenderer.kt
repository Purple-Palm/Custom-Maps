package io.github.purplepalm.custommaps.nav

/**
 * The map on the car's screen: the same map as the phone's ([MapRenderer]), drawn on the surface the
 * car's host gives the app. One per car session; the host's areas set its padding by themselves, so
 * [setViewportPadding] does nothing here.
 *
 * Its look comes through [setLook], as the phone's does: the app's one look, the phone's
 * ([io.github.purplepalm.custommaps.nav.look.SharedLook]), with a basemap's theme that waits for a quiet
 * moment of the drive on both maps alike. The host's own day and night colour only the images on its
 * routing card ([io.github.purplepalm.custommaps.nav.car.CarLook]).
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
