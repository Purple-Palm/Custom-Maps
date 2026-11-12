package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Made-up routes only ([LaneScenes]); invented keys. */
class LaneAgreeLineTest {

    private val run = CardLanes.Source.RUN_UP

    private fun lanes(vararg lit: Boolean) = LaneInfo(lit.map { Lane(setOf(TurnModifier.STRAIGHT), isRecommended = it, activeIndication = if (it) TurnModifier.STRAIGHT else null) })

    private val plan = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(LaneScenes.ia())), CardWindows.latched(LaneScenes.ia()))
    private val other = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(LaneScenes.s9())), CardWindows.latched(LaneScenes.s9()))

    @Test
    fun `each row the card shows gets one line, once its route's plan is held`() {
        val rows = LaneAgreeLine.Rows()
        val row = lanes(false, true, false)
        assertFalse(rows.take(run, "made-up#0/0/0/2", row, words = true, plan = null), "no plan yet: wait")
        assertTrue(rows.take(run, "made-up#0/0/0/2", row, words = true, plan = plan), "the plan came: compare the row shown")
        assertFalse(rows.take(run, "made-up#0/0/0/2", row, words = true, plan = plan), "the same row again")
        assertTrue(rows.take(run, "made-up#0/0/0/end", lanes(true, true, false), words = true, plan = plan), "the next point's row")
        assertTrue(rows.take(run, "made-up#0/0/0/end", lanes(true, true, false), words = false, plan = plan), "the same lanes without words")
        assertTrue(rows.take(run, "made-up#0/0/0/end", lanes(true, true, false), words = false, plan = other), "another plan for the row")
    }

    @Test
    fun `a row the card leaves and shows again gets a line again, a pending card none`() {
        val rows = LaneAgreeLine.Rows()
        val row = lanes(true, false)
        assertTrue(rows.take(CardLanes.Source.CHOICE, "made-up#0/1/2/3", row, words = false, plan = plan))
        assertFalse(rows.take(CardLanes.Source.NONE, null, null, words = false, plan = plan), "no row: a pending card")
        assertTrue(rows.take(CardLanes.Source.CHOICE, "made-up#0/1/2/3", row, words = false, plan = plan))
        assertFalse(rows.take(CardLanes.Source.MANEUVER, null, row, words = false, plan = plan), "a row without a key")
        rows.reset()
        assertTrue(rows.take(CardLanes.Source.CHOICE, "made-up#0/1/2/3", row, words = false, plan = plan), "a new drive")
    }

    @Test
    fun `the line's fields, numbers and short names only, and its size`() {
        val route = LaneScenes.iw31()
        val iw31 = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))
        val shown = CardDrive(route).drive().first { it.row.source == run }
        val gore = iw31.topology.points.first { it.node == shown.node }
        val car = GORE - 2_000.0
        val result = CardPlanAgreement.of(shown.row, iw31, gore, car)
        val fields = LaneAgreeLine.fields(run, "made-up#0/0/3/end", result, gore.station - car)
        assertEquals(
            listOf("source", "key", "aheadM", "lit", "planLit", "amber", "agree", "why", "opensAhead", "unknownAhead", "exact", "outline"),
            fields.keys.toList(),
        )
        assertEquals("run_up", fields["source"])
        assertEquals(2_000L, fields["aheadM"])
        assertEquals(listOf(3), fields["lit"])
        assertEquals(3, fields["planLit"])
        assertEquals(listOf(3, 4), fields["amber"])
        assertEquals(false, fields["agree"])
        assertEquals("words", fields["why"])
        assertEquals(true, fields["opensAhead"])
        assertEquals(listOf(4), fields["outline"], "the exit-only lane, not yet drawn 2,000 m before the gore")
        val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LaneAgreeLine.TYPE, fields)
        // An agreeing row without outlines leaves out "why" and "outline".
        val agreeing = LaneAgreeLine.fields(run, "made-up#0/0/3/end", CardPlanAgreement.of(shown.row.copy(words = false), iw31, gore, GORE - 100.0), 100.0)
        assertEquals(null, agreeing["why"])
        assertTrue("outline" !in agreeing)
        val short = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LaneAgreeLine.TYPE, agreeing)
        println("lane_agree: ${line.length} bytes with why and outline, ${short.length} bytes agreeing")
        assertTrue(line.length < 400, "${line.length}")
        assertFalse(Regex("\"(lat|lon|latitude|longitude|name|text)\"").containsMatchIn(line))
    }
}
