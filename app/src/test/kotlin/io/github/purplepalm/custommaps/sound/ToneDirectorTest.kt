package io.github.purplepalm.custommaps.sound

import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.ToneKind
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.sound.ToneMoment
import io.github.purplepalm.custommaps.settings.FakeAppSettings
import io.github.purplepalm.custommaps.settings.SoundSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The director with a sink that plays nothing and a clock the test moves: what sounds, when, at which level and dip,
 * with and without a car, and what is logged. Invented drives near nowhere: metres, seconds and names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToneDirectorTest {

    /** One sound the sink was asked for. */
    private data class Played(val kind: ToneKind, val level: ToneLevel, val duck: DuckMode, val leadIn: Int, val car: Boolean, val atMillis: Long)

    private inner class Sink(private val now: () -> Long) : ToneSink {
        val played = mutableListOf<Played>()
        var result = ToneResult.PLAYED
        var throwing = false

        override fun play(kind: ToneKind, level: ToneLevel, duck: DuckMode, wakeLeadInMillis: Int, car: Boolean, onResult: (ToneResult) -> Unit) {
            if (throwing) throw IllegalStateException("no track")
            played += Played(kind, level, duck, wakeLeadInMillis, car, now())
            onResult(result)
        }
    }

    private class Lines : DebugLog {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }

        fun of(type: String) = lines.filter { it.first == type }.map { it.second }
    }

    private val turn = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Oak Street")
    private val settings = FakeAppSettings()
    private val log = Lines()

    private fun TestScope.director(): Pair<ToneDirector, Sink> {
        val sink = Sink { testScheduler.currentTime }
        val director = ToneDirector(backgroundScope, settings, sink, log, elapsedMillis = { testScheduler.currentTime })
        director.start()
        runCurrent()
        return director to sink
    }

    private fun moment(meters: Double, step: String = "r1/0/0", route: String = "r1", speed: Double = 13.41, maneuver: Maneuver = turn) =
        ToneMoment(route, step, maneuver, meters, secondsToManeuver = 0.0, speedMps = speed, offRoute = false)

    /** Drives towards a turn [from] metres ahead at [speed], one update a second, to [to] metres. */
    private fun TestScope.approach(director: ToneDirector, from: Double, to: Double = 0.0, speed: Double = 13.41, step: String = "r1/0/0", route: String = "r1") {
        var meters = from
        while (meters > to) {
            director.onProgress(moment(meters, step, route, speed))
            advanceTimeBy(1_000)
            runCurrent()
            meters -= speed
        }
    }

    @Test
    fun `with Tones off nothing plays and nothing is logged, while the rule keeps up with the drive`() = runTest {
        settings.sound.value = SoundSettings(tones = false)
        val (director, sink) = director()
        // Past the one tone's distance with Tones off.
        approach(director, from = 800.0, to = 250.0)
        assertEquals(emptyList<Played>(), sink.played)
        assertEquals(emptyList<Pair<String, Map<String, Any?>>>(), log.lines)
        // Turned on: the one tone's moment has passed, the pair still comes.
        settings.sound.value = SoundSettings()
        runCurrent()
        approach(director, from = 240.0)
        assertEquals(listOf(ToneKind.AT_TURN), sink.played.map { it.kind })
    }

    @Test
    fun `a tone reaches the sink with the stored level, dip and silence in front, and its line says why`() = runTest {
        settings.sound.value = SoundSettings(level = ToneLevel.LOUD, duck = DuckMode.MORE, leadInMillis = 600)
        val (director, sink) = director()
        approach(director, from = 800.0)
        assertEquals(
            listOf(
                Played(ToneKind.AHEAD, ToneLevel.LOUD, DuckMode.MORE, 600, car = false, sink.played[0].atMillis),
                Played(ToneKind.AT_TURN, ToneLevel.LOUD, DuckMode.MORE, 600, car = false, sink.played[1].atMillis),
            ),
            sink.played,
        )
        val (single, pair) = log.of(ToneDirector.TONE_LINE)
        assertEquals("ahead", single["tone"])
        assertEquals("ahead", single["why"])
        assertEquals("TURN", single["maneuver"])
        assertEquals("RIGHT", single["modifier"])
        assertEquals(335L, single["aheadMeters"])
        assertEquals(94L, single["finalMeters"])
        assertEquals(13.4, single["speed"])
        assertEquals("play", single["pacing"])
        assertTrue((single["metersLeft"] as Long) in 322L..335L)
        assertEquals("at_turn", pair["tone"])
        assertEquals("at_turn", pair["why"])
        assertTrue((pair["sinceLast"] as Double) in 17.0..19.0)
        // Numbers, ids and words only: no name of a road.
        assertTrue(log.lines.none { (_, fields) -> fields.values.any { it == "Oak Street" } })
    }

    @Test
    fun `Play the tones plays the one tone then the two, 1_5 s apart, and a tone of the drive cancels the rest`() = runTest {
        val (director, sink) = director()
        director.playDemo()
        runCurrent()
        advanceTimeBy(ToneDirector.DEMO_GAP_MILLIS)
        runCurrent()
        assertEquals(listOf(ToneKind.AHEAD to 0L, ToneKind.AT_TURN to 1_500L), sink.played.map { it.kind to it.atMillis })
        assertEquals("demo", log.of(ToneDirector.TONE_LINE).single()["tone"])

        // Again, and the drive's arrival comes in its gap: its pair waits for the one tone, the demo's pair never comes.
        advanceTimeBy(10_000)
        sink.played.clear()
        director.onProgress(moment(2_000.0, maneuver = Maneuver(ManeuverType.ARRIVE, null, "Office")))
        director.playDemo()
        runCurrent()
        advanceTimeBy(500)
        director.onArrived()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), sink.played.map { it.kind })
        assertEquals("arrived", log.of(ToneDirector.TONE_LINE).last()["why"])
        assertEquals("hold", log.of(ToneDirector.TONE_LINE).last()["pacing"])
    }

    @Test
    fun `the start chime cancels what is left of Play the tones, and Tones off silences it`() = runTest {
        val (director, sink) = director()
        director.playDemo()
        runCurrent()
        advanceTimeBy(2_000)
        director.playStart()
        advanceTimeBy(5_000)
        runCurrent()
        // The demo's pair was due at 1.5 s and played; the chime after it.
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN, ToneKind.START), sink.played.map { it.kind })
        sink.played.clear()
        director.playDemo()
        runCurrent()
        director.playStart()
        advanceTimeBy(5_000)
        runCurrent()
        // The chime waits for the demo's one tone; the demo's pair never comes.
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.START), sink.played.map { it.kind })
        settings.sound.value = SoundSettings(tones = false)
        runCurrent()
        sink.played.clear()
        director.playStart()
        assertEquals(emptyList<Played>(), sink.played)
    }

    @Test
    fun `while a car session is connected every tone says so, and while another app guides on it nothing sounds`() = runTest {
        val (director, sink) = director()
        director.carSession = true
        approach(director, from = 800.0, to = 300.0)
        assertEquals(listOf(true), sink.played.map { it.car })
        // The host asked the app to stop navigating: silent, and nothing logged, while the car stays connected.
        director.carYielded = true
        val lines = log.lines.size
        approach(director, from = 280.0, to = 50.0)
        assertEquals(1, sink.played.size)
        assertEquals(lines, log.lines.size)
        // The driver took the car's screen back: the next turn sounds again, on the car.
        director.carYielded = false
        approach(director, from = 900.0, step = "r1/0/1")
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AHEAD, ToneKind.AT_TURN), sink.played.map { it.kind })
        assertTrue(sink.played.all { it.car })
        // Disconnected: the phone's own dip again.
        director.carSession = false
        approach(director, from = 900.0, step = "r1/0/2")
        assertEquals(listOf(false, false), sink.played.drop(3).map { it.car })
    }

    @Test
    fun `a sink that throws changes nothing for the next tone, and writes one error line`() = runTest {
        val (director, sink) = director()
        sink.throwing = true
        approach(director, from = 800.0, to = 200.0)
        sink.throwing = false
        approach(director, from = 190.0)
        assertEquals(listOf(ToneKind.AT_TURN), sink.played.map { it.kind })
        assertEquals(1, log.of("error").size)
        assertEquals("tones", log.of("error").single()["where"])
    }

    @Test
    fun `a pair asked for while a sound plays waits for its end, and the end of guidance drops it`() = runTest {
        val (director, sink) = director()
        // The one tone of Play the tones plays at 0; the drive arrives 100 ms later.
        director.onProgress(moment(2_000.0, maneuver = Maneuver(ManeuverType.ARRIVE, null, "Office")))
        director.playDemo()
        runCurrent()
        advanceTimeBy(100)
        director.onArrived()
        runCurrent()
        assertEquals(listOf(ToneKind.AHEAD), sink.played.map { it.kind })
        // It waits for the one tone, its silence in front and the room after it.
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(ToneKind.AHEAD to 0L, ToneKind.AT_TURN to 1_300L), sink.played.map { it.kind to it.atMillis })

        // Once the pair has rung out: the one tone again, and guidance ends while the pair waits.
        advanceTimeBy(5_000)
        sink.played.clear()
        director.onGuidanceEnded()
        director.onProgress(moment(2_000.0, step = "r2/0/0", route = "r2", maneuver = Maneuver(ManeuverType.ARRIVE, null, "Office")))
        director.playDemo()
        runCurrent()
        advanceTimeBy(100)
        director.onArrived()
        director.onGuidanceEnded()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(ToneKind.AHEAD), sink.played.map { it.kind })
    }

    @Test
    fun `a seek starts the tones afresh, nothing at once where it lands and the turns ahead again after a seek back`() = runTest {
        val (director, sink) = director()
        approach(director, from = 800.0, to = 300.0)
        assertEquals(listOf(ToneKind.AHEAD), sink.played.map { it.kind })
        // Back to before the turn: its tones sound again.
        director.onGuidanceEnded()
        advanceTimeBy(20_000)
        approach(director, from = 800.0)
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AHEAD, ToneKind.AT_TURN), sink.played.map { it.kind })
        // Forward to 25 m before the next turn: nothing at once, nor after.
        director.onGuidanceEnded()
        approach(director, from = 25.0, step = "r1/0/1")
        assertEquals(3, sink.played.size)
        assertEquals("new_route", log.of(ToneDirector.SKIPPED_LINE).last()["why"])
        assertEquals("at_turn", log.of(ToneDirector.SKIPPED_LINE).last()["tone"])
        // Across a turn: the next one's tones as usual.
        director.onGuidanceEnded()
        approach(director, from = 1_200.0, step = "r1/0/2")
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), sink.played.drop(3).map { it.kind })
    }

    @Test
    fun `what became of Play the tones is kept for Settings, and a level chosen plays the one tone at it`() = runTest {
        val (director, sink) = director()
        assertNull(director.heard.value)
        sink.result = ToneResult.VOLUME_ZERO
        director.playDemo()
        runCurrent()
        assertEquals(ToneResult.VOLUME_ZERO, director.heard.value)
        advanceTimeBy(5_000)
        sink.result = ToneResult.PLAYED
        director.playSample(ToneLevel.SOFT)
        runCurrent()
        assertEquals(ToneResult.PLAYED, director.heard.value)
        assertEquals(Played(ToneKind.AHEAD, ToneLevel.SOFT, DuckMode.LITTLE, 300, false, sink.played.last().atMillis), sink.played.last())
        assertEquals("sample", log.of(ToneDirector.TONE_LINE).last()["why"])
    }

    @Test
    fun `off the route nothing sounds`() = runTest {
        val (director, sink) = director()
        var meters = 800.0
        while (meters > 0) {
            director.onProgress(ToneMoment("r1", "r1/0/0", turn, meters, 0.0, 13.41, offRoute = true))
            advanceTimeBy(1_000)
            meters -= 13.41
        }
        assertEquals(emptyList<Played>(), sink.played)
    }
}
