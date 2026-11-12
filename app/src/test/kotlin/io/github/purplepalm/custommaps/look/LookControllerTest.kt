package io.github.purplepalm.custommaps.look

import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookCause
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.look.LookHold
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.look.PlaceSource
import io.github.purplepalm.custommaps.nav.look.SunElevation
import io.github.purplepalm.custommaps.nav.look.SunPlace
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The look controller with a made-up sun, a clock that starts on 2000-01-01 UTC and moves with the
 * test's own time, synthetic drives (invented steps and distances) and a place at 0, 0 or the time
 * zone's guess. No assertion prints a position, an angle or a time of the sun.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LookControllerTest {

    private val t0 = Instant.parse("2000-01-01T00:00:00Z")

    /** The controller and everything around it: the store, the drive, the sun, the log and a map. */
    private inner class Rig(val test: TestScope, choices: Flow<LookChoice> = flowOf(LookChoice.AUTO)) {
        val guidance = MutableStateFlow(GuidanceState.IDLE)
        val progress = MutableStateFlow<RouteProgressSnapshot?>(null)
        var place: SunPlace = SunPlace.of(0.0, 0.0, PlaceSource.FIX)
        var places: () -> SunPlace = { place }

        /** The made-up sun's elevation, and how often it was asked. */
        var elevation = 10.0
        var sunReads = 0
        private val sun = SunElevation { _, _, _ ->
            sunReads += 1
            elevation
        }

        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        private val log = object : DebugLog {
            override fun log(type: String, fields: Map<String, Any?>) {
                lines += type to fields
            }
        }

        val controller = LookController(
            guidance = guidance,
            progress = progress,
            places = { places() },
            choices = choices,
            debugLog = log,
            scope = test.backgroundScope,
            now = { t0.plusMillis(test.testScheduler.currentTime) },
            elapsedMillis = { test.testScheduler.currentTime },
            driveContext = EmptyCoroutineContext,
            sun = sun,
        )

        /** What the map was asked for, in order, and the settle listener of each. */
        val requests = mutableListOf<LookOnMap>()
        val settles = mutableListOf<(Long?) -> Unit>()

        /** A map on screen that writes the whole basemap for a change and the theme alone for a theme. */
        fun showMap(): Job = test.backgroundScope.launch {
            controller.showOn { request, onSettled ->
                val first = requests.isEmpty()
                val before = requests.lastOrNull()
                requests += request
                settles += onSettled
                when {
                    // Made with the look: nothing to write.
                    first -> LookApplied()
                    before != null && before.look == request.look -> LookApplied(keys = 1, writeMs = 0.5, transitionMs = 0, theme = true)
                    else -> LookApplied(
                        keys = if (request.theme == request.look) 16 else 15,
                        writeMs = 2.5,
                        reset = if (request.look == Look.DAY) "null" else null,
                        transitionMs = 0,
                        routeLine = true,
                        theme = request.theme == request.look,
                    )
                }
            }
        }

        /** The app on screen, from now until the job is cancelled. */
        fun onScreen(): Job = test.backgroundScope.launch { controller.runWhileStarted() }

        fun startAndShow(): Job {
            controller.start()
            test.runCurrent()
            return onScreen().also { test.runCurrent() }
        }

        /** Guidance, [meters] before the next maneuver on [step], at [speed] meters a second. */
        fun drive(meters: Double, step: String = "route-a/0/1", speed: Double? = 13.0, tunnel: Boolean = false) {
            guidance.value = GuidanceState.ACTIVE
            progress.value = RouteProgressSnapshot(
                nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Oak Street"),
                distanceToNextManeuver = Distance.meters(meters),
                lanes = null,
                distanceRemaining = Distance.meters(meters + 4_000.0),
                durationRemaining = ((meters + 4_000.0) / 13.0).seconds,
                durationToNextManeuver = (meters / 13.0).seconds,
                stepKey = step,
                inTunnel = tunnel,
                speedMps = speed,
            )
            test.runCurrent()
        }

        /** Moves the clock to [seconds] after the start. */
        fun at(seconds: Long) {
            test.advanceTimeBy(seconds * 1_000 - test.testScheduler.currentTime)
            test.runCurrent()
        }

        fun types(): List<String> = lines.map { it.first }

        fun looks(): List<Map<String, Any?>> = lines.filter { it.first == "look" }.map { it.second }
    }

    @Test
    fun `the first look is decided once, before the first frame, with how long it waited`() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        assertFalse(rig.controller.decided.value)
        runCurrent()
        assertTrue(rig.controller.decided.value)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(LookOnMap(Look.DAY), rig.controller.lookOnMap.value)
        assertEquals(
            listOf(
                mapOf(
                    "look" to Look.DAY,
                    "cause" to LookCause.START,
                    "choice" to LookChoice.AUTO,
                    "place" to PlaceSource.FIX,
                    "guidance" to false,
                    "startMs" to 0L,
                ),
            ),
            rig.looks(),
        )
        // On screen right after it: nothing new to decide.
        rig.onScreen()
        runCurrent()
        rig.controller.start()
        runCurrent()
        assertEquals(listOf("look"), rig.types())
    }

    @Test
    fun `a store slower than 300 ms gives the first look to Auto, and its choice comes later`() = runTest {
        val store = MutableStateFlow<LookChoice?>(null)
        val rig = Rig(this, store.filterNotNull())
        rig.controller.start()
        advanceTimeBy(299)
        runCurrent()
        assertFalse(rig.controller.decided.value)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(rig.controller.decided.value)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(LookChoice.AUTO, rig.looks().single()["choice"])
        assertEquals(300L, rig.looks().single()["startMs"])
        // The store answers after a second: Night by hand, at once.
        advanceTimeBy(700)
        store.value = LookChoice.NIGHT
        runCurrent()
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookCause.CHOICE, rig.looks().last()["cause"])
        assertEquals(LookChoice.NIGHT, rig.looks().last()["choice"])
    }

    @Test
    fun `a store that fails or never answers, and no position, still decide within the wait`() = runTest {
        // A store that throws: decided at once, on Auto, under the time zone's guess.
        val failing = Rig(this, flow { throw IOException("read failed") })
        failing.places = SunPlaces(
            fix = { null },
            lastKnown = { throw SecurityException("no location access") },
            zone = { ZoneOffset.UTC.rules },
            now = { t0 },
        )::current
        failing.controller.start()
        runCurrent()
        assertTrue(failing.controller.decided.value)
        assertEquals(LookChoice.AUTO, failing.looks().single()["choice"])
        assertEquals(PlaceSource.TIME_ZONE, failing.looks().single()["place"])
        assertEquals(0L, failing.looks().single()["startMs"])
        assertEquals("error", failing.types().first())

        // A store that never answers, and no provider with a position: decided after 300 ms.
        val silent = Rig(this, flow { awaitCancellation() })
        silent.places = SunPlaces(fix = { null }, lastKnown = { null }, zone = { ZoneOffset.UTC.rules }, now = { t0 })::current
        silent.controller.start()
        advanceTimeBy(300)
        runCurrent()
        assertTrue(silent.controller.decided.value)
        assertEquals(300L, silent.looks().single()["startMs"])
        assertEquals(PlaceSource.TIME_ZONE, silent.looks().single()["place"])
    }

    @Test
    fun `while browsing the sun switches the look at the next minute and writes no line`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.elevation = -3.0
        rig.at(60)
        // Between the two elevations the shown look stays.
        assertEquals(Look.DAY, rig.controller.look.value)
        rig.elevation = -4.0
        rig.at(119)
        assertEquals(Look.DAY, rig.controller.look.value)
        rig.at(120)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT), rig.controller.lookOnMap.value)
        // In the route preview the same: the time of a sun switch is not written outside guidance.
        rig.guidance.value = GuidanceState.PREVIEW
        rig.elevation = 5.0
        rig.at(720)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(listOf("look"), rig.types())
        assertEquals(LookCause.START, rig.looks().single()["cause"])
    }

    @Test
    fun `the driver's choice goes at once, even at a turn in a close view`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.drive(50.0)
        rig.controller.onCloseView(true)
        rig.controller.onChoice(LookChoice.NIGHT)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT), rig.controller.lookOnMap.value)
        val line = rig.looks().last()
        assertEquals(LookCause.CHOICE, line["cause"])
        assertEquals(true, line["guidance"])
        assertFalse("heldS" in line)
        assertFalse("place" in line, "a look by hand is computed for no place")
        // The same choice again changes nothing.
        rig.controller.onChoice(LookChoice.NIGHT)
        assertEquals(2, rig.looks().size)
        // Back to Auto by day: the day look at once.
        rig.controller.onChoice(LookChoice.AUTO)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(LookCause.CHOICE, rig.looks().last()["cause"])
        // Auto again, as the store gives it when read anew: not even the sun is computed.
        val reads = rig.sunReads
        rig.controller.onChoice(LookChoice.AUTO)
        assertEquals(reads, rig.sunReads)
        assertEquals(3, rig.looks().size)
    }

    @Test
    fun `back on screen after sunset the night look goes at once`() = runTest {
        val rig = Rig(this)
        val screen = rig.startAndShow()
        screen.cancel()
        rig.elevation = -10.0
        // An hour in the background: no tick, no change.
        rig.at(3_600)
        assertEquals(Look.DAY, rig.controller.look.value)
        rig.onScreen()
        runCurrent()
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT), rig.controller.lookOnMap.value)
        val line = rig.looks().last()
        assertEquals(LookCause.RESUME, line["cause"])
        assertEquals(Look.DAY, line["was"])
        assertFalse("themeDeferred" in line)
    }

    @Test
    fun `Settings over the map lets a waiting switch go at once`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.drive(2_000.0)
        rig.elevation = -4.0
        rig.at(59)
        rig.drive(100.0)
        rig.at(60)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(Look.NIGHT, rig.controller.switchState.waiting)
        rig.at(62)
        rig.controller.onCovered(true)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        val line = rig.looks().last()
        assertEquals(LookCause.SUN, line["cause"])
        assertEquals(LookHold.MANEUVER, line["heldBy"])
        assertEquals(2L, line["heldS"])
        assertEquals(true, line["guidance"])
    }

    @Test
    fun `a hundred progress updates compute the sun not once`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.drive(5_000.0)
        val reads = rig.sunReads
        repeat(100) { rig.drive(4_999.0 - it) }
        assertEquals(reads, rig.sunReads)
        // The minute's tick computes it once.
        rig.at(60)
        assertEquals(reads + 1, rig.sunReads)
    }

    @Test
    fun `a tick past the band near a maneuver waits, and the switch goes six seconds after the step change`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.drive(2_000.0)
        rig.elevation = -4.0
        rig.at(59)
        rig.drive(100.0)
        rig.at(60)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(Look.NIGHT, rig.controller.switchState.waiting)
        assertEquals(LookHold.MANEUVER, rig.controller.switchState.waitingFor)
        rig.at(61)
        rig.drive(50.0)
        // The turn: a new step.
        rig.at(75)
        rig.drive(900.0, step = "route-a/0/2")
        for (second in 76L..80L) {
            rig.at(second)
            rig.drive(900.0 - (second - 75) * 13.0, step = "route-a/0/2")
            assertEquals(Look.DAY, rig.controller.look.value, "within six seconds of the turn")
        }
        rig.at(81)
        rig.drive(820.0, step = "route-a/0/2")
        assertEquals(Look.NIGHT, rig.controller.look.value)
        val line = rig.looks().last()
        assertEquals(LookCause.SUN, line["cause"])
        assertEquals(LookHold.MANEUVER, line["heldBy"])
        assertEquals(21L, line["heldS"])
        assertEquals(true, line["guidance"])
        assertEquals(PlaceSource.FIX, line["place"])
    }

    @Test
    fun `a step change just before the first tick of the drive holds its switch for six seconds`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.at(50)
        rig.drive(1_500.0)
        rig.at(57)
        // A turn while nothing waits, three seconds before the minute's tick.
        rig.drive(1_400.0, step = "route-a/0/2")
        rig.elevation = -4.0
        rig.at(60)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(LookHold.MANEUVER, rig.controller.switchState.waitingFor)
        rig.at(62)
        rig.drive(1_330.0, step = "route-a/0/2")
        assertEquals(Look.DAY, rig.controller.look.value)
        rig.at(63)
        rig.drive(1_317.0, step = "route-a/0/2")
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(3L, rig.looks().last()["heldS"])
    }

    @Test
    fun `back on screen near a maneuver the theme waits, and one look_theme line says when it went`() = runTest {
        val rig = Rig(this)
        val screen = rig.startAndShow()
        rig.showMap()
        runCurrent()
        screen.cancel()
        rig.drive(100.0)
        rig.elevation = -10.0
        rig.at(3_600)
        rig.onScreen()
        runCurrent()
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT, Look.DAY), rig.controller.lookOnMap.value)
        assertEquals(LookOnMap(Look.NIGHT, Look.DAY), rig.requests.last())
        val resume = rig.looks().last()
        assertEquals(LookCause.RESUME, resume["cause"])
        assertEquals(true, resume["themeDeferred"])
        assertEquals(15, resume["keys"])
        rig.at(3_605)
        rig.drive(800.0, step = "route-a/0/2")
        for (second in 3_606L..3_610L) {
            rig.at(second)
            rig.drive(800.0 - (second - 3_605) * 13.0, step = "route-a/0/2")
            assertEquals(LookOnMap(Look.NIGHT, Look.DAY), rig.controller.lookOnMap.value)
        }
        rig.at(3_611)
        rig.drive(722.0, step = "route-a/0/2")
        assertEquals(LookOnMap(Look.NIGHT), rig.controller.lookOnMap.value)
        assertEquals(LookOnMap(Look.NIGHT), rig.requests.last())
        assertEquals(1, rig.types().count { it == "look_theme" })
        assertEquals(mapOf("themeHeldS" to 11L, "heldBy" to LookHold.MANEUVER, "writeMs" to 0.5), rig.lines.last { it.first == "look_theme" }.second)
        // The map has drawn every tile 900 ms after the theme.
        rig.settles.last()(900L)
        assertEquals("look_settled" to mapOf<String, Any?>("idleMs" to 900L, "look" to Look.NIGHT), rig.lines.last())
        rig.at(3_612)
        rig.drive(709.0, step = "route-a/0/2")
        assertEquals(1, rig.types().count { it == "look_theme" })
    }

    @Test
    fun `a close view holds a due switch for as long as it lasts`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.drive(5_000.0)
        rig.controller.onCloseView(true)
        rig.elevation = -4.0
        rig.at(60)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(LookHold.CLOSE_VIEW, rig.controller.switchState.waitingFor)
        for (second in 61L..84L) {
            rig.at(second)
            rig.drive(5_000.0 - (second - 60) * 30.0)
            assertEquals(Look.DAY, rig.controller.look.value)
        }
        rig.controller.onCloseView(false)
        rig.at(85)
        rig.drive(4_250.0)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookHold.CLOSE_VIEW, rig.looks().last()["heldBy"])
        assertEquals(25L, rig.looks().last()["heldS"])
    }

    @Test
    fun `an activity made again keeps the look, decides no first look and keeps the dwell`() = runTest {
        val rig = Rig(this)
        val first = rig.startAndShow()
        rig.elevation = -4.0
        rig.at(60)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        // A token switch makes the activity again: it starts and is on screen at once.
        first.cancel()
        rig.at(120)
        rig.elevation = -3.0
        rig.controller.start()
        assertTrue(rig.controller.decided.value)
        rig.onScreen()
        runCurrent()
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT), rig.controller.lookOnMap.value)
        // The sun is back above the band, but a sun switch does not undo itself within ten minutes,
        // as a second first look would have let it.
        rig.elevation = 5.0
        rig.at(180)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        rig.at(600)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        rig.at(660)
        assertEquals(Look.DAY, rig.controller.look.value)
        assertEquals(1, rig.looks().count { it["cause"] == LookCause.START })
    }

    @Test
    fun `an activity made again back on screen follows the rules of coming back`() = runTest {
        val rig = Rig(this)
        val first = rig.startAndShow()
        first.cancel()
        // Made again by Android during a drive, after dusk, 100 m before a turn.
        rig.drive(100.0)
        rig.elevation = -10.0
        rig.at(1_800)
        rig.controller.start()
        rig.onScreen()
        runCurrent()
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT, Look.DAY), rig.controller.lookOnMap.value)
        assertEquals(LookCause.RESUME, rig.looks().last()["cause"])
        assertEquals(true, rig.looks().last()["themeDeferred"])
        assertEquals(1, rig.looks().count { it["cause"] == LookCause.START })
    }

    @Test
    fun `a first fix after the time zone's guess is a better place`() = runTest {
        val rig = Rig(this)
        // The guess takes the darkest of its latitudes: night for the made-up sun below.
        rig.place = SunPlace.ofTimeZone(0)
        rig.elevation = -5.0
        rig.startAndShow()
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(PlaceSource.TIME_ZONE, rig.looks().single()["place"])
        rig.place = SunPlace.of(0.0, 0.0, PlaceSource.FIX)
        rig.elevation = 5.0
        rig.at(60)
        assertEquals(Look.DAY, rig.controller.look.value)
        val line = rig.looks().last()
        assertEquals(LookCause.PLACE, line["cause"])
        assertEquals(PlaceSource.FIX, line["place"])
    }

    @Test
    fun `the look line takes what the map did, and the map's settle time follows a theme once`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.showMap()
        runCurrent()
        // The map's first request is the look it was made with: no line for it.
        assertEquals(listOf(LookOnMap(Look.DAY)), rig.requests)
        assertEquals(1, rig.lines.size)
        rig.controller.onChoice(LookChoice.NIGHT)
        // The line waits for the map, which takes the request in the next message.
        assertEquals(1, rig.lines.size)
        runCurrent()
        assertEquals(
            mapOf(
                "look" to Look.NIGHT,
                "was" to Look.DAY,
                "cause" to LookCause.CHOICE,
                "choice" to LookChoice.NIGHT,
                "guidance" to false,
                "keys" to 16,
                "errors" to 0,
                "writeMs" to 2.5,
                "transitionMs" to 0L,
                "routeLine" to true,
            ),
            rig.lines.last().second,
        )
        rig.settles.last()(1_234L)
        rig.settles.last()(1_234L)
        assertEquals(listOf("look", "look", "look_settled"), rig.types())
        assertEquals(mapOf<String, Any?>("idleMs" to 1_234L, "look" to Look.NIGHT), rig.lines.last().second)
        // By day the line says how the given back keys read.
        rig.controller.onChoice(LookChoice.DAY)
        runCurrent()
        assertEquals("null", rig.lines.last().second["reset"])
        // No idle within the wait: the line says so.
        rig.settles.last()(null)
        assertEquals(mapOf<String, Any?>("idleMs" to null, "look" to Look.DAY), rig.lines.last().second)
    }

    @Test
    fun `a sun switch outside guidance writes no line and no settle time`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.showMap()
        runCurrent()
        rig.elevation = -4.0
        rig.at(60)
        assertEquals(Look.NIGHT, rig.controller.look.value)
        assertEquals(LookOnMap(Look.NIGHT), rig.requests.last())
        rig.settles.last()(800L)
        assertEquals(listOf("look"), rig.types())
    }

    @Test
    fun `with no map on screen a line goes at once, and one the map never took goes when it leaves`() = runTest {
        val rig = Rig(this)
        rig.startAndShow()
        rig.controller.onChoice(LookChoice.NIGHT)
        assertEquals(LookCause.CHOICE, rig.looks().last()["cause"])
        assertFalse("keys" in rig.looks().last())
        val map = rig.showMap()
        runCurrent()
        rig.controller.onChoice(LookChoice.DAY)
        // The map goes before it takes the request: the line is written as it is.
        map.cancel()
        runCurrent()
        assertEquals(LookChoice.DAY, rig.looks().last()["choice"])
        assertFalse("keys" in rig.looks().last())
        assertEquals(3, rig.looks().size)
    }

    @Test
    fun `no line names a position, an angle or a time`() = runTest {
        val rig = Rig(this)
        rig.place = SunPlace.of(0.4, -0.7, PlaceSource.LAST_KNOWN)
        val screen = rig.startAndShow()
        rig.showMap()
        runCurrent()
        rig.controller.onChoice(LookChoice.NIGHT)
        runCurrent()
        rig.controller.onChoice(LookChoice.AUTO)
        runCurrent()
        rig.drive(2_000.0)
        rig.elevation = -4.0
        rig.at(60)
        rig.at(119)
        rig.drive(100.0)
        rig.at(120)
        rig.at(130)
        rig.drive(1_000.0, step = "route-a/0/2")
        rig.at(140)
        rig.drive(900.0, step = "route-a/0/2")
        screen.cancel()
        rig.elevation = 10.0
        rig.drive(90.0, step = "route-a/0/3")
        rig.at(1_000)
        rig.onScreen()
        runCurrent()
        rig.settles.forEach { it(700L) }
        val allowed = setOf(
            "look", "was", "cause", "choice", "place", "heldS", "heldBy", "guidance", "themeDeferred", "startMs",
            "keys", "errors", "failedKeys", "writeMs", "reset", "transitionMs", "routeLine", "themeHeldS", "idleMs",
        )
        assertTrue(rig.lines.size >= 5, "${rig.types()}")
        for ((type, fields) in rig.lines) {
            assertTrue(type in setOf("look", "look_theme", "look_settled"), type)
            assertTrue(allowed.containsAll(fields.keys), "$type ${fields.keys}")
            for ((key, value) in fields) {
                val plain = value == null || value is Enum<*> || value is Boolean || value is Long || value is Int ||
                    (key == "writeMs" && value is Double) || (key == "reset" && value is String)
                assertTrue(plain, "$type $key")
            }
            fields["place"]?.let { assertTrue(it is PlaceSource, type) }
        }
        val written = rig.lines.joinToString { it.toString() }
        assertFalse("0.4" in written || "0.7" in written || "SunPlace" in written)
        assertNull(rig.lines.firstOrNull { it.first == "look" && it.second["cause"] == LookCause.SUN && it.second["guidance"] == false })
    }
}
