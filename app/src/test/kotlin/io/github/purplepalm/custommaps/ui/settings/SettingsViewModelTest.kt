package io.github.purplepalm.custommaps.ui.settings

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.trip.LogBegan
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.FakeAppSettings
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone)

    private val tripLog = object : TripLogStore {
        val log = MutableStateFlow<List<TripSessionStart>>(emptyList())
        override val entries = log

        override suspend fun record(start: TripSessionStart) {
            log.value = log.value + start
        }
    }

    private val settings = FakeAppSettings()

    private val archive = object : DebugLogArchive {
        var bytes = 1_234L
        var cleared = false
        val logged = mutableListOf<String>()

        override fun log(type: String, fields: Map<String, Any?>) {
            logged += type
        }

        override suspend fun sizeBytes(): Long = bytes

        override suspend fun export(header: Map<String, Any?>): List<File> =
            if (bytes == 0L) emptyList() else listOf(File("custom-maps-debug-2026-11-03.txt"))

        override suspend fun clear() {
            cleared = true
            bytes = 0
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun trip(daysAgo: Long, simulated: Boolean = false) =
        TripSessionStart(now.minusDays(daysAgo).toInstant(), TripKind.ACTIVE_GUIDANCE, simulated)

    private var installedAt = now.minusYears(1).toInstant()

    private fun TestScope.viewModel() = SettingsViewModel(tripLog, settings, archive, now = { now }, installedAt = { installedAt }).also {
        it.debug.launchIn(backgroundScope)
        it.guidance.launchIn(backgroundScope)
        runCurrent()
    }

    @Test
    fun `counts only this month's trip sessions`() = runTest(dispatcher) {
        tripLog.log.value = listOf(trip(daysAgo = 40), trip(daysAgo = 2), trip(daysAgo = 1, simulated = true))
        val viewModel = viewModel()
        assertEquals(2, viewModel.debug.value.trips.count)
        assertEquals(null, viewModel.debug.value.trips.partialSince)
    }

    @Test
    fun `after an installation this month the count says since when`() = runTest(dispatcher) {
        // Installed again on the first of the month, in the afternoon; the trips of that morning are gone.
        installedAt = now.withDayOfMonth(1).withHour(15).toInstant()
        tripLog.log.value = listOf(trip(daysAgo = 1))
        val viewModel = viewModel()

        val trips = viewModel.debug.value.trips
        assertEquals(1, trips.count)
        assertEquals(now.toLocalDate().withDayOfMonth(1), trips.partialSince)
        assertEquals(LogBegan.INSTALLATION, trips.began)
        assertEquals(R.string.debug_trips_since_installation, tripsSinceRes(trips.began))
        assertEquals(R.string.debug_trips_since_oldest_kept, tripsSinceRes(LogBegan.OLDEST_KEPT))
    }

    @Test
    fun `lists the newest trip sessions first`() = runTest(dispatcher) {
        val trips = (9L downTo 0L).map { trip(daysAgo = it) }
        tripLog.log.value = trips
        val viewModel = viewModel()
        val recent = viewModel.debug.value.recentTrips
        assertEquals(5, recent.size)
        assertEquals(trips.last(), recent.first())
        assertTrue(recent.zipWithNext().all { (newer, older) -> newer.startedAt > older.startedAt })
    }

    @Test
    fun `a new trip session shows up at once`() = runTest(dispatcher) {
        val viewModel = viewModel()
        tripLog.record(trip(daysAgo = 0))
        runCurrent()
        assertEquals(1, viewModel.debug.value.trips.count)
    }

    @Test
    fun `the debug log size is measured when settings opens`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.refreshLogSize()
        runCurrent()
        assertEquals(1_234L, viewModel.debug.value.logBytes)
    }

    @Test
    fun `clearing the debug log shows it as empty`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.clearLog()
        runCurrent()
        assertTrue(archive.cleared)
        assertEquals(0L, viewModel.debug.value.logBytes)
    }

    @Test
    fun `a switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setSimulateDrive(true)
        runCurrent()
        assertFalse(viewModel.debug.value.simulateDrive)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `shows the stored camera style`() = runTest(dispatcher) {
        settings.cameraStyle.value = CameraStyle.MAPBOX_TUNED
        val viewModel = viewModel()
        assertEquals(CameraStyle.MAPBOX_TUNED, viewModel.guidance.value.cameraStyle)
    }

    @Test
    fun `a chosen camera style is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(CameraStyle.SMOOTH_FOLLOW, viewModel.guidance.value.cameraStyle)
        viewModel.setCameraStyle(CameraStyle.MAPBOX_STANDARD)
        runCurrent()
        assertEquals(CameraStyle.MAPBOX_STANDARD, settings.cameraStyle.value)
        assertEquals(CameraStyle.MAPBOX_STANDARD, viewModel.guidance.value.cameraStyle)
    }

    @Test
    fun `a camera style that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setCameraStyle(CameraStyle.MAPBOX_TUNED)
        runCurrent()
        assertEquals(CameraStyle.SMOOTH_FOLLOW, settings.cameraStyle.value)
        assertEquals(CameraStyle.SMOOTH_FOLLOW, viewModel.guidance.value.cameraStyle)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `shows the stored lane zoom next to the camera style`() = runTest(dispatcher) {
        settings.laneZoom.value = LaneZoom.LARGE
        settings.cameraStyle.value = CameraStyle.MAPBOX_STANDARD
        val viewModel = viewModel()
        assertEquals(GuidanceSettings(CameraStyle.MAPBOX_STANDARD, LaneZoom.LARGE), viewModel.guidance.value)
    }

    @Test
    fun `lane details are off until switched on, and the switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertFalse(viewModel.guidance.value.laneDetails)
        viewModel.setLaneDetails(true)
        runCurrent()
        assertTrue(settings.laneDetails.value)
        assertEquals(GuidanceSettings(CameraStyle.SMOOTH_FOLLOW, LaneZoom.DEFAULT, laneDetails = true), viewModel.guidance.value)
        viewModel.setLaneDetails(false)
        runCurrent()
        assertFalse(viewModel.guidance.value.laneDetails)
    }

    @Test
    fun `shows the stored lane details switch`() = runTest(dispatcher) {
        settings.laneDetails.value = true
        val viewModel = viewModel()
        assertTrue(viewModel.guidance.value.laneDetails)
    }

    @Test
    fun `a lane details switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setLaneDetails(true)
        runCurrent()
        assertFalse(settings.laneDetails.value)
        assertFalse(viewModel.guidance.value.laneDetails)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `a chosen lane zoom is stored and leaves the camera style alone`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(LaneZoom.DEFAULT, viewModel.guidance.value.laneZoom)
        viewModel.setLaneZoom(LaneZoom.LARGE)
        runCurrent()
        assertEquals(LaneZoom.LARGE, settings.laneZoom.value)
        assertEquals(GuidanceSettings(CameraStyle.SMOOTH_FOLLOW, LaneZoom.LARGE), viewModel.guidance.value)
        viewModel.setLaneZoom(LaneZoom.DEFAULT)
        runCurrent()
        assertEquals(LaneZoom.DEFAULT, viewModel.guidance.value.laneZoom)
    }

    @Test
    fun `a lane zoom that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setLaneZoom(LaneZoom.LARGE)
        runCurrent()
        assertEquals(LaneZoom.DEFAULT, settings.laneZoom.value)
        assertEquals(LaneZoom.DEFAULT, viewModel.guidance.value.laneZoom)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `the simulated drive switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setSimulateDrive(true)
        runCurrent()
        assertTrue(settings.simulateDrive.value)
        assertTrue(viewModel.debug.value.simulateDrive)
    }
}
