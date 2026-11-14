package io.github.purplepalm.custommaps.ui.settings

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.trip.MonthCount
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.DataStoreAppSettings
import io.github.purplepalm.custommaps.settings.SoundSettings
import io.github.purplepalm.custommaps.sound.ToneResult
import io.github.purplepalm.custommaps.token.TokenTrips
import io.github.purplepalm.custommaps.token.TripsOf
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rows of Settings as data: where each lives, what it shows for the stored values, and that every stored key has one. */
class SettingsCatalogueTest {

    /** Values under which every row shows: a debug build with the temporary key, Auto with its next change. */
    private val everything = SettingsValues(
        look = LookSettings(LookChoice.AUTO, NextLook(Look.NIGHT, LocalTime.of(18, 5))),
        crashNow = true,
        temporaryDebugKey = true,
    )

    private fun rows(values: SettingsValues = everything): List<SettingRow> =
        SettingsCatalogue.page(values, ZoneOffset.UTC).flatMap { it.rows }

    private inline fun <reified T : SettingRow> row(id: SettingId, values: SettingsValues = everything): T =
        rows(values).single { it.id == id } as T

    @Test
    fun `every stored key has one home, and no row names a key the store does not keep`() {
        val homes = SettingId.entries.mapNotNull { it.key }
        assertEquals(homes.toSet().size, homes.size, "one row for each key")
        assertEquals(DataStoreAppSettings.KEYS, homes.toSet())
        // Each key is at home on one tab, and the tabs together hold them all.
        val byTab = SettingsTab.entries.map { SettingsCatalogue.keysOn(it) }
        assertEquals(homes.size, byTab.sumOf { it.size })
        assertEquals(DataStoreAppSettings.KEYS, byTab.flatten().toSet())
    }

    @Test
    fun `the page picks every row up once, with every row shown`() {
        assertEquals(SettingId.entries.toList(), rows().map { it.id }.sortedBy { it.ordinal })
        // A row of a key is never only data: it is a switch or a choice the driver can change.
        for (id in SettingId.entries.filter { it.key != null }) {
            val row = rows().single { it.id == id }
            assertTrue(row is SwitchRow || row is ChoiceRow, "$id")
        }
    }

    @Test
    fun `the page shows the tabs' groups in their order, App's as its tab lists them`() {
        val groups = SettingsCatalogue.page(everything, ZoneOffset.UTC)
        assertEquals(groups.map { it.tab }.sortedBy { it.ordinal }, groups.map { it.tab })
        assertEquals(
            listOf(
                R.string.guidance_title,
                R.string.sound_title,
                R.string.day_night_title,
                R.string.token_title,
                R.string.trip_sessions_title,
                R.string.debug_title,
                R.string.about_title,
            ),
            groups.map { it.title },
        )
        // Each row sits in a group of its own tab.
        for (group in groups) for (row in group.rows) assertEquals(row.id.tab, group.tab, "${row.id}")
        assertEquals(SettingsCatalogue.page(everything, ZoneOffset.UTC), SettingsTab.entries.flatMap { SettingsCatalogue.groups(it, everything, ZoneOffset.UTC) })
    }

    @Test
    fun `the debug page holds the Debug switches, the car's frames and the tones' lead-in`() {
        assertEquals(
            setOf("simulate_drive", "own_route_line", "measure_own_lanes", "side_road_fields", "car_map_fps", "car_light_when_hot", "tone_lead_in_ms"),
            SettingsCatalogue.keysOn(SettingsPage.DEBUG),
        )
        assertEquals(setOf("tones", "tone_before_turn", "tone_at_turn", "tone_duck", "tone_level"), SettingsCatalogue.keysOn(SettingsTab.SOUND))
        assertEquals(emptySet<String>(), SettingsCatalogue.keysOn(SettingsPage.TOKEN))
        assertEquals(
            setOf("drive_view", "overview_turn_zoom", "recenter_seconds", "camera_style", "lane_zoom", "lanes_on_map", "lane_counts_horizon"),
            SettingsCatalogue.keysOn(SettingsTab.MAP),
        )
        assertTrue("day_night" in SettingsCatalogue.keysOn(SettingsTab.APP))
    }

    @Test
    fun `each choice selects the stored value among its options in the catalogue's order`() {
        for (style in CameraStyle.entries) {
            val chosen = row<ChoiceRow>(SettingId.CAMERA_STYLE, everything.copy(guidance = GuidanceSettings(cameraStyle = style)))
            assertEquals(style, SettingsCatalogue.cameraStyles[chosen.selected])
            assertEquals(CameraStyle.entries.size, chosen.options.size)
            assertTrue(chosen.folds, "a list with a line for each style")
        }
        for (zoom in LaneZoom.entries) {
            assertEquals(zoom, SettingsCatalogue.laneZooms[row<ChoiceRow>(SettingId.LANE_ZOOM, everything.copy(guidance = GuidanceSettings(laneZoom = zoom))).selected])
        }
        for (lanes in LanesOnMap.entries) {
            assertEquals(lanes, SettingsCatalogue.lanesOnMap[row<ChoiceRow>(SettingId.LANES_ON_MAP, everything.copy(guidance = GuidanceSettings(lanesOnMap = lanes))).selected])
        }
        for (choice in LookChoice.entries) {
            assertEquals(choice, SettingsCatalogue.lookChoices[row<ChoiceRow>(SettingId.DAY_NIGHT, everything.copy(look = LookSettings(choice))).selected])
        }
        for (fps in listOf(30, 60)) {
            assertEquals(fps, SettingsCatalogue.carMapFps[row<ChoiceRow>(SettingId.CAR_MAP_FPS, everything.copy(debug = DebugState(carMapFps = fps))).selected])
        }
    }

    @Test
    fun `the guidance group begins with the drive view, Zoom in before turns right under it, and Recenter by itself`() {
        val guidance = SettingsCatalogue.groups(SettingsTab.MAP, everything).single()
        assertEquals(R.string.guidance_title, guidance.title)
        assertEquals(
            listOf(
                SettingId.DRIVE_VIEW, SettingId.OVERVIEW_TURN_ZOOM, SettingId.RECENTER_SECONDS,
                SettingId.CAMERA_STYLE, SettingId.LANE_ZOOM, SettingId.LANES_ON_MAP, SettingId.LANE_COUNTS_HORIZON,
            ),
            guidance.rows.map { it.id },
        )
        val view = row<ChoiceRow>(SettingId.DRIVE_VIEW)
        assertEquals(listOf(RowText.Res(R.string.drive_view_normal), RowText.Res(R.string.drive_view_overview)), view.options.map { it.title })
        assertFalse(view.folds, "two segments")
        val recenter = row<ChoiceRow>(SettingId.RECENTER_SECONDS)
        assertEquals(
            listOf(
                RowText.Res(R.string.recenter_seconds_off),
                RowText.Res(R.string.recenter_seconds_value, listOf(5)),
                RowText.Res(R.string.recenter_seconds_value, listOf(10)),
                RowText.Res(R.string.recenter_seconds_value, listOf(20)),
            ),
            recenter.options.map { it.title },
        )
        assertEquals(listOf(RowNote(RowText.Res(R.string.overview_turn_zoom_note))), row<SwitchRow>(SettingId.OVERVIEW_TURN_ZOOM).lines)
        assertEquals(listOf(RowNote(RowText.Res(R.string.recenter_seconds_note))), recenter.lines)
    }

    @Test
    fun `the drive camera rows show the stored settings, Normal, 10 s and Zoom in before turns on when never chosen`() {
        assertEquals(DriveView.NORMAL, SettingsCatalogue.driveViews[row<ChoiceRow>(SettingId.DRIVE_VIEW).selected])
        assertEquals(10, SettingsCatalogue.recenterSeconds[row<ChoiceRow>(SettingId.RECENTER_SECONDS).selected])
        assertTrue(row<SwitchRow>(SettingId.OVERVIEW_TURN_ZOOM).on)
        for (view in DriveView.entries) {
            for (seconds in DriveCameraSettings.RECENTER_CHOICES) {
                for (turnZoom in listOf(true, false)) {
                    val values = everything.copy(guidance = GuidanceSettings(driveCamera = DriveCameraSettings(view, seconds, turnZoom)))
                    assertEquals(view, SettingsCatalogue.driveViews[row<ChoiceRow>(SettingId.DRIVE_VIEW, values).selected])
                    assertEquals(seconds, SettingsCatalogue.recenterSeconds[row<ChoiceRow>(SettingId.RECENTER_SECONDS, values).selected])
                    assertEquals(turnZoom, row<SwitchRow>(SettingId.OVERVIEW_TURN_ZOOM, values).on)
                }
            }
        }
    }

    @Test
    fun `with a Mapbox camera style the drive view row says the drive views are smoothest with Smooth follow`() {
        val smoothest = RowNote(RowText.Res(R.string.drive_view_mapbox_styles_note), LineTone.NOTE_NOW)
        for (style in CameraStyle.entries) {
            val lines = row<ChoiceRow>(SettingId.DRIVE_VIEW, everything.copy(guidance = GuidanceSettings(cameraStyle = style))).lines
            assertEquals(RowNote(RowText.Res(R.string.drive_view_note)), lines.first())
            assertEquals(style != CameraStyle.SMOOTH_FOLLOW, smoothest in lines, "$style")
        }
    }

    @Test
    fun `the notes that apply now come with what they apply to`() {
        val appLanes = RowNote(RowText.Res(R.string.camera_style_app_lanes_note), LineTone.NOTE_NOW)
        assertTrue(appLanes in row<ChoiceRow>(SettingId.CAMERA_STYLE, everything.copy(guidance = GuidanceSettings(lanesOnMap = LanesOnMap.APP))).lines)
        assertFalse(appLanes in row<ChoiceRow>(SettingId.CAMERA_STYLE, everything.copy(guidance = GuidanceSettings(lanesOnMap = LanesOnMap.MAPBOX))).lines)
        val standard = RowNote(RowText.Res(R.string.lane_zoom_mapbox_standard_note), LineTone.NOTE_NOW)
        assertEquals(listOf(standard), row<ChoiceRow>(SettingId.LANE_ZOOM, everything.copy(guidance = GuidanceSettings(cameraStyle = CameraStyle.MAPBOX_STANDARD))).lines)
        assertEquals(emptyList<RowNote>(), row<ChoiceRow>(SettingId.LANE_ZOOM, everything.copy(guidance = GuidanceSettings(cameraStyle = CameraStyle.SMOOTH_FOLLOW))).lines)
        // The beta is named one beside its title.
        assertEquals(RowText.Res(R.string.mapbox_beta_label), row<SwitchRow>(SettingId.LANE_COUNTS_HORIZON).badge)
    }

    @Test
    fun `under Auto the day and night row says what it does and when the look changes next, by hand nothing`() {
        val auto = row<ChoiceRow>(SettingId.DAY_NIGHT)
        assertEquals(
            listOf(
                RowNote(RowText.Res(R.string.day_night_auto_note)),
                RowNote(RowText.Res(R.string.day_night_next_night, listOf(RowText.Plain("6:05 PM"))), LineTone.VALUE),
            ),
            auto.lines,
        )
        assertEquals(listOf("Auto", "Day", "Night").size, auto.options.size)
        assertEquals(emptyList<RowNote>(), row<ChoiceRow>(SettingId.DAY_NIGHT, everything.copy(look = LookSettings(LookChoice.NIGHT))).lines)
        // Under the time zone's guess there is no next change to show.
        assertEquals(1, row<ChoiceRow>(SettingId.DAY_NIGHT, everything.copy(look = LookSettings(LookChoice.AUTO))).lines.size)
    }

    @Test
    fun `the trip sessions show the month's count, by token, and the newest sessions`() {
        val values = everything.copy(
            debug = DebugState(
                trips = MonthCount(2),
                tripsByToken = listOf(TokenTrips(TripsOf.Token("Main"), 1), TokenTrips(TripsOf.Deleted, 1)),
                recentTrips = listOf(
                    RecentTrip(TripSessionStart(Instant.parse("2000-01-02T03:04:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = true, tokenId = "t1"), TripsOf.Token("Main")),
                    RecentTrip(TripSessionStart(Instant.parse("2000-01-01T05:06:00Z"), TripKind.FREE_DRIVE, simulated = false), TripsOf.BeforeNames),
                ),
            ),
        )
        val count = row<InfoRow>(SettingId.TRIP_SESSIONS, values)
        assertEquals(RowText.Res(R.string.debug_trips_this_month, listOf(2)), count.title)
        assertEquals(
            listOf(
                RowNote(RowText.Res(R.string.debug_trips_token, listOf(RowText.Plain("Main"), 1)), LineTone.VALUE),
                RowNote(RowText.Res(R.string.debug_trips_token, listOf(RowText.Res(R.string.debug_trips_deleted_token), 1)), LineTone.VALUE),
                RowNote(RowText.Res(R.string.debug_trips_note)),
            ),
            count.lines,
        )
        val recent = row<InfoRow>(SettingId.RECENT_TRIPS, values).lines
        assertEquals(
            RowText.Joined(
                listOf(RowText.Plain("Jan 2, 3:04 AM"), RowText.Res(R.string.debug_trip_guidance), RowText.Res(R.string.debug_trip_simulated), RowText.Plain("Main")),
                " · ",
            ),
            recent[0].text,
        )
        // A session from before tokens were named names none.
        assertEquals(RowText.Joined(listOf(RowText.Plain("Jan 1, 5:06 AM"), RowText.Res(R.string.debug_trip_free_drive)), " · "), recent[1].text)
        assertEquals(listOf(RowNote(RowText.Res(R.string.debug_recent_empty), LineTone.VALUE_MUTED)), row<InfoRow>(SettingId.RECENT_TRIPS).lines)
        assertEquals(SettingsCatalogue.MAPBOX_STATISTICS_URL, row<LinkRow>(SettingId.MAPBOX_STATISTICS).url)
    }

    @Test
    fun `the debug log's actions wait for a log, Clear asks first, and Crash now and the key's warning only where they apply`() {
        assertFalse(row<ActionRow>(SettingId.SHARE_LOG).enabled)
        assertFalse(row<ActionRow>(SettingId.CLEAR_LOG).enabled)
        val withLog = everything.copy(debug = DebugState(logBytes = 2_048))
        assertTrue(row<ActionRow>(SettingId.SHARE_LOG, withLog).enabled)
        assertTrue(row<ActionRow>(SettingId.CLEAR_LOG, withLog).enabled)
        assertNotNull(row<ActionRow>(SettingId.CLEAR_LOG).confirm)
        assertEquals(null, row<ActionRow>(SettingId.SHARE_LOG).confirm)
        assertNotNull(row<ActionRow>(SettingId.CRASH_NOW).confirm)
        val release = everything.copy(crashNow = false, temporaryDebugKey = false)
        assertFalse(rows(release).any { it.id == SettingId.CRASH_NOW || it.id == SettingId.TEMPORARY_KEY })
        assertEquals(listOf(LineTone.WARNING), row<InfoRow>(SettingId.TEMPORARY_KEY).lines.map { it.tone })
        assertEquals(RowText.Res(R.string.debug_log_measuring), row<InfoRow>(SettingId.DEBUG_LOG).lines.first().text)
        assertEquals(RowText.Res(R.string.debug_log_empty), row<InfoRow>(SettingId.DEBUG_LOG, everything.copy(debug = DebugState(logBytes = 0))).lines.first().text)
    }

    @Test
    fun `the switches show the stored values`() {
        val on = DebugState(simulateDrive = true, ownRouteLine = true, measureOwnLanes = true, sideRoadFields = true, carLightWhenHot = true)
        for (id in listOf(SettingId.SIMULATE_DRIVE, SettingId.OWN_ROUTE_LINE, SettingId.MEASURE_OWN_LANES, SettingId.SIDE_ROAD_FIELDS, SettingId.CAR_LIGHT_WHEN_HOT)) {
            assertTrue(row<SwitchRow>(id, everything.copy(debug = on)).on, "$id")
            assertFalse(row<SwitchRow>(id, everything.copy(debug = DebugState(carLightWhenHot = false))).on, "$id")
        }
        assertTrue(row<SwitchRow>(SettingId.LANE_COUNTS_HORIZON, everything.copy(guidance = GuidanceSettings(laneCountsHorizon = true))).on)
        assertFalse(row<SwitchRow>(SettingId.LANE_COUNTS_HORIZON, everything.copy(guidance = GuidanceSettings(laneCountsHorizon = false))).on)
    }

    @Test
    fun `the Sound group has the tones' rows in their order, and Tones off hides every row below it`() {
        val sound = SettingsCatalogue.groups(SettingsTab.SOUND, everything).single()
        assertEquals(R.string.sound_title, sound.title)
        assertEquals(
            listOf(SettingId.TONES, SettingId.TONE_BEFORE_TURN, SettingId.TONE_AT_TURN, SettingId.TONE_DUCK, SettingId.TONE_LEVEL, SettingId.PLAY_TONES),
            sound.rows.map { it.id },
        )
        val off = everything.copy(sound = SoundSettings(tones = false))
        val alone = SettingsCatalogue.groups(SettingsTab.SOUND, off).single().rows
        assertEquals(listOf(SettingId.TONES), alone.map { it.id })
        assertFalse((alone.single() as SwitchRow).on)
        // The Debug page's lead-in stays: it is no row under Tones.
        assertTrue(rows(off).any { it.id == SettingId.TONE_LEAD_IN })
        assertEquals(listOf(RowNote(RowText.Res(R.string.tones_note))), alone.single().lines)
    }

    @Test
    fun `the tones' switches and choices show the stored values`() {
        val parts = everything.copy(sound = SoundSettings(beforeTurn = false, atTurn = true))
        assertFalse(row<SwitchRow>(SettingId.TONE_BEFORE_TURN, parts).on)
        assertTrue(row<SwitchRow>(SettingId.TONE_AT_TURN, parts).on)
        assertTrue(row<SwitchRow>(SettingId.TONE_BEFORE_TURN).on)
        assertFalse(row<SwitchRow>(SettingId.TONE_AT_TURN, everything.copy(sound = SoundSettings(atTurn = false))).on)
        for (duck in DuckMode.entries) {
            val chosen = row<ChoiceRow>(SettingId.TONE_DUCK, everything.copy(sound = SoundSettings(duck = duck)))
            assertEquals(duck, SettingsCatalogue.duckModes[chosen.selected])
            assertFalse(chosen.folds, "three segments")
        }
        for (level in ToneLevel.entries) {
            assertEquals(level, SettingsCatalogue.toneLevels[row<ChoiceRow>(SettingId.TONE_LEVEL, everything.copy(sound = SoundSettings(level = level))).selected])
        }
        for (millis in listOf(0, 150, 300, 600)) {
            val chosen = row<ChoiceRow>(SettingId.TONE_LEAD_IN, everything.copy(sound = SoundSettings(leadInMillis = millis)))
            assertEquals(millis, SettingsCatalogue.toneLeadIns[chosen.selected])
            assertEquals(listOf("0", "150", "300", "600").map { ChoiceOption(RowText.Plain(it)) }, chosen.options)
        }
        // A little and Normal when never chosen, 300 ms in front.
        assertEquals(DuckMode.LITTLE, SettingsCatalogue.duckModes[row<ChoiceRow>(SettingId.TONE_DUCK).selected])
        assertEquals(ToneLevel.NORMAL, SettingsCatalogue.toneLevels[row<ChoiceRow>(SettingId.TONE_LEVEL).selected])
        assertEquals(300, SettingsCatalogue.toneLeadIns[row<ChoiceRow>(SettingId.TONE_LEAD_IN).selected])
    }

    @Test
    fun `under Play the tones one line says why they were not heard, where the app knows`() {
        val reasons = mapOf(
            ToneResult.VOLUME_ZERO to R.string.play_tones_volume_zero,
            ToneResult.IN_CALL to R.string.play_tones_in_call,
            ToneResult.NO_FOCUS to R.string.play_tones_no_focus,
        )
        val note = RowNote(RowText.Res(R.string.play_tones_note))
        for ((heard, reason) in reasons) {
            // In the notes' own tone: a line to read when parked, not a warning.
            assertEquals(listOf(note, RowNote(RowText.Res(reason), LineTone.NOTE)), row<ActionRow>(SettingId.PLAY_TONES, everything.copy(heard = heard)).lines)
        }
        for (heard in listOf(null, ToneResult.PLAYED, ToneResult.FAILED)) {
            assertEquals(listOf(note), row<ActionRow>(SettingId.PLAY_TONES, everything.copy(heard = heard)).lines, "$heard")
        }
        assertTrue(row<ActionRow>(SettingId.PLAY_TONES).enabled)
        assertEquals(null, row<ActionRow>(SettingId.PLAY_TONES).confirm)
    }
}
