package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The card's lane row as the debug log writes it. Made-up lanes and keys of a route "r". */
class LaneRowLogTest {

    private val carPool = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true)
    private val on = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, activeIndication = TurnModifier.STRAIGHT)
    private val off = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false)
    private val leaving = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false)
    private val exit = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)

    /** The maneuver's own row: a car pool lane, three lanes that go on, the exit lane lit. */
    private val maneuverLanes = LaneInfo(listOf(carPool, off, off, off, exit))

    /** A run-up's row at a point where the right lane leaves: the second from the right lit. */
    private val edgeRow = RunUpLanes(
        LaneInfo(listOf(carPool, off, off, on, leaving)),
        target = LaneTarget(LaneEdge.RIGHT, 1, 1),
        key = "r/0/2/4",
        odd = null,
        need = 1,
    )

    /** The run-up's row past that point: the exit's own lanes, the right lane in words. */
    private val exitRow = RunUpLanes(maneuverLanes, target = LaneTarget(LaneEdge.RIGHT, 1, 0), key = "r/0/2/end", odd = null, need = 0)

    private fun snapshot(
        lanes: LaneInfo? = maneuverLanes,
        toManeuver: Double = 1_800.0,
        laneChoiceAhead: LanesAhead? = null,
        runUpLanes: RunUpLanes? = null,
        pending: Boolean = false,
        offRoute: Boolean = false,
    ) = RouteProgressSnapshot(
        nextManeuver = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: Oak Street"),
        distanceToNextManeuver = Distance.meters(toManeuver),
        lanes = lanes,
        distanceRemaining = Distance.miles(12.0),
        durationRemaining = 14.minutes,
        stepKey = "r/0/2",
        laneChoiceAhead = laneChoiceAhead,
        pending = pending,
        runUpLanes = runUpLanes,
        offRoute = offRoute,
    )

    private fun LaneRowLog.at(snapshot: RouteProgressSnapshot, choiceKey: String? = null, runUpMeters: Double? = null, runUp: String? = null, start: String? = null) =
        onSnapshot(snapshot, choiceKey, runUpMeters, runUp, start)

    @Test
    fun `each lane is a token of its arrows, lit with a star and the lit arrow where it is not the only one`() {
        assertEquals("s", LaneRowLog.token(off))
        assertEquals("s*", LaneRowLog.token(on))
        assertEquals("sr*", LaneRowLog.token(exit))
        assertEquals("s+sr*>sr", LaneRowLog.token(Lane(setOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.STRAIGHT), true, TurnModifier.SLIGHT_RIGHT)))
        assertEquals("s+sr", LaneRowLog.token(Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), false)))
        // A lane without arrows is drawn straight on, and says so when lit.
        assertEquals("-", LaneRowLog.token(Lane(emptySet(), false)))
        assertEquals("-*>s", LaneRowLog.token(Lane(emptySet(), true, TurnModifier.STRAIGHT)))
        // Lit on an arrow it does not have: the lit one is named.
        assertEquals("l*>s", LaneRowLog.token(Lane(setOf(TurnModifier.LEFT), true, TurnModifier.STRAIGHT)))
        // Lit without an arrow named: the star alone.
        assertEquals("s+r*", LaneRowLog.token(Lane(setOf(TurnModifier.RIGHT, TurnModifier.STRAIGHT), true)))
        // Every arrow, in the token's own order.
        assertEquals("s+sl+l+shl+sr+r+shr+u", LaneRowLog.token(Lane(TurnModifier.entries.toSet(), false)))
    }

    @Test
    fun `the words name the side, how many lanes and how far in`() {
        assertEquals("R1", LaneRowLog.words(LaneTarget(LaneEdge.RIGHT, 1, 0)))
        assertEquals("R2", LaneRowLog.words(LaneTarget(LaneEdge.RIGHT, 2, 0)))
        assertEquals("R3", LaneRowLog.words(LaneTarget(LaneEdge.RIGHT, 3, 0)))
        assertEquals("R1+1", LaneRowLog.words(LaneTarget(LaneEdge.RIGHT, 1, 1)))
        assertEquals("R1+2", LaneRowLog.words(LaneTarget(LaneEdge.RIGHT, 1, 2)))
        assertEquals("L1", LaneRowLog.words(LaneTarget(LaneEdge.LEFT, 1, 0)))
        assertEquals("L1+2", LaneRowLog.words(LaneTarget(LaneEdge.LEFT, 1, 2)))
    }

    @Test
    fun `the maneuver's row is written once with its lanes and its distance`() {
        val log = LaneRowLog()
        val line = requireNotNull(log.at(snapshot(toManeuver = 1_800.4)))
        assertEquals(
            mapOf(
                "source" to "maneuver",
                "key" to "r/0/2/end",
                "row" to listOf("s", "s", "s", "s", "sr*"),
                "lit" to listOf(4),
                "des" to listOf(0),
                "metersAhead" to 1_800L,
                "pending" to false,
                "offRoute" to false,
            ),
            line,
        )
        // The same lanes as a new object, nearer: no line.
        assertNull(log.at(snapshot(LaneInfo(maneuverLanes.lanes.toList()), toManeuver = 1_700.0)))
    }

    @Test
    fun `a lane choice's row has its key and its distance, and a distance alone writes nothing`() {
        val log = LaneRowLog()
        log.at(snapshot())
        val choice = LaneInfo(listOf(carPool, on, on, leaving, leaving))
        val line = requireNotNull(log.at(snapshot(laneChoiceAhead = LanesAhead(Distance.miles(0.4), choice)), choiceKey = "r/0/2/3"))
        assertEquals("choice", line["source"])
        assertEquals("r/0/2/3", line["key"])
        assertEquals(listOf("s", "s*", "s*", "sr", "sr"), line["row"])
        assertEquals(listOf(1, 2), line["lit"])
        assertEquals(listOf(0), line["des"])
        assertEquals("0.4 mi", line["label"])
        assertEquals(Math.round(Distance.miles(0.4).meters), line["metersAhead"])
        assertFalse("words" in line)
        assertNull(log.at(snapshot(laneChoiceAhead = LanesAhead(Distance.miles(0.1), choice)), choiceKey = "r/0/2/3"), "nearer only")
        // Back to the maneuver's row once the choice is passed.
        assertEquals("maneuver", log.at(snapshot())?.get("source"))
    }

    @Test
    fun `a run-up writes a line for each row it shows, with its words, need and start`() {
        val log = LaneRowLog()
        log.at(snapshot())
        val first = requireNotNull(log.at(snapshot(runUpLanes = edgeRow), runUpMeters = 400.2, runUp = "r/0/2/end", start = "edge"))
        assertEquals(
            mapOf(
                "source" to "run_up",
                "key" to "r/0/2/4",
                "row" to listOf("s", "s", "s", "s*", "sr"),
                "lit" to listOf(3),
                "des" to listOf(0),
                "words" to "R1+1",
                "need" to 1,
                "metersAhead" to 400L,
                "runUp" to "r/0/2/end",
                "start" to "edge",
                "pending" to false,
                "offRoute" to false,
            ),
            first,
        )
        assertNull(log.at(snapshot(runUpLanes = edgeRow), runUpMeters = 300.0, runUp = "r/0/2/end", start = "edge"), "the same row")
        val second = requireNotNull(log.at(snapshot(runUpLanes = exitRow), runUpMeters = 1_200.0, runUp = "r/0/2/end", start = "edge"))
        assertEquals("r/0/2/end", second["key"])
        assertEquals("R1", second["words"])
        assertEquals(0, second["need"])
        assertEquals(listOf(4), second["lit"])
    }

    @Test
    fun `a row without words says why, and the words coming write a line on the same lanes`() {
        val log = LaneRowLog()
        val quiet = edgeRow.copy(target = null, odd = RunUpRule.ODD_UNKNOWN_BRANCH)
        val line = requireNotNull(log.at(snapshot(runUpLanes = quiet), runUpMeters = 500.0, runUp = "r/0/2/end", start = "r/0/2/end"))
        assertEquals(RunUpRule.ODD_UNKNOWN_BRANCH, line["odd"])
        assertFalse("words" in line)
        // The road off is passed: the same lanes, now with words.
        val words = requireNotNull(log.at(snapshot(runUpLanes = edgeRow), runUpMeters = 450.0, runUp = "r/0/2/end", start = "r/0/2/end"))
        assertEquals("R1+1", words["words"])
        assertFalse("odd" in words)
    }

    @Test
    fun `words the card could not fit write a line without them, once, and a line again when they fit`() {
        val log = LaneRowLog()
        requireNotNull(log.at(snapshot(runUpLanes = edgeRow), runUpMeters = 400.0, runUp = "r/0/2/end", start = "edge"))
        val left = requireNotNull(log.onWordsShown("r/0/2/4", shown = false))
        assertEquals(LaneRowLog.ODD_NO_FIT, left["odd"])
        assertFalse("words" in left)
        assertEquals(listOf(3), left["lit"])
        assertEquals("r/0/2/4", left["key"])
        assertNull(log.onWordsShown("r/0/2/4", shown = false), "said already")
        // The next update of the same row keeps what the card said.
        assertNull(log.at(snapshot(runUpLanes = edgeRow), runUpMeters = 350.0, runUp = "r/0/2/end", start = "edge"))
        // A report for a row the card no longer shows, or one without words, changes nothing.
        assertNull(log.onWordsShown("r/0/2/3", shown = true))
        // They fit again, after a turn of the phone for example.
        val back = requireNotNull(log.onWordsShown("r/0/2/4", shown = true))
        assertEquals("R1+1", back["words"])
        assertFalse("odd" in back)
        // The next row's words are taken to show until the card says otherwise.
        val next = requireNotNull(log.at(snapshot(runUpLanes = exitRow), runUpMeters = 1_000.0, runUp = "r/0/2/end", start = "edge"))
        assertEquals("R1", next["words"])
        val quiet = LaneRowLog()
        quiet.at(snapshot(runUpLanes = edgeRow.copy(target = null, odd = RunUpRule.ODD_COMPOSED)))
        assertNull(quiet.onWordsShown("r/0/2/4", shown = false), "a row without words has none to leave out")
    }

    @Test
    fun `what the card shows now, for the lines that tell what it showed before a diverge`() {
        val log = LaneRowLog()
        assertNull(log.cardSource)
        assertNull(log.cardWords)
        log.at(snapshot(runUpLanes = edgeRow), runUpMeters = 400.0)
        assertEquals("run_up", log.cardSource)
        assertEquals("R1+1", log.cardWords)
        log.onWordsShown("r/0/2/4", shown = false)
        assertNull(log.cardWords, "left out")
        log.at(snapshot(laneChoiceAhead = LanesAhead(Distance.miles(0.4), maneuverLanes)), choiceKey = "r/0/2/3")
        assertEquals("choice", log.cardSource)
        assertNull(log.cardWords)
        log.reset()
        assertNull(log.cardSource)
    }

    @Test
    fun `a pending card has no row, and a new drive writes its first row again`() {
        val log = LaneRowLog()
        log.at(snapshot(runUpLanes = exitRow), runUpMeters = 900.0, runUp = "r/0/2/end", start = "resume")
        val pending = requireNotNull(log.at(snapshot(runUpLanes = exitRow, pending = true), runUpMeters = 880.0, runUp = "r/0/2/end", start = "resume"))
        assertEquals(mapOf("source" to "none", "runUp" to "r/0/2/end", "start" to "resume", "pending" to true, "offRoute" to false), pending)
        assertNull(log.at(snapshot(lanes = null, pending = true)), "no row either way")
        // Off the route the maneuver's row goes on: the same lanes write nothing new.
        log.at(snapshot())
        assertNull(log.at(snapshot(offRoute = true)))
        log.reset()
        assertNotNull(log.at(snapshot()))
        assertNull(log.onWordsShown("r/0/2/end", shown = false), "the maneuver's row has no words")
    }

    @Test
    fun `no lanes at all is a row of none, and a distance that is no number is left out`() {
        val log = LaneRowLog()
        assertEquals(mapOf("source" to "none", "pending" to false, "offRoute" to false), log.at(snapshot(lanes = null)))
        val line = requireNotNull(log.at(snapshot(runUpLanes = exitRow), runUpMeters = Double.NaN))
        assertFalse("metersAhead" in line)
        assertNull(LaneRowLog().onWordsShown("r/0/2/end", shown = false), "before any row")
    }
}
