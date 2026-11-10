package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.nav.model.CameraStyle
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
    fun `starts with a real drive and the Smooth follow camera`() = runBlocking {
        assertFalse(settings.simulateDrive.first())
        assertEquals(CameraStyle.SMOOTH_FOLLOW, settings.cameraStyle.first())
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
    fun `one setting does not change the other`() = runBlocking {
        settings.setCameraStyle(CameraStyle.MAPBOX_STANDARD)
        settings.setSimulateDrive(true)
        assertEquals(CameraStyle.MAPBOX_STANDARD, settings.cameraStyle.first())
        assertTrue(settings.simulateDrive.first())
    }

    @Test
    fun `reports each stored change with its key and the value as stored`() = runBlocking {
        settings.setSimulateDrive(true)
        settings.setCameraStyle(CameraStyle.MAPBOX_TUNED)
        assertEquals(listOf<Pair<String, Any>>("simulate_drive" to true, "camera_style" to "mapbox_tuned"), changes)
    }
}
