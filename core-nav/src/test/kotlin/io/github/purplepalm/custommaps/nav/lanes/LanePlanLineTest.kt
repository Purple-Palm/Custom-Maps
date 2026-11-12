package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Made-up routes only ([LaneScenes]: stations in metres from the route's start, the gore at 6,000 m, invented keys). */
class LanePlanLineTest {

    private fun plan(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    /** An invented key for each node, as the engine's lane choices key their points. */
    private fun keyOf(node: Int): String = "made-up#0/0/$node/end"

    private fun fields(route: LaneRoute, carry: LaneCarry.Carry? = null, ms: Double? = 12.34): Map<String, Any?> {
        val plan = plan(route)
        return LanePlanLine.fields(plan, plan.evidence(), ::keyOf, ms, carry)
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(fields: Map<String, Any?>): List<Map<String, Any?>> = fields["windows"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun list(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>

    @Test
    fun `IA's run-up with its seam, its points by their lane from the exit's edge and its possible lane`() {
        val fields = fields(LaneScenes.ia())
        assertEquals("ia", fields["routeId"])
        assertEquals(12.3, fields["ms"])
        val runUp = windows(fields).single { it["kind"] == "run_up" }
        val exit = LaneScenes.ia().nodes.indexOfFirst { it.station == GORE }
        assertEquals(keyOf(exit), runUp["key"])
        assertEquals(-2_662L, runUp["fromM"])
        assertEquals(mapOf("latchM" to -2_295L, "narrowingM" to -2_500L, "earlierM" to 367L), runUp["seam"])
        // IA0, IA1, IA2a, IA2b and the gore: the option lane, its count from the right edge with the possible exit-only lane.
        assertEquals(listOf(-2_180L, -1_330L, -690L, -640L, 0L), list(runUp["points"]).map { it["atM"] })
        assertEquals(listOf(1, 1, 0, 1, 1), list(runUp["points"]).map { it["lane"] })
        assertEquals(2, list(runUp["points"]).last()["amber"], "the option lane and the exit-only lane at the gore")
        assertEquals(1, runUp["possibleLanes"])
        assertEquals(2, runUp["zones"], "the careful gains before IA1 and IA2a")
        assertNull(runUp["moves"])
        assertNull(runUp["unknownExits"])
        assertEquals(mapOf("activeOutsideValid" to 0, "arrowContradicts" to 0), fields["odd"])
        // The street turn at the ramp's end has no lane data: no entry.
        assertTrue(windows(fields).none { it["kind"] == "street" })
    }

    @Test
    fun `a move is placed relative to the lane point it is made for`() {
        val runUp = windows(fields(LaneScenes.s9())).single { it["kind"] == "run_up" }
        // Along the exit lane's taper: the right lane before it opens, the right lane after.
        assertEquals(listOf(mapOf<String, Any>("atM" to -210L, "from" to 0, "to" to 0)), runUp["moves"])
    }

    @Test
    fun `an unknown exit's hold, and where it was dropped, before the gore`() {
        val held = windows(fields(LaneScenes.unknownExit(900.0, 27.0))).single { it["kind"] == "run_up" }
        assertEquals(1, held["unknownExits"])
        assertNull(held["unknownExitDropped"])
        val dropped = windows(fields(LaneScenes.s17())).single { it["kind"] == "run_up" }
        assertEquals(1, dropped["unknownExits"])
        assertEquals(listOf(-320L), dropped["unknownExitDropped"])
    }

    @Test
    fun `the ramp a run-up holds through to its split, after the run-up`() {
        val entries = windows(fields(LaneScenes.iw31()))
        assertEquals(listOf("run_up", "ramp"), entries.map { it["kind"] })
        val ramp = entries[1]
        assertEquals(-260L, ramp["fromM"], "from the gore, 260 m before the split")
        assertEquals(listOf(0L), list(ramp["points"]).map { it["atM"] }, "the split; the gore is the run-up's")
        assertEquals(listOf(0), list(ramp["points"]).map { it["lane"] }, "the ramp's left lane, counted from the fork's side")
        assertEquals(1, list(entries[0]["points"]).last()["lane"], "at the gore still from the exit's right edge")
    }

    @Test
    fun `approaches where no lane can be named say why`() {
        val only = windows(fields(LaneScenes.geomS5()))
        assertEquals("approach", only.single()["kind"], "no card window: the close view's own")
        assertEquals("designatedOnly", only.single()["conflict"])
        assertTrue(list(only.single()["points"]).all { it["lane"] == null && it["amber"] == 0 })
        val beyond = windows(fields(LaneScenes.carpoolBeyond())).single { it["kind"] == "run_up" }
        assertEquals("designatedBetween", beyond["conflict"])
        assertTrue((beyond["conflictM"] as List<*>).isNotEmpty())
        // The approach and the way round, each a street window without a lane named.
        val roundabout = windows(fields(LaneScenes.roundabout()))
        assertEquals(listOf("street", "street"), roundabout.map { it["kind"] })
        assertTrue(roundabout.all { it["roundabout"] == true })
    }

    @Test
    fun `a change of route says whether the light moves and whether the count was kept`() {
        val carry = LaneCarry.Carry(from = 0.0, to = 50.0, lane = 3, fromEdge = 1, edge = null, kept = true, moveTo = 4)
        val fields = fields(LaneScenes.ia(), carry)
        assertEquals(1, fields["replanMoves"])
        assertEquals(true, fields["replanKept"])
        val none = fields(LaneScenes.ia(), carry.copy(kept = false, moveTo = null))
        assertEquals(0, none["replanMoves"])
        assertEquals(false, none["replanKept"])
    }

    @Test
    fun `numbers, keys and short names only, and its size`() {
        for ((name, route) in LaneScenes.all()) {
            val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LanePlanLine.TYPE, fields(route))
            assertFalse(Regex("\"(lat|lon|latitude|longitude|name|text)\"").containsMatchIn(line), name)
            assertTrue(line.length < 2_000, "$name: ${line.length}")
        }
        val ia = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LanePlanLine.TYPE, fields(LaneScenes.ia())).length
        val iw31 = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LanePlanLine.TYPE, fields(LaneScenes.iw31())).length
        println("lane_plan: IA $ia bytes, IW31 $iw31 bytes")
        assertTrue(ia in 300..900, "IA's line: $ia bytes")
    }
}
