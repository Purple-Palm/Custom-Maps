package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    fun `starts with a real drive, the Smooth follow camera, the Default lane zoom and no lane details`() = runBlocking {
        assertFalse(settings.simulateDrive.first())
        assertEquals(CameraStyle.SMOOTH_FOLLOW, settings.cameraStyle.first())
        assertEquals(LaneZoom.DEFAULT, settings.laneZoom.first())
        assertFalse(settings.laneDetails.first())
        assertFalse(settings.ownRouteLine.first(), "the SDK draws the route line unless the Debug switch says otherwise")
        assertFalse(settings.drawOwnLanes.first(), "the app draws no lanes unless the Debug switch says otherwise")
    }

    @Test
    fun `stores the own lanes switch under its own key`() = runBlocking {
        settings.setDrawOwnLanes(true)
        assertTrue(settings.drawOwnLanes.first())
        assertEquals(true, dataStore.data.first()[booleanPreferencesKey("draw_own_lanes")])
        assertFalse(settings.ownRouteLine.first(), "the own route line's switch is its own")
        assertFalse(settings.laneDetails.first())
        settings.setDrawOwnLanes(false)
        assertFalse(settings.drawOwnLanes.first())
    }

    @Test
    fun `stores the own route line switch under its own key`() = runBlocking {
        settings.setOwnRouteLine(true)
        assertTrue(settings.ownRouteLine.first())
        assertEquals(true, dataStore.data.first()[booleanPreferencesKey("own_route_line")])
        assertFalse(settings.simulateDrive.first(), "the other Debug switch is its own")
        assertFalse(settings.laneDetails.first())
        settings.setOwnRouteLine(false)
        assertFalse(settings.ownRouteLine.first())
    }

    @Test
    fun `stores the lane details switch`() = runBlocking {
        settings.setLaneDetails(true)
        assertTrue(settings.laneDetails.first())
        assertFalse(settings.simulateDrive.first(), "the other switch is its own")
        settings.setLaneDetails(false)
        assertFalse(settings.laneDetails.first())
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
        assertFalse(settings.laneDetails.first())
        settings.setLaneDetails(true)
        assertEquals(LaneZoom.LARGE, settings.laneZoom.first())
        assertTrue(settings.simulateDrive.first())
        settings.setDayNight(LookChoice.DAY)
        assertEquals(CameraStyle.MAPBOX_TUNED, settings.cameraStyle.first())
        assertTrue(settings.laneDetails.first())
        settings.setLaneZoom(LaneZoom.DEFAULT)
        assertEquals(LookChoice.DAY, settings.dayNight.first())
    }

    @Test
    fun `reports each stored change with its key and the value as stored`() = runBlocking {
        settings.setSimulateDrive(true)
        settings.setCameraStyle(CameraStyle.MAPBOX_TUNED)
        settings.setLaneZoom(LaneZoom.LARGE)
        settings.setLaneDetails(true)
        settings.setDayNight(LookChoice.NIGHT)
        settings.setDayNight(LookChoice.AUTO)
        settings.setOwnRouteLine(true)
        assertEquals(
            listOf<Pair<String, Any>>(
                "simulate_drive" to true,
                "camera_style" to "mapbox_tuned",
                "lane_zoom" to "large",
                "lane_details" to true,
                "day_night" to "night",
                "day_night" to "auto",
                "own_route_line" to true,
            ),
            changes,
        )
    }
}
