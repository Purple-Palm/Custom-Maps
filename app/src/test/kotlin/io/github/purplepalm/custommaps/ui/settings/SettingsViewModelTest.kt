package io.github.purplepalm.custommaps.ui.settings

import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.AppSettings
import java.io.File
import java.io.IOException
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

    private val settings = object : AppSettings {
        val simulate = MutableStateFlow(false)
        var storageFull = false
        override val simulateDrive = simulate

        override suspend fun setSimulateDrive(enabled: Boolean) {
            if (storageFull) throw IOException("No space left on device")
            simulate.value = enabled
        }
    }

    private val archive = object : DebugLogArchive {
        var bytes = 1_234L
        var cleared = false
        val logged = mutableListOf<String>()

        override fun log(type: String, fields: Map<String, Any?>) {
            logged += type
        }

        override suspend fun sizeBytes(): Long = bytes

        override suspend fun export(target: File, header: Map<String, Any?>): List<File> =
            if (bytes == 0L) emptyList() else listOf(File(target, "custom-maps-debug-2026-11-03.txt"))

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

    private fun TestScope.viewModel() = SettingsViewModel(tripLog, settings, archive) { now }.also {
        it.debug.launchIn(backgroundScope)
        runCurrent()
    }

    @Test
    fun `counts only this month's trip sessions`() = runTest(dispatcher) {
        tripLog.log.value = listOf(trip(daysAgo = 40), trip(daysAgo = 2), trip(daysAgo = 1, simulated = true))
        val viewModel = viewModel()
        assertEquals(2, viewModel.debug.value.tripsThisMonth)
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
        assertEquals(1, viewModel.debug.value.tripsThisMonth)
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
    fun `the simulated drive switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setSimulateDrive(true)
        runCurrent()
        assertTrue(settings.simulate.value)
        assertTrue(viewModel.debug.value.simulateDrive)
    }
}
