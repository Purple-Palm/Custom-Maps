package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule.LanePoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Made-up approaches only. The lanes are written from the left as a row: H for a lane kept for car
 * pools, s for straight on, sr and sl for slight right and left; a lit lane has a star. The
 * distances are metres before the exit's gore, round synthetic numbers.
 */
class RunUpRuleTest {

    private val s = TurnModifier.STRAIGHT
    private val sr = TurnModifier.SLIGHT_RIGHT
    private val sl = TurnModifier.SLIGHT_LEFT
    private val right = LaneEdge.RIGHT
    private val left = LaneEdge.LEFT

    /** A lane open to every car; the route prefers it where it can be followed from it, unless told otherwise. */
    private fun open(valid: Boolean? = true, vararg arrows: TurnModifier, active: Boolean? = valid) =
        RouteLane(arrows.toSet().ifEmpty { setOf(s) }, valid = valid, active = active)

    private fun s(valid: Boolean = true) = open(valid, s)
    private fun sr(valid: Boolean = true) = open(valid, sr)
    private fun sl(valid: Boolean = true) = open(valid, sl)

    /** A lane kept for car pools, which the route does not use. */
    private fun hov(valid: Boolean = false, active: Boolean? = valid) = RouteLane(setOf(s), valid = valid, active = active, designated = true)

    private fun point(key: String, ahead: Double, vararg lanes: RouteLane) = LanePoint(key, ahead, lanes.toList())

    /** The row as the design's rig prints it, with the point it shows and its words. */
    private fun describe(row: RunUpLanes?): String {
        if (row == null) return "none"
        val lanes = row.lanes.lanes.joinToString(" ", "[", "]") { lane ->
            val token = if (lane.designated) "H" else lane.indications.joinToString("+") { arrow(it) }.ifEmpty { "s" }
            if (lane.isRecommended) "$token*" else token
        }
        return "${row.key} $lanes '${say(row.target)}'" + (row.odd?.let { " odd=$it" } ?: "")
    }

    private fun arrow(turn: TurnModifier) = when (turn) {
        TurnModifier.STRAIGHT -> "s"
        TurnModifier.SLIGHT_RIGHT -> "sr"
        TurnModifier.SLIGHT_LEFT -> "sl"
        TurnModifier.RIGHT -> "r"
        TurnModifier.LEFT -> "l"
        TurnModifier.SHARP_RIGHT -> "shr"
        TurnModifier.SHARP_LEFT -> "shl"
        TurnModifier.U_TURN -> "u"
    }

    /** The short words of the design for a target: the app's strings say the same. */
    private fun say(target: LaneTarget?): String {
        if (target == null) return ""
        val side = if (target.edge == LaneEdge.RIGHT) "right" else "left"
        return when {
            target.offset == 0 && target.count == 1 -> side.replaceFirstChar { it.uppercase() } + " lane"
            target.offset == 0 -> "${target.count} $side"
            else -> listOf("2nd", "3rd")[target.offset - 1] + " from $side"
        }
    }

    /** An approach to an exit: points on the way at metres before the gore, and the exit's lanes. */
    private inner class Approach(
        val edge: LaneEdge?,
        val points: List<Pair<Double, LanePoint>>,
        val exit: List<RouteLane>,
        val branches: List<Double> = emptyList(),
    ) {
        /** The row with the car [toGore] metres before the gore. */
        fun rowAt(toGore: Double): RunUpLanes? {
            val ahead = points.filter { it.first <= toGore }.map { (at, p) -> p.copy(metersAhead = toGore - at) }
            return RunUpRule.row(edge, ahead, "E", exit, branchAhead = branches.any { it <= toGore })
        }
    }

    private fun at(meters: Double, key: String, vararg lanes: RouteLane) = meters to point(key, 0.0, *lanes)

    // ------------------------------------------------------------------ the exit's side

    @Test
    fun `the exit's side comes from the maneuver's own direction`() {
        val lanes = listOf(s(false), s(false), sr())
        for (turn in listOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)) {
            assertEquals(right, RunUpRule.exitEdge(turn, lanes), "$turn")
            // Whatever the lanes say: the step's own maneuver decides.
            assertEquals(right, RunUpRule.exitEdge(turn, listOf(sl(), s(false), s(false))), "$turn")
        }
        for (turn in listOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT)) {
            assertEquals(left, RunUpRule.exitEdge(turn, lanes), "$turn")
        }
    }

    @Test
    fun `straight on or without a direction the lanes to use tell the side`() {
        for (turn in listOf(TurnModifier.STRAIGHT, TurnModifier.U_TURN, null)) {
            assertEquals(right, RunUpRule.exitEdge(turn, listOf(s(false), s(false), sr(), sr())), "$turn")
            assertEquals(left, RunUpRule.exitEdge(turn, listOf(sl(), s(false), s(false))), "$turn")
            // A lane kept for car pools at the edge is not the edge lane.
            assertEquals(left, RunUpRule.exitEdge(turn, listOf(hov(), sl(), s(false), s(false))), "$turn")
            // In the middle, on both edges or nowhere: not known.
            assertNull(RunUpRule.exitEdge(turn, listOf(s(false), s(), s(), s(false))), "$turn")
            assertNull(RunUpRule.exitEdge(turn, listOf(s(), s(), s())), "$turn")
            assertNull(RunUpRule.exitEdge(turn, listOf(s(false), s(false))), "$turn")
            assertNull(RunUpRule.exitEdge(turn, emptyList()), "$turn")
        }
        // The lanes the route prefers are the lanes to use where it prefers some.
        assertEquals(right, RunUpRule.exitEdge(null, listOf(open(true, s, active = false), s(false), open(true, sr, active = true))))
    }

    // ------------------------------------------------------------------ edge offsets and edge points

    @Test
    fun `the edge offset counts open lanes from the exit's side before a usable one`() {
        val lanes = listOf(hov(), s(), s(), s(false), s(false))
        assertEquals(2, RunUpRule.edgeOffset(lanes, right))
        assertEquals(0, RunUpRule.edgeOffset(lanes, left), "the car pool lane on the left is no lane to count")
        val hovRight = listOf(s(false), s(), s(), hov())
        assertEquals(0, RunUpRule.edgeOffset(hovRight, right), "the car pool lane on the right is passed over")
        assertEquals(1, RunUpRule.edgeOffset(hovRight, left))
        // A lane kept for car pools that the route marks still cannot be used by this car.
        assertEquals(1, RunUpRule.edgeOffset(listOf(s(), s(), s(false), hov(valid = true)), right))
    }

    @Test
    fun `an edge point is where the lane at the exit's side leaves while another goes on`() {
        assertTrue(RunUpRule.edgeLeaves(listOf(hov(), s(), s(), s(), s(false)), right))
        assertFalse(RunUpRule.edgeLeaves(listOf(hov(), s(false), s(), s(), s()), right), "the far side's lane leaves")
        assertTrue(RunUpRule.edgeLeaves(listOf(hov(), s(false), s(), s(), s()), left))
        assertFalse(RunUpRule.edgeLeaves(listOf(s(), s(), s()), right), "every lane goes on")
        // A point where no lane can be used tells nothing: no edge point.
        assertNull(RunUpRule.edgeOffset(listOf(s(false), s(false), s(false)), right))
        assertFalse(RunUpRule.edgeLeaves(listOf(s(false), s(false), s(false)), right))
        assertTrue(RunUpRule.edgePoints(listOf(point("A", 300.0, s(false), s(false), s(false))), right).isEmpty())
        // Without the exit's side there are no edge points.
        assertFalse(RunUpRule.edgeLeaves(listOf(s(), s(), s(false)), null))
        // Lanes without the route's flags cannot be used.
        assertNull(RunUpRule.edgeOffset(listOf(open(null, s), open(null, s)), right))
    }

    @Test
    fun `points close together with the same lanes are one point`() {
        val oneLeaves = arrayOf(s(), s(), s(), s(false))
        val twoLeave = arrayOf(s(), s(), s(false), s(false))
        // 100 m apart: one point, a lane usable only where it is usable at both.
        val close = RunUpRule.edgePoints(listOf(point("A", 500.0, *oneLeaves), point("B", 600.0, *twoLeave)), right)
        assertEquals(1, close.size)
        assertEquals(2, close[0].lo)
        assertEquals("B", close[0].key, "the key of the last of them, which stays until all are passed")
        assertEquals(500.0, close[0].metersAhead)
        // 101 m apart: two points.
        val apart = RunUpRule.edgePoints(listOf(point("A", 500.0, *oneLeaves), point("B", 601.0, *twoLeave)), right)
        assertEquals(listOf("A" to 1, "B" to 2), apart.map { it.key to it.lo })
        // A chain of points each within 100 m of the one before is one point.
        val chain = RunUpRule.edgePoints((0..4).map { point("C$it", 500.0 + 90.0 * it, *oneLeaves) }, right)
        assertEquals(listOf("C4"), chain.map { it.key })
        // Another number of lanes, or another lane kept for car pools: two points.
        val wider = RunUpRule.edgePoints(listOf(point("A", 500.0, *oneLeaves), point("B", 550.0, s(), s(), s(), s(), s(false))), right)
        assertEquals(2, wider.size)
        val restricted = RunUpRule.edgePoints(listOf(point("A", 500.0, *oneLeaves), point("B", 550.0, hov(), s(), s(), s(false))), right)
        assertEquals(2, restricted.size)
        // Points behind the car or at no known distance are not on the way.
        val behind = RunUpRule.edgePoints(listOf(point("A", -5.0, *oneLeaves), point("B", Double.NaN, *oneLeaves), point("C", 300.0, *oneLeaves)), right)
        assertEquals(listOf("C"), behind.map { it.key })
    }

    @Test
    fun `the row draws the nearest of the points taken as one`() {
        val near = point("A", 500.0, s(), s(), s(), open(false, s, sr))
        val far = point("B", 560.0, s(), s(), s(), s(false))
        val row = RunUpRule.row(right, listOf(near, far), "E", listOf(s(false), s(false), s(false), sr()), branchAhead = false)
        assertEquals("B [s s s* s+sr] '2nd from right'", describe(row))
    }

    // ------------------------------------------------------------------ the composed lane

    @Test
    fun `two edge points with no lane added light the same lane at both (the challenge's X1)`() {
        // T1: the right lane leaves at 1,400 m. T2: the two right lanes leave at 800 m. The exit
        // takes the right lane of three. The lane to be in at T1 is the second from the left: of
        // T1's five lanes the right one leaves, and T2's four lanes are the other four.
        val x1 = Approach(
            right,
            listOf(at(1_400.0, "T1", s(), s(), s(), s(), sr(false)), at(800.0, "T2", s(), s(), sr(false), sr(false))),
            listOf(s(false), s(false), sr()),
        )
        val points = RunUpRule.edgePoints(
            listOf(point("T1", 400.0, s(), s(), s(), s(), sr(false)), point("T2", 1_000.0, s(), s(), sr(false), sr(false))),
            right,
        )
        assertEquals(listOf(RunUpRule.Composed(3, true), RunUpRule.Composed(2, true)), RunUpRule.compose(points, 0, 0))
        val atT1 = x1.rowAt(1_800.0)
        assertEquals("T1 [s s* s s sr] ''", describe(atT1), "offset 3 has no words")
        assertEquals(3, atT1?.need)
        assertNull(atT1?.odd, "exact, only too far in for words")
        val atT2 = x1.rowAt(1_399.0)
        assertEquals("T2 [s s* sr sr] '3rd from right'", describe(atT2))
        assertEquals(2, atT2?.need)
        assertEquals("E [s s sr*] 'Right lane'", describe(x1.rowAt(799.0)))
    }

    @Test
    fun `a lane added between two edge points is taken on the far side and the row has no words`() {
        // S20 of the design: both points have four lanes, so one lane came between them.
        val s20 = Approach(
            right,
            listOf(at(1_600.0, "T1", s(), s(), s(), sr(false)), at(700.0, "T2", s(), s(), s(), sr(false))),
            listOf(s(false), s(false), s(false), sr()),
        )
        val atT1 = s20.rowAt(2_000.0)
        assertEquals("T1 [s s* s sr] '' odd=composed", describe(atT1))
        assertEquals(2, atT1?.need, "the careful lane: one nearer the far side than if the lane was added on the exit's side")
        assertEquals("T2 [s s s* sr] '2nd from right'", describe(s20.rowAt(1_599.0)))
        assertEquals("E [s s s sr*] 'Right lane'", describe(s20.rowAt(699.0)))
    }

    @Test
    fun `a lane gone between two edge points is taken at the exit's side and the need grows`() {
        // Five lanes at T1, of which the right one leaves; three at T2: one more went in between.
        val gone = Approach(
            right,
            listOf(at(1_400.0, "T1", s(), s(), s(), s(), sr(false)), at(800.0, "T2", s(), s(), sr(false))),
            listOf(s(false), s(false), sr()),
        )
        val atT1 = gone.rowAt(1_800.0)
        assertEquals("T1 [s s* s s sr] '' odd=composed", describe(atT1))
        assertEquals(3, atT1?.need, "2 without the lane that went")
        assertEquals("T2 [s s* sr] '2nd from right'", describe(gone.rowAt(1_399.0)))
    }

    @Test
    fun `a car pool lane that begins at the exit's side takes an open lane there (S16)`() {
        // A left exit. The leftmost lane leaves at 1,200 m; then the new leftmost lane is kept for
        // car pools, and the exit is taken from the lane beside it.
        val s16 = Approach(
            left,
            listOf(at(1_200.0, "T1", sl(false), s(), s(), s(), s())),
            listOf(hov(), sl(), s(false), s(false)),
        )
        val atT1 = s16.rowAt(2_000.0)
        assertEquals("T1 [sl s s* s s] '3rd from left'", describe(atT1))
        assertEquals(2, atT1?.need)
        assertEquals("E [H sl* s s] 'Left lane'", describe(s16.rowAt(1_199.0)))
    }

    @Test
    fun `a lane is kept within the open lanes, without words`() {
        // Three lanes at T1, the right one leaving; T2 has four, its two right ones leaving. The
        // careful lane would be the fourth from the right of three.
        val tight = Approach(
            right,
            listOf(at(1_400.0, "T1", s(), s(), sr(false)), at(800.0, "T2", s(), s(), sr(false), sr(false))),
            listOf(s(false), s(false), sr()),
        )
        val atT1 = tight.rowAt(1_800.0)
        assertEquals("T1 [s* s sr] '' odd=composed", describe(atT1))
        assertEquals(2, atT1?.need)
        // A point of two lanes before an exit taken from the third lane from the right: no lane
        // there leads to it as counted, so the exit's row has no words until it is passed.
        val narrow = Approach(right, listOf(at(600.0, "Q", s(), s())), listOf(s(false), sr(), s(false), s(false)))
        assertEquals("E [s sr* s s] '' odd=composed", describe(narrow.rowAt(1_000.0)))
        assertEquals("E [s sr* s s] '3rd from right'", describe(narrow.rowAt(599.0)))
    }

    @Test
    fun `a lane that cannot be used at a point is never lit there`() {
        // The exit is taken from the second lane from the right. At P the right lane leaves, and so
        // does, by the data, the lane the count leads to: the usable lane below it is lit, without words.
        val odd = Approach(
            right,
            listOf(at(900.0, "P", s(), s(false), s(), sr(false))),
            listOf(s(false), s(false), sr(), sr(false)),
        )
        val atP = odd.rowAt(1_500.0)
        assertEquals("P [s s s* sr] '' odd=composed", describe(atP))
        assertEquals(1, atP?.need)
    }

    @Test
    fun `an exit whose lanes to use are not at the edge counts from them`() {
        val inner = Approach(
            right,
            listOf(at(900.0, "T", s(), s(), s(), sr(false))),
            listOf(s(false), s(false), sr(), s(false)),
        )
        assertEquals("T [s s* s sr] '3rd from right'", describe(inner.rowAt(1_500.0)))
        assertEquals("E [s s sr* s] '2nd from right'", describe(inner.rowAt(899.0)))
        assertEquals(1, inner.rowAt(899.0)?.need)
    }

    @Test
    fun `a point that is no edge point takes part only through the bounds`() {
        // A split on the far side at 600 m: nothing changes for the exit's lane.
        val farSplit = Approach(right, listOf(at(600.0, "Q", s(false), s(false), s(), s(), s())), listOf(s(false), s(false), s(false), s(false), sr()))
        assertEquals("E [s s s s sr*] 'Right lane'", describe(farSplit.rowAt(1_000.0)))
        // A point where the exit's lane cannot be used as counted: the exit, taken from the third
        // lane from the right, has no words either until the point is passed.
        val narrowing = Approach(right, listOf(at(600.0, "Q", s(), s(false), s(), s())), listOf(s(false), sr(), s(false), s(false)))
        assertEquals("E [s sr* s s] '' odd=composed", describe(narrowing.rowAt(1_000.0)))
        assertEquals("E [s sr* s s] '3rd from right'", describe(narrowing.rowAt(599.0)))
    }

    // ------------------------------------------------------------------ the exit's own row

    @Test
    fun `the exit's row lights the lanes the route prefers where it prefers some`() {
        val preferred = listOf(s(false), open(true, sr, active = false), open(true, sr, active = true))
        assertEquals("E [s sr sr*] 'Right lane'", describe(RunUpRule.row(right, emptyList(), "E", preferred, false)))
        val none = listOf(s(false), open(true, sr, active = false), open(true, sr, active = false))
        assertEquals("E [s sr* sr*] '2 right'", describe(RunUpRule.row(right, emptyList(), "E", none, false)))
        val unknown = listOf(s(false), open(true, sr, active = null), open(true, sr, active = null))
        assertEquals("E [s sr* sr*] '2 right'", describe(RunUpRule.row(right, emptyList(), "E", unknown, false)))
        // An option lane: the arrow the route names is the lit one.
        val option = RouteLane(setOf(s, sr), valid = true, active = true, validIndication = sr)
        val row = RunUpRule.row(right, emptyList(), "E", listOf(s(false), s(false), option, sr()), false)
        assertEquals("E [s s s+sr* sr*] '2 right'", describe(row))
        assertEquals(sr, row?.lanes?.lanes?.get(2)?.activeIndication)
        assertEquals(0, row?.need)
    }

    @Test
    fun `the exit's row has words only for few lanes side by side at the edge`() {
        fun words(vararg lanes: RouteLane) = describe(RunUpRule.row(right, emptyList(), "E", lanes.toList(), false))
        assertEquals("E [s s* s* s*] '3 right'", words(s(false), s(), s(), s()))
        assertEquals("E [s s* s* s* s*] ''", words(s(false), s(), s(), s(), s()), "four lanes")
        assertEquals("E [s* s* s*] ''", words(s(), s(), s()), "every open lane: nothing to choose")
        assertEquals("E [H s* s* s*] ''", words(hov(), s(), s(), s()), "every open lane, the car pool lane aside")
        assertEquals("E [s s* s s s] ''", words(s(false), s(), s(false), s(false), s(false)), "offset 3")
        assertEquals("E [s s* s s] '3rd from right'", words(s(false), s(), s(false), s(false)))
        assertEquals("E [sr* s sr*] '' odd=pieces", words(sr(), s(false), sr()))
        assertEquals("E [s s* s s* s] '' odd=pieces", words(s(false), s(), s(false), s(), s(false)))
    }

    @Test
    fun `a car pool lane is never lit while an open lane can be used`() {
        val row = RunUpRule.row(right, emptyList(), "E", listOf(hov(valid = true), s(false), s(false), sr()), false)
        assertEquals("E [H s s sr*] 'Right lane'", describe(row))
        assertTrue(row!!.lanes.lanes[0].designated)
        assertFalse(row.lanes.lanes[0].isRecommended)
        // Points on the way never light it either: the composed lane is an open one.
        val onTheWay = Approach(right, listOf(at(900.0, "T", hov(valid = true), s(), s(), sr(false))), listOf(hov(), s(false), s(false), sr()))
        assertEquals("T [H s s* sr] '2nd from right'", describe(onTheWay.rowAt(1_500.0)))
    }

    @Test
    fun `where only car pool lanes are marked they keep their arrow lit, without words`() {
        val row = RunUpRule.row(right, emptyList(), "E", listOf(hov(valid = true), s(false), s(false)), false)
        assertEquals("E [H* s s] '' odd=designated_only", describe(row))
        assertTrue(row!!.lanes.lanes[0].designated)
        assertTrue(row.lanes.lanes[0].isRecommended)
        assertEquals(s, row.lanes.lanes[0].activeIndication)
        assertNull(row.need)
        val onlyPreferred = RouteLane(setOf(s), valid = null, active = true, designated = true)
        val unknownSide = RunUpRule.row(null, emptyList(), "E", listOf(onlyPreferred, s(false), s(false)), false)
        assertEquals("E [H* s s] '' odd=designated_only", describe(unknownSide))
        // Nothing marked at all: nothing lit.
        assertEquals("E [H s s] ''", describe(RunUpRule.row(right, emptyList(), "E", listOf(hov(), s(false), s(false)), false)))
    }

    @Test
    fun `without the exit's side the row is the exit's own lanes without words`() {
        val lanes = listOf(s(false), s(), s(), s(false))
        val points = listOf(point("T", 400.0, s(), s(), s(false), s(false)))
        val row = RunUpRule.row(null, points, "E", lanes, branchAhead = false)
        assertEquals("E [s s* s* s] ''", describe(row))
        assertNull(row?.need)
    }

    @Test
    fun `an exit without lanes has no row`() {
        assertNull(RunUpRule.row(right, emptyList(), "E", emptyList(), false))
    }

    @Test
    fun `a road without lane data on the exit's side keeps the words back until it is passed (S17)`() {
        // Exits close together: the route takes exit B, and exit A, 300 m before it, has no lanes.
        val s17 = Approach(right, emptyList(), listOf(s(false), s(false), s(false), s(false), sr()), branches = listOf(300.0))
        assertEquals("E [s s s s sr*] '' odd=unknown_branch", describe(s17.rowAt(900.0)))
        assertEquals("E [s s s s sr*] 'Right lane'", describe(s17.rowAt(299.0)))
        // A row that has no words for its own reason keeps that reason.
        val inexact = Approach(
            right,
            listOf(at(1_600.0, "T1", s(), s(), s(), sr(false)), at(700.0, "T2", s(), s(), s(), sr(false))),
            listOf(s(false), s(false), s(false), sr()),
            branches = listOf(300.0),
        )
        assertEquals("T1 [s s* s sr] '' odd=composed", describe(inexact.rowAt(2_000.0)))
        assertEquals("T2 [s s s* sr] '' odd=unknown_branch", describe(inexact.rowAt(1_599.0)))
    }

    // ------------------------------------------------------------------ the design's approaches

    /**
     * The lane design's approach at its rounded, synthetic distances, on made-up lanes: points at
     * 2,000 m (P0), 1,500, 1,470, 1,460 and 1,440 m (P1a to P1d, one junction) and 400 m (P2)
     * before the gore, five lanes at each; the exit on the right takes the two right lanes of six.
     * On the exit's side ([onExitSide]) the right lane leaves at P0, the right and the left lane
     * at P1; on the far side the left lane leaves at P0 and the two left lanes at P1. The two left
     * lanes leave at P2 either way.
     */
    private fun designApproach(onExitSide: Boolean): Approach {
        fun five(vararg valid: Boolean) = valid.map { s(it) }.toTypedArray()
        val p0 = if (onExitSide) five(true, true, true, true, false) else five(false, true, true, true, true)
        val p1 = if (onExitSide) five(false, true, true, true, false) else five(false, false, true, true, true)
        val p2 = five(false, false, true, true, true)
        return Approach(
            right,
            listOf(
                at(2_000.0, "P0", *p0),
                at(1_500.0, "P1a", *p1),
                at(1_470.0, "P1b", *p1),
                at(1_460.0, "P1c", *p1),
                at(1_440.0, "P1d", *p1),
                at(400.0, "P2", *p2),
            ),
            listOf(s(false), s(false), s(false), s(false), sr(), sr()),
        )
    }

    @Test
    fun `the design's approach where lanes leave on the exit's side on the way (S1)`() {
        val s1 = designApproach(onExitSide = true)
        // From where the run-up begins: P0's row on the middle lane, no words, as a lane was added
        // between P0 and P1 and its side is not known.
        assertEquals("P0 [s s s* s s] '' odd=composed", describe(s1.rowAt(2_125.0)))
        assertEquals(2, s1.rowAt(2_125.0)?.need)
        // Past P0: P1's row, the second lane from the right.
        assertEquals("P1d [s s s s* s] '2nd from right'", describe(s1.rowAt(1_999.0)))
        // The same row while P1's points are passed one by one: the same lanes, words and key.
        assertEquals(s1.rowAt(1_999.0), s1.rowAt(1_469.0))
        assertEquals(s1.rowAt(1_999.0), s1.rowAt(1_441.0))
        // Past P1: the exit's own lanes, the two right ones; P2 on the way agrees with them.
        assertEquals("E [s s s s sr* sr*] '2 right'", describe(s1.rowAt(1_439.0)))
        assertEquals("E [s s s s sr* sr*] '2 right'", describe(s1.rowAt(399.0)))
        assertEquals(s1.rowAt(1_439.0), s1.rowAt(1.0))
    }

    @Test
    fun `the design's approach where lanes leave on the far side on the way (S2)`() {
        val s2 = designApproach(onExitSide = false)
        for (toGore in listOf(2_125.0, 1_999.0, 1_469.0, 1_439.0, 399.0, 0.0)) {
            assertEquals("E [s s s s sr* sr*] '2 right'", describe(s2.rowAt(toGore)), "$toGore m before the gore")
        }
    }

    @Test
    fun `a left exit with two right lanes leaving before it (S5)`() {
        val s5 = Approach(left, listOf(at(1_100.0, "Q", hov(), s(), s(), s(false), sr(false))), listOf(hov(), sl(), s(false), s(false), s(false)))
        assertEquals("E [H sl* s s s] 'Left lane'", describe(s5.rowAt(1_800.0)))
        assertEquals("E [H sl* s s s] 'Left lane'", describe(s5.rowAt(1_099.0)))
    }

    @Test
    fun `a two-lane exit with an option lane after a split on the far side (S6)`() {
        val option = open(true, s, sr)
        val s6 = Approach(right, listOf(at(1_200.0, "Q", sl(false), sl(false), s(), s(), s())), listOf(s(false), s(false), s(false), option, sr()))
        assertEquals("E [s s s s+sr* sr*] '2 right'", describe(s6.rowAt(1_900.0)))
    }

    @Test
    fun `a fork on its left branch (S7)`() {
        val s7 = Approach(left, listOf(at(800.0, "Q", s(), s(), s(), s(), s())), listOf(sl(), sl(), sl(), sr(false), sr(false)))
        assertEquals("E [sl* sl* sl* sr sr] '3 left'", describe(s7.rowAt(700.0)))
        assertEquals(LaneTarget(LaneEdge.LEFT, 3, 0), s7.rowAt(700.0)?.target)
    }

    @Test
    fun `a lane that ends on the exit's side before the exit (S8)`() {
        val s8 = Approach(right, listOf(at(500.0, "D", s(), s(), s(), s(false))), listOf(s(false), s(false), s(false), sr()))
        assertEquals("D [s s s* s] '2nd from right'", describe(s8.rowAt(2_400.0)))
        assertEquals("E [s s s sr*] 'Right lane'", describe(s8.rowAt(499.0)))
    }

    @Test
    fun `exits close together, the second one taken (S9)`() {
        // Exit A's gore 300 m before exit B takes the right lane.
        val s9 = Approach(right, listOf(at(300.0, "A", s(), s(), s(), s(), sr(false))), listOf(s(false), s(false), s(false), s(false), sr()))
        assertEquals("A [s s s s* sr] '2nd from right'", describe(s9.rowAt(2_200.0)))
        assertEquals("E [s s s s sr*] 'Right lane'", describe(s9.rowAt(299.0)))
    }

    @Test
    fun `within a run-up the words never name the far side`() {
        val approaches = listOf(
            designApproach(true), designApproach(false),
            Approach(left, listOf(at(1_200.0, "T1", sl(false), s(), s(), s(), s())), listOf(hov(), sl(), s(false), s(false))),
            Approach(right, listOf(at(500.0, "D", s(), s(), s(), s(false))), listOf(s(false), s(false), s(false), sr())),
        )
        for (approach in approaches) {
            for (toGore in 2_200 downTo 0 step 25) {
                val target = approach.rowAt(toGore.toDouble())?.target ?: continue
                assertEquals(approach.edge, target.edge, "$toGore m")
                assertTrue(target.hasWords)
            }
        }
    }
}
