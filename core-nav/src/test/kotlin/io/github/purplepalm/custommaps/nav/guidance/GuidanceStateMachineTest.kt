package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand.ClearPreview
import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand.ClearRoutes
import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand.CommitRoutes
import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand.StartTripSession
import io.github.purplepalm.custommaps.nav.guidance.GuidanceCommand.StopTripSession
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.GuidanceState.ACTIVE
import io.github.purplepalm.custommaps.nav.model.GuidanceState.ARRIVED
import io.github.purplepalm.custommaps.nav.model.GuidanceState.IDLE
import io.github.purplepalm.custommaps.nav.model.GuidanceState.PREVIEW
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidanceStateMachineTest {

    private val allEvents = listOf(
        GuidanceEvent.RoutesReady(2),
        GuidanceEvent.RoutesReady(0),
        GuidanceEvent.PreviewCleared,
        GuidanceEvent.StartRequested(simulated = false),
        GuidanceEvent.StartRequested(simulated = true),
        GuidanceEvent.RoutesCommitted(simulated = false),
        GuidanceEvent.RoutesCommitted(simulated = true),
        GuidanceEvent.CommitFailed,
        GuidanceEvent.Arrived,
        GuidanceEvent.Stopped(StopReason.USER_CANCELLED),
        GuidanceEvent.Stopped(StopReason.STOPPED_OUTSIDE_APP),
        GuidanceEvent.ArrivalDismissed,
    )

    private fun reduce(state: GuidanceState, event: GuidanceEvent) = GuidanceStateMachine.reduce(state, event)

    /** Whether [event] in [state] ends a drive, as the engine asks it of the transition. */
    private fun endsDrive(state: GuidanceState, event: GuidanceEvent) = GuidanceStateMachine.endsDrive(state, reduce(state, event).state)

    @Test
    fun `End from anywhere and the arrival end a drive`() {
        for (reason in StopReason.entries) assertTrue(endsDrive(ACTIVE, GuidanceEvent.Stopped(reason)), reason.name)
        assertTrue(endsDrive(ACTIVE, GuidanceEvent.Arrived))
    }

    @Test
    fun `a start whose routes were refused, and every other change, end no drive`() {
        // Back to the preview: no trip had begun, and the preview's download goes on.
        assertFalse(endsDrive(ACTIVE, GuidanceEvent.CommitFailed))
        assertFalse(endsDrive(ACTIVE, GuidanceEvent.RoutesCommitted(simulated = false)))
        assertFalse(endsDrive(ARRIVED, GuidanceEvent.ArrivalDismissed))
        assertFalse(endsDrive(PREVIEW, GuidanceEvent.PreviewCleared))
        assertFalse(endsDrive(PREVIEW, GuidanceEvent.StartRequested(simulated = false)))
        for (state in GuidanceState.entries.filter { it != ACTIVE }) {
            for (event in allEvents) assertFalse(endsDrive(state, event), "$state $event")
        }
    }

    @Test
    fun `routes ready opens the preview without any trip session`() {
        assertEquals(GuidanceTransition(PREVIEW, emptyList()), reduce(IDLE, GuidanceEvent.RoutesReady(3)))
    }

    @Test
    fun `starting from the preview hands the routes to the navigator first`() {
        assertEquals(
            GuidanceTransition(ACTIVE, listOf(CommitRoutes(simulated = true))),
            reduce(PREVIEW, GuidanceEvent.StartRequested(simulated = true)),
        )
    }

    @Test
    fun `the session starts once the navigator has the routes`() {
        assertEquals(
            GuidanceTransition(ACTIVE, listOf(StartTripSession(simulated = true))),
            reduce(ACTIVE, GuidanceEvent.RoutesCommitted(simulated = true)),
        )
    }

    @Test
    fun `routes the navigator rejects go back to the preview`() {
        assertEquals(
            GuidanceTransition(PREVIEW, listOf(StopTripSession, ClearRoutes)),
            reduce(ACTIVE, GuidanceEvent.CommitFailed),
        )
    }

    @Test
    fun `routes committed after the driver ended guidance start nothing`() {
        assertEquals(GuidanceTransition(IDLE, emptyList()), reduce(IDLE, GuidanceEvent.RoutesCommitted(simulated = false)))
    }

    @Test
    fun `cancelling stops the session before clearing routes`() {
        assertEquals(
            GuidanceTransition(IDLE, listOf(StopTripSession, ClearRoutes, ClearPreview)),
            reduce(ACTIVE, GuidanceEvent.Stopped(StopReason.USER_CANCELLED)),
        )
    }

    @Test
    fun `arrival stops the session and waits for the driver to dismiss`() {
        val arrived = reduce(ACTIVE, GuidanceEvent.Arrived)
        assertEquals(GuidanceTransition(ARRIVED, listOf(StopTripSession, ClearRoutes, ClearPreview)), arrived)
        assertEquals(GuidanceTransition(IDLE, emptyList()), reduce(ARRIVED, GuidanceEvent.ArrivalDismissed))
    }

    @Test
    fun `a new search right after arriving opens a new preview`() {
        assertEquals(PREVIEW, reduce(ARRIVED, GuidanceEvent.RoutesReady(1)).state)
    }

    @Test
    fun `an empty route response closes the preview`() {
        assertEquals(GuidanceTransition(IDLE, listOf(ClearPreview)), reduce(PREVIEW, GuidanceEvent.RoutesReady(0)))
    }

    @Test
    fun `events that do not fit the state are ignored`() {
        assertEquals(GuidanceTransition(IDLE, emptyList()), reduce(IDLE, GuidanceEvent.StartRequested(simulated = false)))
        assertEquals(GuidanceTransition(ACTIVE, emptyList()), reduce(ACTIVE, GuidanceEvent.RoutesReady(2)))
        assertEquals(GuidanceTransition(IDLE, emptyList()), reduce(IDLE, GuidanceEvent.Stopped(StopReason.USER_CANCELLED)))
    }

    @Test
    fun `a trip session starts only during guidance and once the routes are in the navigator`() {
        for (state in GuidanceState.entries) {
            for (event in allEvents) {
                val starts = reduce(state, event).commands.any { it is StartTripSession }
                val allowed = state == ACTIVE && event is GuidanceEvent.RoutesCommitted
                assertEquals(allowed, starts, "$state + $event")
            }
        }
    }

    @Test
    fun `routes reach the navigator only when the driver starts guidance from the preview`() {
        for (state in GuidanceState.entries) {
            for (event in allEvents) {
                val commits = reduce(state, event).commands.any { it is CommitRoutes }
                val allowed = state == PREVIEW && event is GuidanceEvent.StartRequested
                assertEquals(allowed, commits, "$state + $event")
            }
        }
    }

    @Test
    fun `routes are never cleared while a session may still be running`() {
        for (state in GuidanceState.entries) {
            for (event in allEvents) {
                val commands = reduce(state, event).commands
                val clearAt = commands.indexOf(ClearRoutes)
                if (clearAt >= 0) {
                    val stopAt = commands.indexOf(StopTripSession)
                    assertTrue(stopAt in 0 until clearAt, "$state + $event: $commands")
                }
            }
        }
    }
}
