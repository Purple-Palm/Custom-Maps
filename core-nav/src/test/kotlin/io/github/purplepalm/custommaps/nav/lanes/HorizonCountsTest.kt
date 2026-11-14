package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the app listens to the horizon ([HorizonListening]) and what a trip read of it ([HorizonCounts]): invented values. */
class HorizonCountsTest {

    @Test
    fun `the horizon is listened to only with the switch on, during guidance, once the trip session runs`() {
        assertTrue(HorizonListening.wanted(switchOn = true, guidanceActive = true, tripSessionRunning = true))
        for (switchOn in listOf(true, false, null)) {
            for (guidance in listOf(true, false)) {
                for (session in listOf(true, false)) {
                    if (switchOn == true && guidance && session) continue
                    assertFalse(HorizonListening.wanted(switchOn, guidance, session), "$switchOn $guidance $session")
                }
            }
        }
    }

    private fun point(listed: Int, general: Int) = HorizonPoint(0, 0.0, listed, general, RoadKind.FREEWAY, null, null)

    private fun edge(lanes: Int?) = HorizonEdge(100.0, lanes, motorway = true, ramp = false, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    private fun compared(listed: Int, general: Int, count: Int?, matched: Boolean = true): HorizonLanesLine.Comparison {
        val edge = if (matched) edge(count) else null
        return HorizonLanesLine.Comparison(point(listed, general), 100.0, 0.0, edge, HorizonConvention.of(edge?.laneCount, listed, general))
    }

    @Test
    fun `a trip adds up its updates, reads and comparisons, by the reading that agreed`() {
        val counts = HorizonCounts()
        counts.onSwitch(true)
        counts.begin(0L)
        counts.onListening(true, 1_000L)
        counts.onPosition(true)
        counts.onPosition(false)
        counts.onPosition(true)
        counts.onRead(12)
        counts.onRead(3)
        counts.onCompared(
            listOf(
                compared(5, 5, 5),
                compared(4, 3, 3),
                compared(4, 3, 2),
                compared(5, 5, null),
                compared(5, 5, 5, matched = false),
            ),
        )
        counts.onStretchesUsed(4)
        counts.onWindow()

        assertEquals(
            linkedMapOf(
                "on" to true,
                "switched" to 0,
                "listenedS" to 9.0,
                "updates" to 3,
                "notAvailable" to 1,
                "edges" to 15,
                "points" to 5,
                "agreeAll" to 1,
                "agreeGeneral" to 1,
                "disagree" to 1,
                "noCount" to 2,
                "stretchesUsed" to 4,
                "windows" to 1,
                "failed" to false,
            ),
            counts.fields(10_000L),
        )
    }

    @Test
    fun `the time listened adds up every spell, the open one too, and the switch's changes count within a trip only`() {
        val counts = HorizonCounts()
        counts.onSwitch(false)
        counts.onSwitch(true)
        counts.begin(0L)
        assertEquals(0, counts.fields(0L)["switched"])
        counts.onListening(true, 0L)
        counts.onSwitch(false)
        counts.onListening(false, 4_000L)
        counts.onSwitch(true)
        counts.onListening(true, 6_000L)
        assertEquals(6.5, counts.fields(8_500L)["listenedS"])
        assertEquals(2, counts.fields(8_500L)["switched"])
        assertEquals(true, counts.fields(8_500L)["on"])
        counts.onFailure()
        assertEquals(true, counts.fields(8_500L)["failed"])
    }

    @Test
    fun `nothing is counted outside a trip, and the next trip starts over with the switch as it is`() {
        val counts = HorizonCounts()
        counts.onPosition(true)
        counts.onRead(5)
        counts.onListening(true, 0L)
        counts.onFailure()
        counts.begin(1_000L)
        val first = counts.fields(2_000L)
        assertEquals(false, first["on"], "the switch was never heard of: off")
        assertEquals(0, first["updates"])
        assertEquals(0, first["edges"])
        assertEquals(0.0, first["listenedS"])
        assertEquals(false, first["failed"])
        counts.onRead(2)
        counts.end()
        counts.onRead(7)
        counts.onSwitch(true)
        counts.begin(5_000L)
        val second = counts.fields(5_000L)
        assertEquals(0, second["edges"])
        assertEquals(true, second["on"])
        assertEquals(0, second["switched"])
        assertFalse(HorizonCounts().running)
        assertTrue(counts.running)
    }
}
