package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [AppSettings] kept in memory. Every test that needs settings uses this one fake, so a new setting
 * is added to the tests in one place.
 */
class FakeAppSettings(
    simulateDrive: Boolean = false,
    cameraStyle: CameraStyle = CameraStyle.DEFAULT,
    laneZoom: LaneZoom = LaneZoom.DEFAULT,
    lanesOnMap: LanesOnMap = LanesOnMap.DEFAULT,
    laneCountsHorizon: Boolean = LANE_COUNTS_HORIZON_DEFAULT,
    dayNight: LookChoice = LookChoice.DEFAULT,
    ownRouteLine: Boolean = false,
    measureOwnLanes: Boolean = false,
    carMapFps: Int = 30,
    carLightWhenHot: Boolean = CAR_LIGHT_WHEN_HOT_DEFAULT,
    sideRoadFields: Boolean = false,
    sound: SoundSettings = SoundSettings(),
    driveCamera: DriveCameraSettings = DriveCameraSettings(),
) : AppSettings {

    /** While true every change fails as it does on a full disk, and the stored value stays. */
    var storageFull = false

    override val simulateDrive = MutableStateFlow(simulateDrive)

    override suspend fun setSimulateDrive(enabled: Boolean) = store { simulateDrive.value = enabled }

    override val cameraStyle = MutableStateFlow(cameraStyle)

    override suspend fun setCameraStyle(style: CameraStyle) = store { cameraStyle.value = style }

    override val laneZoom = MutableStateFlow(laneZoom)

    override suspend fun setLaneZoom(mode: LaneZoom) = store { laneZoom.value = mode }

    override val lanesOnMap = MutableStateFlow(lanesOnMap)

    override suspend fun setLanesOnMap(choice: LanesOnMap) = store { lanesOnMap.value = choice }

    override val laneCountsHorizon = MutableStateFlow(laneCountsHorizon)

    override suspend fun setLaneCountsHorizon(enabled: Boolean) = store { laneCountsHorizon.value = enabled }

    override val dayNight = MutableStateFlow(dayNight)

    override suspend fun setDayNight(choice: LookChoice) = store { dayNight.value = choice }

    override val ownRouteLine = MutableStateFlow(ownRouteLine)

    override suspend fun setOwnRouteLine(enabled: Boolean) = store { ownRouteLine.value = enabled }

    override val measureOwnLanes = MutableStateFlow(measureOwnLanes)

    override suspend fun setMeasureOwnLanes(enabled: Boolean) = store { measureOwnLanes.value = enabled }

    override val carMapFps = MutableStateFlow(carMapFps)

    override suspend fun setCarMapFps(fps: Int) = store { carMapFps.value = fps }

    override val carLightWhenHot = MutableStateFlow(carLightWhenHot)

    override suspend fun setCarLightWhenHot(enabled: Boolean) = store { carLightWhenHot.value = enabled }

    override val sideRoadFields = MutableStateFlow(sideRoadFields)

    override suspend fun setSideRoadFields(enabled: Boolean) = store { sideRoadFields.value = enabled }

    override val sound = MutableStateFlow(sound)

    override suspend fun updateSound(change: (SoundSettings) -> SoundSettings) = store { sound.value = change(sound.value) }

    override val driveCamera = MutableStateFlow(driveCamera)

    /** Every change that was stored, in order, as the map would have it from the flow. */
    val storedDriveCameras = mutableListOf<DriveCameraSettings>()

    override suspend fun updateDriveCamera(change: (DriveCameraSettings) -> DriveCameraSettings) = store {
        driveCamera.value = change(driveCamera.value)
        storedDriveCameras += driveCamera.value
    }

    private fun store(change: () -> Unit) {
        if (storageFull) throw IOException("No space left on device")
        change()
    }
}
