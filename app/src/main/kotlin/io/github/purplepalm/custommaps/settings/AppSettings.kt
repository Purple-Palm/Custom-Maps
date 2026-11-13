package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.nav.car.CarFrameRate
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Settings the driver changes on the phone. */
interface AppSettings {
    /** Replay the route instead of following GPS; for testing at home. Still billed as a trip. */
    val simulateDrive: Flow<Boolean>

    suspend fun setSimulateDrive(enabled: Boolean)

    /** How the camera moves during guidance and in the route preview. */
    val cameraStyle: Flow<CameraStyle>

    suspend fun setCameraStyle(style: CameraStyle)

    /** How close the guidance camera goes before turns and lane choices. */
    val laneZoom: Flow<LaneZoom>

    suspend fun setLaneZoom(mode: LaneZoom)

    /** Whether the map draws lane markings where the map data has them. */
    val laneDetails: Flow<Boolean>

    suspend fun setLaneDetails(enabled: Boolean)

    /** The look of the app and its map: by the sun (Auto), or always the day or the night look. */
    val dayNight: Flow<LookChoice>

    suspend fun setDayNight(choice: LookChoice)

    /** Debug: whether the app draws the route line of a drive itself, in place of the navigation SDK's. */
    val ownRouteLine: Flow<Boolean>

    suspend fun setOwnRouteLine(enabled: Boolean)

    /** Debug: whether the app draws the freeway lanes of a drive itself, under its own route line. */
    val drawOwnLanes: Flow<Boolean>

    suspend fun setDrawOwnLanes(enabled: Boolean)

    /**
     * Debug: the frames a second the map on the car's screen draws, one of [CarFrameRate.TARGETS]: 30,
     * or 60 to compare with what the car's screen shows.
     */
    val carMapFps: Flow<Int>

    suspend fun setCarMapFps(fps: Int)
}

/**
 * The settings in a DataStore file. A choice is stored as a lowercase word that never changes, and
 * a missing or unknown word reads as the default.
 *
 * @param onChanged called after each change that was stored, with the key and the value as stored
 */
class DataStoreAppSettings(
    private val dataStore: DataStore<Preferences>,
    private val onChanged: (key: String, value: Any) -> Unit = { _, _ -> },
) : AppSettings {

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    override val simulateDrive: Flow<Boolean> = preferences.map { it[SIMULATE_DRIVE] ?: false }.distinctUntilChanged()

    override suspend fun setSimulateDrive(enabled: Boolean) {
        dataStore.edit { it[SIMULATE_DRIVE] = enabled }
        onChanged(SIMULATE_DRIVE.name, enabled)
    }

    override val cameraStyle: Flow<CameraStyle> = preferences.map { cameraStyleOf(it[CAMERA_STYLE]) }.distinctUntilChanged()

    override suspend fun setCameraStyle(style: CameraStyle) {
        val stored = style.stored()
        dataStore.edit { it[CAMERA_STYLE] = stored }
        onChanged(CAMERA_STYLE.name, stored)
    }

    override val laneZoom: Flow<LaneZoom> = preferences.map { laneZoomOf(it[LANE_ZOOM]) }.distinctUntilChanged()

    override suspend fun setLaneZoom(mode: LaneZoom) {
        val stored = mode.stored()
        dataStore.edit { it[LANE_ZOOM] = stored }
        onChanged(LANE_ZOOM.name, stored)
    }

    override val laneDetails: Flow<Boolean> = preferences.map { it[LANE_DETAILS] ?: false }.distinctUntilChanged()

    override suspend fun setLaneDetails(enabled: Boolean) {
        dataStore.edit { it[LANE_DETAILS] = enabled }
        onChanged(LANE_DETAILS.name, enabled)
    }

    override val dayNight: Flow<LookChoice> = preferences.map { lookChoiceOf(it[DAY_NIGHT]) }.distinctUntilChanged()

    override suspend fun setDayNight(choice: LookChoice) {
        val stored = choice.stored()
        dataStore.edit { it[DAY_NIGHT] = stored }
        onChanged(DAY_NIGHT.name, stored)
    }

    override val ownRouteLine: Flow<Boolean> = preferences.map { it[OWN_ROUTE_LINE] ?: false }.distinctUntilChanged()

    override suspend fun setOwnRouteLine(enabled: Boolean) {
        dataStore.edit { it[OWN_ROUTE_LINE] = enabled }
        onChanged(OWN_ROUTE_LINE.name, enabled)
    }

    override val drawOwnLanes: Flow<Boolean> = preferences.map { it[DRAW_OWN_LANES] ?: false }.distinctUntilChanged()

    override suspend fun setDrawOwnLanes(enabled: Boolean) {
        dataStore.edit { it[DRAW_OWN_LANES] = enabled }
        onChanged(DRAW_OWN_LANES.name, enabled)
    }

    /** A number of frames the Debug switch does not offer reads as 30. */
    override val carMapFps: Flow<Int> =
        preferences.map { CarFrameRate.targetOf(it[CAR_MAP_FPS] ?: CarFrameRate.TARGET_FPS) }.distinctUntilChanged()

    override suspend fun setCarMapFps(fps: Int) {
        val stored = CarFrameRate.targetOf(fps)
        dataStore.edit { it[CAR_MAP_FPS] = stored }
        onChanged(CAR_MAP_FPS.name, stored)
    }

    companion object {
        const val FILE_NAME = "app_settings"

        /** Location of the file relative to the app's files directory, as the backup rules name it. */
        const val RELATIVE_PATH = "datastore/$FILE_NAME.preferences_pb"
        private val SIMULATE_DRIVE = booleanPreferencesKey("simulate_drive")
        private val CAMERA_STYLE = stringPreferencesKey("camera_style")
        private val LANE_ZOOM = stringPreferencesKey("lane_zoom")
        private val LANE_DETAILS = booleanPreferencesKey("lane_details")
        private val DAY_NIGHT = stringPreferencesKey("day_night")
        private val OWN_ROUTE_LINE = booleanPreferencesKey("own_route_line")
        private val DRAW_OWN_LANES = booleanPreferencesKey("draw_own_lanes")
        private val CAR_MAP_FPS = intPreferencesKey("car_map_fps")

        private fun CameraStyle.stored(): String = when (this) {
            CameraStyle.MAPBOX_STANDARD -> "mapbox_standard"
            CameraStyle.MAPBOX_TUNED -> "mapbox_tuned"
            CameraStyle.SMOOTH_FOLLOW -> "smooth_follow"
        }

        private fun cameraStyleOf(stored: String?): CameraStyle =
            CameraStyle.entries.firstOrNull { it.stored() == stored } ?: CameraStyle.DEFAULT

        private fun LaneZoom.stored(): String = when (this) {
            LaneZoom.DEFAULT -> "default"
            LaneZoom.LARGE -> "large"
        }

        private fun laneZoomOf(stored: String?): LaneZoom =
            LaneZoom.entries.firstOrNull { it.stored() == stored } ?: LaneZoom.DEFAULT

        private fun LookChoice.stored(): String = when (this) {
            LookChoice.AUTO -> "auto"
            LookChoice.DAY -> "day"
            LookChoice.NIGHT -> "night"
        }

        private fun lookChoiceOf(stored: String?): LookChoice =
            LookChoice.entries.firstOrNull { it.stored() == stored } ?: LookChoice.DEFAULT
    }
}
