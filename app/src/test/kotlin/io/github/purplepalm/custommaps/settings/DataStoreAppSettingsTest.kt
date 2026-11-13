package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreAppSettingsTest {

    @TempDir
    lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: DataStoreAppSettings
    private val changes = mutableListOf<Pair<String, Any>>()

    /** The key as the file holds it, to read and write what the settings do not go through. */
    private val storedCameraStyle = stringPreferencesKey("camera_style")
    private val storedLaneZoom = stringPreferencesKey("lane_zoom")
    private val storedDayNight = stringPreferencesKey("day_night")
    private val storedLanesOnMap = stringPreferencesKey("lanes_on_map")

    /** The switches of the versions before Lanes on the map, as their files hold them. */
    private val oldLaneDetails = booleanPreferencesKey("lane_details")
    private val oldDrawOwnLanes = booleanPreferencesKey("draw_own_lanes")

    @BeforeEach
    fun createSettings() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "app_settings.preferences_pb") },
        )
        settings = DataStoreAppSettings(dataStore) { key, value -> changes += key to value }
    }

    @AfterEach
    fun closeSettings() {
        scope.cancel()
    }

    @Test
    fun `starts with a real drive, the Smooth follow camera, the Default lane zoom and no lanes on the map`() = runBlocking {
        assertFalse(settings.simulateDrive.first())
        assertEquals(CameraStyle.SMOOTH_FOLLOW, settings.cameraStyle.first())
        assertEquals(LaneZoom.DEFAULT, settings.laneZoom.first())
        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())
        assertFalse(settings.ownRouteLine.first(), "the SDK draws the route line unless the Debug switch says otherwise")
        // Read, never written: a later default reaches a phone that never chose.
        assertEquals(emptyMap<String, Any>(), raw())
        assertEquals(emptyList<Pair<String, Any>>(), changes)
    }

    @Test
    fun `stores each choice of Lanes on the map under its own word, each change reported`() = runBlocking {
        val words = mapOf(LanesOnMap.OFF to "off", LanesOnMap.MAPBOX to "mapbox", LanesOnMap.APP to "app")
        assertEquals(LanesOnMap.entries.toSet(), words.keys)
        // Off last: it is also what an empty file reads as.
        for (choice in listOf(LanesOnMap.MAPBOX, LanesOnMap.APP, LanesOnMap.OFF)) {
            settings.setLanesOnMap(choice)
            assertEquals(choice, settings.lanesOnMap.first())
            assertEquals(mapOf("lanes_on_map" to words[choice]), raw())
        }
        assertEquals(listOf<Pair<String, Any>>("lanes_on_map" to "mapbox", "lanes_on_map" to "app", "lanes_on_map" to "off"), changes)
        assertFalse(settings.ownRouteLine.first(), "the own route line's switch is its own")
    }

    @Test
    fun `a Lanes on the map word it does not know reads as Off`() = runBlocking {
        settings.setLanesOnMap(LanesOnMap.APP)
        // As a file from a build with other choices would hold it, and one that named them otherwise.
        for (word in listOf("streets", "APP", "true", "")) {
            dataStore.edit { it[storedLanesOnMap] = word }
            assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first(), word)
        }
    }

    @Test
    fun `the old switch on becomes Mapbox's lane details, written once, and its key goes`() = runBlocking {
        dataStore.edit { it[oldLaneDetails] = true }

        assertEquals(LanesOnMap.MAPBOX, settings.lanesOnMap.first())

        assertEquals(mapOf("lanes_on_map" to "mapbox"), raw())
        assertEquals(listOf<Pair<String, Any>>("lanes_on_map" to "mapbox"), changes)
        // Read again, as at the next start: the same choice, nothing written.
        assertEquals(LanesOnMap.MAPBOX, DataStoreAppSettings(dataStore) { key, value -> changes += key to value }.lanesOnMap.first())
        assertEquals(1, changes.size)
    }

    @Test
    fun `the old switch off becomes Off, written, so the old key never counts again`() = runBlocking {
        dataStore.edit { it[oldLaneDetails] = false }

        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())

        assertEquals(mapOf("lanes_on_map" to "off"), raw())
        assertEquals(listOf<Pair<String, Any>>("lanes_on_map" to "off"), changes)
    }

    @Test
    fun `the move writes the choice first and removes the old keys last, the old Debug switch's with them`() = runBlocking {
        dataStore.edit {
            it[oldLaneDetails] = true
            it[oldDrawOwnLanes] = true
        }
        val probe = Probe(dataStore)

        assertEquals(LanesOnMap.MAPBOX, DataStoreAppSettings(probe).lanesOnMap.first())

        assertEquals(
            listOf(setOf("lane_details", "draw_own_lanes", "lanes_on_map"), setOf("lanes_on_map")),
            probe.written,
        )
        assertEquals(mapOf("lanes_on_map" to "mapbox"), raw(), "the old Debug switch is never carried over")
    }

    @Test
    fun `a process that dies between the two writes loses nothing and writes the choice once`() = runBlocking {
        dataStore.edit { it[oldLaneDetails] = true }

        // The second write, the one that removes the old key, never happens.
        val first = DataStoreAppSettings(Probe(dataStore, failingWrite = 2)) { key, value -> changes += key to value }.lanesOnMap.first()
        assertEquals(LanesOnMap.MAPBOX, first)
        assertEquals(mapOf("lane_details" to true, "lanes_on_map" to "mapbox"), raw(), "both keys are there, as after a crash")

        // The next start reads both keys: the choice is there already, so only the old key goes.
        assertEquals(LanesOnMap.MAPBOX, settings.lanesOnMap.first())
        assertEquals(mapOf("lanes_on_map" to "mapbox"), raw())
        assertEquals(listOf<Pair<String, Any>>("lanes_on_map" to "mapbox"), changes, "the choice was reported once")
    }

    @Test
    fun `a file that cannot be written still gives the old switch's choice, and the move waits for a later read`() = runBlocking {
        dataStore.edit { it[oldLaneDetails] = true }

        // Every write fails, as on a full disk; reading works.
        val shown = withTimeout(5_000) { DataStoreAppSettings(Probe(dataStore, diskFull = true)).lanesOnMap.first() }

        assertEquals(LanesOnMap.MAPBOX, shown)
        assertEquals(mapOf("lane_details" to true), raw(), "the old key stays for a later move")
        // With room again, as at the next start, the move is made.
        assertEquals(LanesOnMap.MAPBOX, settings.lanesOnMap.first())
        assertEquals(mapOf("lanes_on_map" to "mapbox"), raw())
    }

    @Test
    fun `a choice stored next to an old switch an older version wrote again stays, and the old keys go`() = runBlocking {
        dataStore.edit {
            it[storedLanesOnMap] = "app"
            it[oldLaneDetails] = false
        }

        assertEquals(LanesOnMap.APP, settings.lanesOnMap.first())

        assertEquals(mapOf("lanes_on_map" to "app"), raw())
        assertEquals(emptyList<Pair<String, Any>>(), changes, "no choice was written")
    }

    @Test
    fun `the old Debug switch alone is never carried over, and the next choice takes it away`() = runBlocking {
        dataStore.edit { it[oldDrawOwnLanes] = true }

        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())
        assertEquals(mapOf("draw_own_lanes" to true), raw(), "nothing is written while nothing was chosen")

        settings.setLanesOnMap(LanesOnMap.APP)
        assertEquals(mapOf("lanes_on_map" to "app"), raw())
    }

    @Test
    fun `two readers at once carry the old switch over once`() = runBlocking {
        dataStore.edit { it[oldLaneDetails] = true }

        val one = async { settings.lanesOnMap.first() }
        val two = async { DataStoreAppSettings(dataStore) { key, value -> changes += key to value }.lanesOnMap.first() }

        assertEquals(LanesOnMap.MAPBOX, one.await())
        assertEquals(LanesOnMap.MAPBOX, two.await())
        assertEquals(mapOf("lanes_on_map" to "mapbox"), raw())
        assertEquals(listOf<Pair<String, Any>>("lanes_on_map" to "mapbox"), changes, "one of the two wrote it")
    }

    @Test
    fun `a choice made after a crash in the move removes the old keys in the same write`() = runBlocking {
        // The state a process that died between the move's two writes leaves.
        dataStore.edit {
            it[oldLaneDetails] = true
            it[storedLanesOnMap] = "mapbox"
        }
        val probe = Probe(dataStore)

        DataStoreAppSettings(probe).setLanesOnMap(LanesOnMap.OFF)

        assertEquals(listOf(setOf("lanes_on_map")), probe.written)
        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())
    }

    @Test
    fun `the measuring switch is off until turned on, stored under its own key, each change reported`() = runBlocking {
        assertFalse(settings.measureOwnLanes.first())
        settings.setMeasureOwnLanes(true)
        assertTrue(settings.measureOwnLanes.first())
        assertEquals(true, dataStore.data.first()[booleanPreferencesKey("measure_own_lanes")])
        assertFalse(settings.ownRouteLine.first(), "the other Debug switches are their own")
        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first(), "the switch chooses no lanes")
        settings.setMeasureOwnLanes(false)
        assertFalse(settings.measureOwnLanes.first())
        assertEquals(listOf<Pair<String, Any>>("measure_own_lanes" to true, "measure_own_lanes" to false), changes)
    }

    @Test
    fun `the car's map draws 30 frames a second until the Debug switch says 60, stored under its own key`() = runBlocking {
        assertEquals(30, settings.carMapFps.first())
        settings.setCarMapFps(60)
        assertEquals(60, settings.carMapFps.first())
        assertEquals(60, dataStore.data.first()[intPreferencesKey("car_map_fps")])
        assertFalse(settings.ownRouteLine.first(), "the other Debug switch is its own")
        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())
        settings.setCarMapFps(30)
        assertEquals(30, settings.carMapFps.first())
        assertEquals(listOf<Pair<String, Any>>("car_map_fps" to 60, "car_map_fps" to 30), changes)
    }

    @Test
    fun `a number of frames the switch does not offer reads and stores as 30`() = runBlocking {
        settings.setCarMapFps(60)
        // As a file from a build with other numbers would hold it.
        for (stored in listOf(45, 0, 120, -60)) {
            dataStore.edit { it[intPreferencesKey("car_map_fps")] = stored }
            assertEquals(30, settings.carMapFps.first(), "$stored")
        }
        settings.setCarMapFps(90)
        assertEquals(30, dataStore.data.first()[intPreferencesKey("car_map_fps")])
        assertEquals("car_map_fps" to 30, changes.last())
    }

    @Test
    fun `stores the own route line switch under its own key`() = runBlocking {
        settings.setOwnRouteLine(true)
        assertTrue(settings.ownRouteLine.first())
        assertEquals(true, dataStore.data.first()[booleanPreferencesKey("own_route_line")])
        assertFalse(settings.simulateDrive.first(), "the other Debug switch is its own")
        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())
        settings.setOwnRouteLine(false)
        assertFalse(settings.ownRouteLine.first())
    }

    @Test
    fun `stores the simulated drive switch`() = runBlocking {
        settings.setSimulateDrive(true)
        assertTrue(settings.simulateDrive.first())
        settings.setSimulateDrive(false)
        assertFalse(settings.simulateDrive.first())
    }

    @Test
    fun `stores each camera style under its own word`() = runBlocking {
        val words = mapOf(
            CameraStyle.MAPBOX_STANDARD to "mapbox_standard",
            CameraStyle.MAPBOX_TUNED to "mapbox_tuned",
            CameraStyle.SMOOTH_FOLLOW to "smooth_follow",
        )
        assertEquals(CameraStyle.entries.toSet(), words.keys)
        for ((style, word) in words) {
            settings.setCameraStyle(style)
            assertEquals(style, settings.cameraStyle.first())
            assertEquals(word, dataStore.data.first()[storedCameraStyle])
        }
    }

    @Test
    fun `a camera style it does not know reads as Smooth follow`() = runBlocking {
        settings.setCameraStyle(CameraStyle.MAPBOX_TUNED)
        // As a file from a build with other styles would hold it.
        dataStore.edit { it[storedCameraStyle] = "follow_the_road" }
        assertEquals(CameraStyle.SMOOTH_FOLLOW, settings.cameraStyle.first())
    }

    @Test
    fun `stores each lane zoom under its own word`() = runBlocking {
        val words = mapOf(LaneZoom.DEFAULT to "default", LaneZoom.LARGE to "large")
        assertEquals(LaneZoom.entries.toSet(), words.keys)
        // Large first: Default is also what an empty file reads as.
        for (mode in listOf(LaneZoom.LARGE, LaneZoom.DEFAULT)) {
            settings.setLaneZoom(mode)
            assertEquals(mode, settings.laneZoom.first())
            assertEquals(words[mode], dataStore.data.first()[storedLaneZoom])
        }
    }

    @Test
    fun `a lane zoom it does not know reads as Default`() = runBlocking {
        settings.setLaneZoom(LaneZoom.LARGE)
        // As a file from a build with other choices would hold it, and one that named them otherwise.
        for (word in listOf("huge", "normal", "LARGE", "")) {
            dataStore.edit { it[storedLaneZoom] = word }
            assertEquals(LaneZoom.DEFAULT, settings.laneZoom.first(), word)
        }
    }

    @Test
    fun `stores each day and night choice under its own word, Auto at first`() = runBlocking {
        assertEquals(LookChoice.AUTO, settings.dayNight.first())
        val words = mapOf(LookChoice.AUTO to "auto", LookChoice.DAY to "day", LookChoice.NIGHT to "night")
        assertEquals(LookChoice.entries.toSet(), words.keys)
        // Auto last: it is also what an empty file reads as.
        for (choice in listOf(LookChoice.DAY, LookChoice.NIGHT, LookChoice.AUTO)) {
            settings.setDayNight(choice)
            assertEquals(choice, settings.dayNight.first())
            assertEquals(words[choice], dataStore.data.first()[storedDayNight])
        }
    }

    @Test
    fun `a day and night word it does not know reads as Auto`() = runBlocking {
        settings.setDayNight(LookChoice.NIGHT)
        // As a file from a build with other choices would hold it, and one that named them otherwise.
        for (word in listOf("dusk", "system", "NIGHT", "")) {
            dataStore.edit { it[storedDayNight] = word }
            assertEquals(LookChoice.AUTO, settings.dayNight.first(), word)
        }
    }

    @Test
    fun `one setting does not change the other`() = runBlocking {
        settings.setCameraStyle(CameraStyle.MAPBOX_STANDARD)
        settings.setSimulateDrive(true)
        settings.setLaneZoom(LaneZoom.LARGE)
        assertEquals(CameraStyle.MAPBOX_STANDARD, settings.cameraStyle.first())
        assertTrue(settings.simulateDrive.first())
        assertEquals(LaneZoom.LARGE, settings.laneZoom.first())
        settings.setCameraStyle(CameraStyle.MAPBOX_TUNED)
        assertEquals(LaneZoom.LARGE, settings.laneZoom.first())
        assertEquals(LanesOnMap.OFF, settings.lanesOnMap.first())
        settings.setLanesOnMap(LanesOnMap.APP)
        assertEquals(LaneZoom.LARGE, settings.laneZoom.first())
        assertTrue(settings.simulateDrive.first())
        settings.setDayNight(LookChoice.DAY)
        assertEquals(CameraStyle.MAPBOX_TUNED, settings.cameraStyle.first())
        assertEquals(LanesOnMap.APP, settings.lanesOnMap.first())
        settings.setLaneZoom(LaneZoom.DEFAULT)
        assertEquals(LookChoice.DAY, settings.dayNight.first())
    }

    @Test
    fun `reports each stored change with its key and the value as stored`() = runBlocking {
        settings.setSimulateDrive(true)
        settings.setCameraStyle(CameraStyle.MAPBOX_TUNED)
        settings.setLaneZoom(LaneZoom.LARGE)
        settings.setLanesOnMap(LanesOnMap.MAPBOX)
        settings.setDayNight(LookChoice.NIGHT)
        settings.setDayNight(LookChoice.AUTO)
        settings.setOwnRouteLine(true)
        assertEquals(
            listOf<Pair<String, Any>>(
                "simulate_drive" to true,
                "camera_style" to "mapbox_tuned",
                "lane_zoom" to "large",
                "lanes_on_map" to "mapbox",
                "day_night" to "night",
                "day_night" to "auto",
                "own_route_line" to true,
            ),
            changes,
        )
    }

    /** What the file holds, key by key. */
    private suspend fun raw(): Map<String, Any> = dataStore.data.first().asMap().mapKeys { it.key.name }

    /**
     * The settings file with a write that fails, as a process that dies there leaves it, or with every write failing, as
     * on a full disk; and the keys held after each write.
     */
    private class Probe(
        private val file: DataStore<Preferences>,
        private val failingWrite: Int? = null,
        private val diskFull: Boolean = false,
    ) : DataStore<Preferences> {
        private val writes = AtomicInteger()
        val written = CopyOnWriteArrayList<Set<String>>()

        override val data: Flow<Preferences> = file.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (diskFull) throw IOException("No space left on device")
            if (writes.incrementAndGet() == failingWrite) throw IOException("The process died here")
            return file.updateData(transform).also { written += it.asMap().keys.map { key -> key.name }.toSet() }
        }
    }
}
