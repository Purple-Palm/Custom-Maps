package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.nav.car.CarFrameRate
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

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

    /**
     * Who draws the lanes on the map: nobody, Mapbox's lane details, or the app ([LanesOnMap]). Versions before the
     * choice had a switch for Mapbox's lane details: its stored value is carried over once, on the first read.
     */
    val lanesOnMap: Flow<LanesOnMap>

    suspend fun setLanesOnMap(choice: LanesOnMap)

    /** The look of the app and its map: by the sun (Auto), or always the day or the night look. */
    val dayNight: Flow<LookChoice>

    suspend fun setDayNight(choice: LookChoice)

    /** Debug: whether the app draws the route line of a drive itself, in place of the navigation SDK's. */
    val ownRouteLine: Flow<Boolean>

    suspend fun setOwnRouteLine(enabled: Boolean)

    /**
     * Debug: whether a drive with the lanes drawn by the app measures what they cost the phone's map: the drawing shows
     * around every other close view only, so the views with it and without it can be compared.
     */
    val measureOwnLanes: Flow<Boolean>

    suspend fun setMeasureOwnLanes(enabled: Boolean)

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

    /**
     * An absent choice reads as [LanesOnMap.DEFAULT] and is never written, so a later default reaches every phone that
     * never chose; a word it does not know reads as Off.
     *
     * The first read after an update from a version with the switch Lane details on the map carries the switch over
     * ([LanesOnMap.Migration]): one write stores the choice it stands for while the old key stays, and is reported as a
     * change; a second one removes the old key, and the old Debug switch's with it; only then is the choice emitted. A
     * process that dies between the two leaves both, and the next read only removes the old keys. When the first write
     * fails, as on a full disk, the old switch's choice is used all the same, and the move is tried again at a later
     * read; when the second fails, the stored choice is used and the old keys go with the next choice made. A file
     * without the old switch is not written.
     */
    override val lanesOnMap: Flow<LanesOnMap> = preferences
        .transform { stored ->
            when (LanesOnMap.Migration.of(stored[LANES_ON_MAP], stored[LANE_DETAILS], stored[DRAW_OWN_LANES])) {
                LanesOnMap.Migration.NONE -> emit(lanesOnMapOf(stored[LANES_ON_MAP]))
                // Each write brings the next read, one step further.
                LanesOnMap.Migration.WRITE_CHOICE -> try {
                    migrateLanes()
                } catch (e: IOException) {
                    emit(LanesOnMap.fromLaneDetails(checkNotNull(stored[LANE_DETAILS])))
                }
                LanesOnMap.Migration.REMOVE_OLD_KEYS -> try {
                    migrateLanes()
                } catch (e: IOException) {
                    emit(lanesOnMapOf(stored[LANES_ON_MAP]))
                }
            }
        }
        .distinctUntilChanged()

    override suspend fun setLanesOnMap(choice: LanesOnMap) {
        val stored = choice.stored()
        // The old switches go in the same write: a choice made is never undone by what they said.
        dataStore.edit {
            it[LANES_ON_MAP] = stored
            it.remove(LANE_DETAILS)
            it.remove(DRAW_OWN_LANES)
        }
        onChanged(LANES_ON_MAP.name, stored)
    }

    /** One step of the move from the old switch, in one write; the choice it wrote is reported as a change. */
    private suspend fun migrateLanes() {
        var written: String? = null
        dataStore.edit { written = migrateLanesOneStep(it) }
        written?.let { onChanged(LANES_ON_MAP.name, it) }
    }

    /** The next step of [LanesOnMap.Migration] on [preferences], and only that one; the word of a choice it wrote, else null. */
    private fun migrateLanesOneStep(preferences: MutablePreferences): String? =
        when (LanesOnMap.Migration.of(preferences[LANES_ON_MAP], preferences[LANE_DETAILS], preferences[DRAW_OWN_LANES])) {
            LanesOnMap.Migration.NONE -> null
            LanesOnMap.Migration.WRITE_CHOICE -> LanesOnMap.fromLaneDetails(checkNotNull(preferences[LANE_DETAILS])).stored().also {
                preferences[LANES_ON_MAP] = it
            }
            LanesOnMap.Migration.REMOVE_OLD_KEYS -> {
                preferences.remove(LANE_DETAILS)
                preferences.remove(DRAW_OWN_LANES)
                null
            }
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

    override val measureOwnLanes: Flow<Boolean> = preferences.map { it[MEASURE_OWN_LANES] ?: false }.distinctUntilChanged()

    override suspend fun setMeasureOwnLanes(enabled: Boolean) {
        dataStore.edit { it[MEASURE_OWN_LANES] = enabled }
        onChanged(MEASURE_OWN_LANES.name, enabled)
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
        private val LANES_ON_MAP = stringPreferencesKey("lanes_on_map")
        private val DAY_NIGHT = stringPreferencesKey("day_night")
        private val OWN_ROUTE_LINE = booleanPreferencesKey("own_route_line")
        private val MEASURE_OWN_LANES = booleanPreferencesKey("measure_own_lanes")
        private val CAR_MAP_FPS = intPreferencesKey("car_map_fps")

        /** The switch Lane details on the map of the versions before [LANES_ON_MAP]: read once, to carry it over. */
        private val LANE_DETAILS = booleanPreferencesKey("lane_details")

        /** The Debug switch Draw the app's lanes of those versions: never carried over, only removed. */
        private val DRAW_OWN_LANES = booleanPreferencesKey("draw_own_lanes")

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

        private fun LanesOnMap.stored(): String = when (this) {
            LanesOnMap.OFF -> "off"
            LanesOnMap.MAPBOX -> "mapbox"
            LanesOnMap.APP -> "app"
        }

        private fun lanesOnMapOf(stored: String?): LanesOnMap = when (stored) {
            null -> LanesOnMap.DEFAULT
            else -> LanesOnMap.entries.firstOrNull { it.stored() == stored } ?: LanesOnMap.OFF
        }

        private fun LookChoice.stored(): String = when (this) {
            LookChoice.AUTO -> "auto"
            LookChoice.DAY -> "day"
            LookChoice.NIGHT -> "night"
        }

        private fun lookChoiceOf(stored: String?): LookChoice =
            LookChoice.entries.firstOrNull { it.stored() == stored } ?: LookChoice.DEFAULT
    }
}
