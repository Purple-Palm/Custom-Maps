package io.github.purplepalm.custommaps.ui.settings

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.nav.car.CarMapSize
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.trip.LogBegan
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.FakeAppSettings
import io.github.purplepalm.custommaps.settings.SoundSettings
import io.github.purplepalm.custommaps.sound.ToneResult
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenTrips
import io.github.purplepalm.custommaps.token.TripsOf
import java.io.File
import java.time.LocalTime
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
        val recent = viewModel.debug.value.recentTrips.map { it.start }
        assertEquals(5, recent.size)
        assertEquals(trips.last(), recent.first())
        assertTrue(recent.zipWithNext().all { (newer, older) -> newer.startedAt > older.startedAt })
    }

    /** Two saved tokens, Main (t1, active) and Test (t2), with clearly fake values. */
    private val twoTokens = TokenList.EMPTY.add(null, "pk.dGVzdA.c2ln1", "t1").add("Test", "pk.dGVzdA.c2ln2", "t2")
    private val tokens = MutableStateFlow(twoTokens)

    private fun TestScope.viewModelWithTokens() =
        SettingsViewModel(tripLog, settings, archive, now = { now }, installedAt = { installedAt }, tokens = tokens).also {
            it.debug.launchIn(backgroundScope)
            runCurrent()
        }

    private fun trip(daysAgo: Long, tokenId: String?) = trip(daysAgo).copy(tokenId = tokenId)

    @Test
    fun `counts the month's trip sessions for each token`() = runTest(dispatcher) {
        // Two drives on two tokens this month, one on Main last month.
        tripLog.log.value = listOf(trip(daysAgo = 40, tokenId = "t1"), trip(daysAgo = 2, tokenId = "t1"), trip(daysAgo = 1, tokenId = "t2"))
        val viewModel = viewModelWithTokens()

        val debug = viewModel.debug.value
        assertEquals(2, debug.trips.count)
        assertEquals(listOf(TokenTrips(TripsOf.Token("Main"), 1), TokenTrips(TripsOf.Token("Test"), 1)), debug.tripsByToken)
    }

    @Test
    fun `sessions from before tokens were named, and those of deleted tokens, keep counting`() = runTest(dispatcher) {
        tripLog.log.value = listOf(trip(daysAgo = 2, tokenId = null), trip(daysAgo = 2, tokenId = "gone"), trip(daysAgo = 1, tokenId = "t2"))
        val viewModel = viewModelWithTokens()

        assertEquals(
            listOf(
                TokenTrips(TripsOf.Token("Main"), 0),
                TokenTrips(TripsOf.Token("Test"), 1),
                TokenTrips(TripsOf.Deleted, 1),
                TokenTrips(TripsOf.BeforeNames, 1),
            ),
            viewModel.debug.value.tripsByToken,
        )

        // Test deleted: its session counts as a deleted token's from then on.
        tokens.value = twoTokens.remove("t2")
        runCurrent()
        assertEquals(
            listOf(TokenTrips(TripsOf.Token("Main"), 0), TokenTrips(TripsOf.Deleted, 2), TokenTrips(TripsOf.BeforeNames, 1)),
            viewModel.debug.value.tripsByToken,
        )
        assertEquals(3, viewModel.debug.value.trips.count)
    }

    @Test
    fun `one token with every trip on it shows no line by token`() = runTest(dispatcher) {
        tokens.value = twoTokens.remove("t2")
        tripLog.log.value = listOf(trip(daysAgo = 2, tokenId = "t1"))
        val viewModel = viewModelWithTokens()

        assertEquals(emptyList<TokenTrips>(), viewModel.debug.value.tripsByToken)
        assertEquals(1, viewModel.debug.value.trips.count)
    }

    @Test
    fun `recent trips say which token they ran on`() = runTest(dispatcher) {
        tripLog.log.value = listOf(trip(daysAgo = 3, tokenId = null), trip(daysAgo = 2, tokenId = "gone"), trip(daysAgo = 1, tokenId = "t2"))
        val viewModel = viewModelWithTokens()

        assertEquals(listOf(TripsOf.Token("Test"), TripsOf.Deleted, TripsOf.BeforeNames), viewModel.debug.value.recentTrips.map { it.token })
        // Names only reach the screen, never a token.
        assertFalse(viewModel.debug.value.toString().contains("pk."))
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
    fun `no lanes are on the map until chosen, and each of the three choices is stored and shown`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(LanesOnMap.OFF, viewModel.guidance.value.lanesOnMap)
        // Off last, after the other two, so each one is a change.
        for (choice in listOf(LanesOnMap.MAPBOX, LanesOnMap.APP, LanesOnMap.OFF)) {
            viewModel.setLanesOnMap(choice)
            runCurrent()
            assertEquals(choice, settings.lanesOnMap.value)
            assertEquals(GuidanceSettings(CameraStyle.SMOOTH_FOLLOW, LaneZoom.DEFAULT, lanesOnMap = choice), viewModel.guidance.value)
        }
        assertEquals(emptyList<String>(), archive.logged)
    }

    @Test
    fun `shows the stored choice of Lanes on the map, whichever it is`() = runTest(dispatcher) {
        for (choice in LanesOnMap.entries) {
            settings.lanesOnMap.value = choice
            val viewModel = viewModel()
            assertEquals(choice, viewModel.guidance.value.lanesOnMap)
        }
    }

    @Test
    fun `a choice of Lanes on the map that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.lanesOnMap.value = LanesOnMap.MAPBOX
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setLanesOnMap(LanesOnMap.APP)
        runCurrent()
        assertEquals(LanesOnMap.MAPBOX, settings.lanesOnMap.value)
        assertEquals(LanesOnMap.MAPBOX, viewModel.guidance.value.lanesOnMap)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `a migrated choice shows as stored, the old switch's on as Mapbox's lane details`() = runTest(dispatcher) {
        // What the settings give after carrying the old switch over (DataStoreAppSettingsTest has the move itself).
        settings.lanesOnMap.value = LanesOnMap.fromLaneDetails(true)
        assertEquals(LanesOnMap.MAPBOX, viewModel().guidance.value.lanesOnMap)
        settings.lanesOnMap.value = LanesOnMap.fromLaneDetails(false)
        assertEquals(LanesOnMap.OFF, viewModel().guidance.value.lanesOnMap)
    }

    @Test
    fun `the lane counts from the electronic horizon are on until switched off, and the switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.guidance.value.laneCountsHorizon)
        viewModel.setLaneCountsHorizon(false)
        runCurrent()
        assertFalse(settings.laneCountsHorizon.value)
        assertEquals(GuidanceSettings(CameraStyle.SMOOTH_FOLLOW, LaneZoom.DEFAULT, LanesOnMap.OFF, laneCountsHorizon = false), viewModel.guidance.value)
        viewModel.setLaneCountsHorizon(true)
        runCurrent()
        assertTrue(settings.laneCountsHorizon.value)
        assertTrue(viewModel.guidance.value.laneCountsHorizon)
        assertEquals(LanesOnMap.OFF, viewModel.guidance.value.lanesOnMap, "the switch chooses no lanes")
        assertEquals(emptyList<String>(), archive.logged)
    }

    @Test
    fun `shows the stored horizon switch, off as well as on`() = runTest(dispatcher) {
        for (on in listOf(false, true)) {
            settings.laneCountsHorizon.value = on
            assertEquals(on, viewModel().guidance.value.laneCountsHorizon)
        }
    }

    @Test
    fun `a horizon switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setLaneCountsHorizon(false)
        runCurrent()
        assertTrue(settings.laneCountsHorizon.value)
        assertTrue(viewModel.guidance.value.laneCountsHorizon)
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

    @Test
    fun `the own route line is off until switched on, and the switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertFalse(viewModel.debug.value.ownRouteLine)
        viewModel.setOwnRouteLine(true)
        runCurrent()
        assertTrue(settings.ownRouteLine.value)
        assertTrue(viewModel.debug.value.ownRouteLine)
        assertFalse(viewModel.debug.value.simulateDrive, "the other Debug switch is its own")
    }

    @Test
    fun `the measuring switch is off until switched on, and the switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertFalse(viewModel.debug.value.measureOwnLanes)
        viewModel.setMeasureOwnLanes(true)
        runCurrent()
        assertTrue(settings.measureOwnLanes.value)
        assertTrue(viewModel.debug.value.measureOwnLanes)
        assertFalse(viewModel.debug.value.ownRouteLine, "the other Debug switches are their own")
        assertEquals(LanesOnMap.OFF, viewModel.guidance.value.lanesOnMap, "the switch chooses no lanes")
    }

    @Test
    fun `a measuring switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setMeasureOwnLanes(true)
        runCurrent()
        assertFalse(viewModel.debug.value.measureOwnLanes)
        assertFalse(settings.measureOwnLanes.value)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `the side-road fields switch is off until switched on, and the switch is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertFalse(viewModel.debug.value.sideRoadFields)
        viewModel.setSideRoadFields(true)
        runCurrent()
        assertTrue(settings.sideRoadFields.value)
        assertTrue(viewModel.debug.value.sideRoadFields)
        assertFalse(viewModel.debug.value.measureOwnLanes, "the other Debug switches are their own")
        assertFalse(viewModel.debug.value.ownRouteLine)
        assertEquals(30, viewModel.debug.value.carMapFps)
        assertTrue(viewModel.debug.value.carLightWhenHot)
        viewModel.setSideRoadFields(false)
        runCurrent()
        assertFalse(viewModel.debug.value.sideRoadFields)
    }

    @Test
    fun `a side-road fields switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setSideRoadFields(true)
        runCurrent()
        assertFalse(viewModel.debug.value.sideRoadFields)
        assertFalse(settings.sideRoadFields.value)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `each Debug switch shows its own stored value`() = runTest(dispatcher) {
        // Every switch the other way from the one before it, so a switch read in another's place shows.
        settings.simulateDrive.value = true
        settings.ownRouteLine.value = false
        settings.measureOwnLanes.value = true
        settings.sideRoadFields.value = false
        settings.carMapFps.value = 60
        settings.carLightWhenHot.value = false
        val viewModel = viewModel()
        runCurrent()
        val debug = viewModel.debug.value
        assertEquals(
            listOf(true, false, true, false, 60, false),
            listOf(debug.simulateDrive, debug.ownRouteLine, debug.measureOwnLanes, debug.sideRoadFields, debug.carMapFps, debug.carLightWhenHot),
        )
        settings.simulateDrive.value = false
        settings.ownRouteLine.value = true
        settings.measureOwnLanes.value = false
        settings.sideRoadFields.value = true
        settings.carMapFps.value = 30
        settings.carLightWhenHot.value = true
        runCurrent()
        val after = viewModel.debug.value
        assertEquals(
            listOf(false, true, false, true, 30, true),
            listOf(after.simulateDrive, after.ownRouteLine, after.measureOwnLanes, after.sideRoadFields, after.carMapFps, after.carLightWhenHot),
        )
    }

    @Test
    fun `the car's map draws 30 frames a second until 60 is chosen, and the choice is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertEquals(30, viewModel.debug.value.carMapFps)
        viewModel.setCarMapFps(60)
        runCurrent()
        assertEquals(60, settings.carMapFps.value)
        assertEquals(60, viewModel.debug.value.carMapFps)
        assertFalse(viewModel.debug.value.ownRouteLine, "the other Debug switches are their own")
        viewModel.setCarMapFps(30)
        runCurrent()
        assertEquals(30, viewModel.debug.value.carMapFps)
    }

    @Test
    fun `a choice of the car map's frames that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setCarMapFps(60)
        runCurrent()
        assertEquals(30, viewModel.debug.value.carMapFps)
        assertEquals(30, settings.carMapFps.value)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `the car's map gets lighter when hot until the switch is turned off, and the choice is stored`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertTrue(viewModel.debug.value.carLightWhenHot)
        viewModel.setCarLightWhenHot(false)
        runCurrent()
        assertFalse(settings.carLightWhenHot.value)
        assertFalse(viewModel.debug.value.carLightWhenHot)
        assertEquals(30, viewModel.debug.value.carMapFps, "the other Debug switches are their own")
        assertFalse(viewModel.debug.value.measureOwnLanes)
        viewModel.setCarLightWhenHot(true)
        runCurrent()
        assertTrue(viewModel.debug.value.carLightWhenHot)
    }

    @Test
    fun `a lighter car map switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setCarLightWhenHot(false)
        runCurrent()
        assertTrue(viewModel.debug.value.carLightWhenHot)
        assertTrue(settings.carLightWhenHot.value)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `an own route line switch that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModel()
        viewModel.setOwnRouteLine(true)
        runCurrent()
        assertFalse(viewModel.debug.value.ownRouteLine)
        assertFalse(settings.ownRouteLine.value)
        assertEquals(listOf("error"), archive.logged)
    }

    /** The look on screen, and what the next change is asked with. */
    private val shown = MutableStateFlow(Look.DAY)
    private val asked = mutableListOf<Pair<LookChoice, Look>>()

    /** Invented times: the evening's for the day look, the morning's for the night look, none by hand. */
    private val nextLook: (LookChoice, Look) -> NextLook? = { choice, look ->
        asked += choice to look
        when {
            choice != LookChoice.AUTO -> null
            look == Look.DAY -> NextLook(Look.NIGHT, LocalTime.of(18, 5))
            else -> NextLook(Look.DAY, LocalTime.of(6, 20))
        }
    }

    private fun TestScope.viewModelWithLook() =
        SettingsViewModel(tripLog, settings, archive, now = { now }, installedAt = { installedAt }, shownLook = shown, nextLook = nextLook).also {
            it.look.launchIn(backgroundScope)
            runCurrent()
        }

    @Test
    fun `under Auto the look section says when the look changes next, and by hand it says nothing`() = runTest(dispatcher) {
        val viewModel = viewModelWithLook()
        assertEquals(LookSettings(LookChoice.AUTO, NextLook(Look.NIGHT, LocalTime.of(18, 5))), viewModel.look.value)
        // The look changes on screen: the next change is the morning's.
        shown.value = Look.NIGHT
        runCurrent()
        assertEquals(NextLook(Look.DAY, LocalTime.of(6, 20)), viewModel.look.value.next)
        // Day by hand: no next change.
        viewModel.setDayNight(LookChoice.DAY)
        runCurrent()
        assertEquals(LookChoice.DAY, settings.dayNight.value)
        assertEquals(LookSettings(LookChoice.DAY, null), viewModel.look.value)
        assertEquals(listOf(LookChoice.AUTO to Look.DAY, LookChoice.AUTO to Look.NIGHT, LookChoice.DAY to Look.NIGHT), asked)
    }

    @Test
    fun `a day and night choice that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModelWithLook()
        viewModel.setDayNight(LookChoice.NIGHT)
        runCurrent()
        assertEquals(LookChoice.AUTO, settings.dayNight.value)
        assertEquals(LookChoice.AUTO, viewModel.look.value.choice)
        assertEquals(listOf("error"), archive.logged)
    }

    private fun TestScope.viewModelWithValues() =
        SettingsViewModel(tripLog, settings, archive, now = { now }, installedAt = { installedAt }).also {
            it.values.launchIn(backgroundScope)
            runCurrent()
        }

    @Test
    fun `a switch of the catalogue stores through its own setter`() = runTest(dispatcher) {
        val viewModel = viewModelWithValues()
        val stored: Map<SettingId, () -> Boolean> = mapOf(
            SettingId.SIMULATE_DRIVE to { settings.simulateDrive.value },
            SettingId.OWN_ROUTE_LINE to { settings.ownRouteLine.value },
            SettingId.MEASURE_OWN_LANES to { settings.measureOwnLanes.value },
            SettingId.SIDE_ROAD_FIELDS to { settings.sideRoadFields.value },
            SettingId.CAR_LIGHT_WHEN_HOT to { settings.carLightWhenHot.value },
            SettingId.LANE_COUNTS_HORIZON to { settings.laneCountsHorizon.value },
            SettingId.OVERVIEW_TURN_ZOOM to { settings.driveCamera.value.turnZoom },
            SettingId.TONE_BEFORE_TURN to { settings.sound.value.beforeTurn },
            SettingId.TONE_AT_TURN to { settings.sound.value.atTurn },
            // Last: off, it hides the two above.
            SettingId.TONES to { settings.sound.value.tones },
        )
        // Every switch row of the page is one of these.
        val switches = SettingsCatalogue.page(viewModel.values.value).flatMap { it.rows }.filterIsInstance<SwitchRow>().map { it.id }.toSet()
        assertEquals(stored.keys, switches)
        for ((id, value) in stored) {
            val now = !value()
            viewModel.onEvent(SettingsEvent.Switched(id, now))
            runCurrent()
            assertEquals(now, value(), "$id")
            // And the row shows what was stored.
            val row = SettingsCatalogue.page(viewModel.values.value).flatMap { it.rows }.single { it.id == id } as SwitchRow
            assertEquals(now, row.on, "$id")
        }
        assertEquals(emptyList<String>(), archive.logged)
    }

    @Test
    fun `a choice of the catalogue stores the option at its place in the row`() = runTest(dispatcher) {
        val viewModel = viewModelWithValues()
        fun selected(id: SettingId) = (SettingsCatalogue.page(viewModel.values.value).flatMap { it.rows }.single { it.id == id } as ChoiceRow).selected
        for ((index, view) in SettingsCatalogue.driveViews.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.DRIVE_VIEW, index))
            runCurrent()
            assertEquals(view, settings.driveCamera.value.driveView)
            assertEquals(index, selected(SettingId.DRIVE_VIEW))
        }
        for ((index, seconds) in SettingsCatalogue.recenterSeconds.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.RECENTER_SECONDS, index))
            runCurrent()
            assertEquals(seconds, settings.driveCamera.value.recenterSeconds)
            assertEquals(index, selected(SettingId.RECENTER_SECONDS))
        }
        // Each wrote its own part of the drive camera's settings only: Overview, the last wait, Zoom in before turns on.
        assertEquals(DriveCameraSettings(driveView = DriveView.OVERVIEW, recenterSeconds = 20, turnZoom = true), settings.driveCamera.value)
        for ((index, style) in SettingsCatalogue.cameraStyles.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.CAMERA_STYLE, index))
            runCurrent()
            assertEquals(style, settings.cameraStyle.value)
            assertEquals(index, selected(SettingId.CAMERA_STYLE))
        }
        for ((index, zoom) in SettingsCatalogue.laneZooms.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.LANE_ZOOM, index))
            runCurrent()
            assertEquals(zoom, settings.laneZoom.value)
            assertEquals(index, selected(SettingId.LANE_ZOOM))
        }
        for ((index, lanes) in SettingsCatalogue.lanesOnMap.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.LANES_ON_MAP, index))
            runCurrent()
            assertEquals(lanes, settings.lanesOnMap.value)
            assertEquals(index, selected(SettingId.LANES_ON_MAP))
        }
        for ((index, choice) in SettingsCatalogue.lookChoices.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.DAY_NIGHT, index))
            runCurrent()
            assertEquals(choice, settings.dayNight.value)
            assertEquals(index, selected(SettingId.DAY_NIGHT))
        }
        for ((index, fps) in SettingsCatalogue.carMapFps.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.CAR_MAP_FPS, index))
            runCurrent()
            assertEquals(fps, settings.carMapFps.value)
            assertEquals(index, selected(SettingId.CAR_MAP_FPS))
        }
        for ((index, size) in SettingsCatalogue.carMapSizes.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.CAR_MAP_SIZE, index))
            runCurrent()
            assertEquals(size, settings.carMapSize.value)
            assertEquals(index, selected(SettingId.CAR_MAP_SIZE))
        }
        for ((index, duck) in SettingsCatalogue.duckModes.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.TONE_DUCK, index))
            runCurrent()
            assertEquals(duck, settings.sound.value.duck)
            assertEquals(index, selected(SettingId.TONE_DUCK))
        }
        for ((index, level) in SettingsCatalogue.toneLevels.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.TONE_LEVEL, index))
            runCurrent()
            assertEquals(level, settings.sound.value.level)
            assertEquals(index, selected(SettingId.TONE_LEVEL))
        }
        for ((index, millis) in SettingsCatalogue.toneLeadIns.withIndex()) {
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.TONE_LEAD_IN, index))
            runCurrent()
            assertEquals(millis, settings.sound.value.leadInMillis)
            assertEquals(index, selected(SettingId.TONE_LEAD_IN))
        }
        // Each choice wrote its own part of the tones' settings only.
        assertEquals(SoundSettings(duck = DuckMode.MORE, level = ToneLevel.LOUD, leadInMillis = 600), settings.sound.value)
        assertEquals(emptyList<String>(), archive.logged)
    }

    @Test
    fun `Play the tones plays them, a level chosen plays the one tone at it, and why the tones were not heard shows under the button`() =
        runTest(dispatcher) {
            val heard = MutableStateFlow<ToneResult?>(null)
            val played = mutableListOf<String>()
            val viewModel = SettingsViewModel(
                tripLog, settings, archive, now = { now }, installedAt = { installedAt },
                tonesHeard = heard, playTones = { played += "demo" }, playToneAt = { played += "sample ${it.name}" },
            ).also {
                it.values.launchIn(backgroundScope)
                runCurrent()
            }
            fun playRow() = SettingsCatalogue.page(viewModel.values.value).flatMap { it.rows }.single { it.id == SettingId.PLAY_TONES } as ActionRow
            viewModel.onEvent(SettingsEvent.Acted(SettingId.PLAY_TONES))
            // Only the button plays them.
            viewModel.onEvent(SettingsEvent.Switched(SettingId.PLAY_TONES, true))
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.PLAY_TONES, 0))
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.TONE_LEVEL, SettingsCatalogue.toneLevels.indexOf(ToneLevel.SOFT)))
            runCurrent()
            assertEquals(listOf("demo", "sample SOFT"), played)
            assertEquals(ToneLevel.SOFT, settings.sound.value.level)
            assertEquals(listOf(RowNote(RowText.Res(R.string.play_tones_note))), playRow().lines)
            heard.value = ToneResult.VOLUME_ZERO
            runCurrent()
            assertEquals(ToneResult.VOLUME_ZERO, viewModel.values.value.heard)
            assertEquals(RowNote(RowText.Res(R.string.play_tones_volume_zero)), playRow().lines.last())
            // A level that cannot be stored is still heard at once; the row keeps the stored one.
            settings.storageFull = true
            viewModel.onEvent(SettingsEvent.Chosen(SettingId.TONE_LEVEL, SettingsCatalogue.toneLevels.indexOf(ToneLevel.LOUD)))
            runCurrent()
            assertEquals("sample LOUD", played.last())
            assertEquals(ToneLevel.SOFT, viewModel.values.value.sound.level)
            assertEquals(listOf("error"), archive.logged)
        }

    @Test
    fun `an option the row does not have, or an event of another kind, stores nothing`() = runTest(dispatcher) {
        val viewModel = viewModelWithValues()
        viewModel.onEvent(SettingsEvent.Chosen(SettingId.CAMERA_STYLE, SettingsCatalogue.cameraStyles.size))
        viewModel.onEvent(SettingsEvent.Switched(SettingId.CAMERA_STYLE, true))
        viewModel.onEvent(SettingsEvent.Chosen(SettingId.SIMULATE_DRIVE, 1))
        runCurrent()
        assertEquals(CameraStyle.SMOOTH_FOLLOW, settings.cameraStyle.value)
        assertFalse(settings.simulateDrive.value)
    }

    @Test
    fun `the drive camera rows show the stored settings, and one that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.driveCamera.value = DriveCameraSettings(driveView = DriveView.OVERVIEW, recenterSeconds = 5, turnZoom = false)
        val viewModel = viewModelWithValues()
        assertEquals(settings.driveCamera.value, viewModel.values.value.guidance.driveCamera)
        settings.storageFull = true
        viewModel.onEvent(SettingsEvent.Chosen(SettingId.DRIVE_VIEW, SettingsCatalogue.driveViews.indexOf(DriveView.NORMAL)))
        viewModel.onEvent(SettingsEvent.Switched(SettingId.OVERVIEW_TURN_ZOOM, true))
        viewModel.onEvent(SettingsEvent.Chosen(SettingId.RECENTER_SECONDS, SettingsCatalogue.recenterSeconds.indexOf(0)))
        runCurrent()
        assertEquals(DriveCameraSettings(driveView = DriveView.OVERVIEW, recenterSeconds = 5, turnZoom = false), viewModel.values.value.guidance.driveCamera)
        assertEquals(listOf("error", "error", "error"), archive.logged)
        assertEquals(emptyList<DriveCameraSettings>(), settings.storedDriveCameras)
    }

    @Test
    fun `the car's map is x1_5 until another size is chosen, and the size shows as stored`() = runTest(dispatcher) {
        val viewModel = viewModelWithValues()
        assertEquals(CarScreenSettings(mapSize = CarMapSize.X1_5), viewModel.values.value.carScreen)
        viewModel.setCarMapSize(CarMapSize.X2)
        runCurrent()
        assertEquals(CarMapSize.X2, settings.carMapSize.value)
        assertEquals(CarMapSize.X2, viewModel.values.value.carScreen.mapSize)
        // Stored elsewhere, as by another screen: shown as stored.
        settings.carMapSize.value = CarMapSize.X1
        runCurrent()
        assertEquals(CarMapSize.X1, viewModel.values.value.carScreen.mapSize)
        assertEquals(30, viewModel.values.value.debug.carMapFps, "the car's frames are their own")
        assertEquals(emptyList<String>(), archive.logged)
    }

    @Test
    fun `a car map size that cannot be stored stays as it was`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModelWithValues()
        viewModel.onEvent(SettingsEvent.Chosen(SettingId.CAR_MAP_SIZE, SettingsCatalogue.carMapSizes.indexOf(CarMapSize.X1)))
        runCurrent()
        assertEquals(CarMapSize.X1_5, settings.carMapSize.value)
        assertEquals(CarMapSize.X1_5, viewModel.values.value.carScreen.mapSize)
        assertEquals(listOf("error"), archive.logged)
    }

    @Test
    fun `an event that cannot be stored logs one error and the row keeps the stored value`() = runTest(dispatcher) {
        settings.storageFull = true
        val viewModel = viewModelWithValues()
        viewModel.onEvent(SettingsEvent.Switched(SettingId.SIDE_ROAD_FIELDS, true))
        runCurrent()
        assertFalse(settings.sideRoadFields.value)
        assertFalse(viewModel.values.value.debug.sideRoadFields)
        assertEquals(listOf("error"), archive.logged)
        viewModel.onEvent(SettingsEvent.Switched(SettingId.TONES, false))
        runCurrent()
        assertTrue(viewModel.values.value.sound.tones)
        assertEquals(listOf("error", "error"), archive.logged)
    }

    @Test
    fun `Clear of the catalogue clears the log, and the rows the screen carries out store nothing`() = runTest(dispatcher) {
        val viewModel = viewModelWithValues()
        for (id in listOf(SettingId.SHARE_LOG, SettingId.CRASH_NOW, SettingId.MAPBOX_STATISTICS, SettingId.PRIVACY_POLICY, SettingId.TOKENS)) {
            viewModel.onEvent(SettingsEvent.Acted(id))
        }
        runCurrent()
        assertFalse(archive.cleared)
        assertEquals(FakeAppSettings().simulateDrive.value, settings.simulateDrive.value)
        viewModel.onEvent(SettingsEvent.Acted(SettingId.CLEAR_LOG))
        runCurrent()
        assertTrue(archive.cleared)
    }
}
