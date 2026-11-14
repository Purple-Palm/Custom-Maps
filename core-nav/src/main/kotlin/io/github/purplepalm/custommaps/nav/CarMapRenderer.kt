package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.car.ThermalStatus

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
     * Debug: the frames a second the map draws on the car's surface, 30 or 60
     * ([io.github.purplepalm.custommaps.nav.car.CarFrameRate.TARGETS]). A change applies at once on
     * the surface there is, and every later surface starts with it.
     */
    fun setFrameTarget(fps: Int)

    /**
     * The phone's thermal status as Android reports it, null while none was read: from MODERATE on the
     * map draws lighter, without its 3D objects, and full again after two minutes of a cooler phone
     * ([io.github.purplepalm.custommaps.nav.car.CarLightRule]); the phone's own map keeps them.
     */
    fun setPhoneThermal(status: ThermalStatus?)

    /** Debug: whether the map gets lighter while the phone is hot; off, the full map always. On until told otherwise. */
    fun setLightWhenHot(enabled: Boolean)

    /**
     * The car session ends: the map lets go of the surface, the navigator and everything it drew.
     * Nothing it does stops the drive. Calling it again does nothing.
     */
    fun release()
}
