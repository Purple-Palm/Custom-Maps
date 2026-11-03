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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidanceStateMachineTest {

    private val allEvents = listOf(
        GuidanceEvent.RoutesReady(2),
        GuidanceEvent.RoutesReady(0),
        GuidanceEvent.PreviewCleared,
        GuidanceEvent.StartRequested(simulated = false),
        GuidanceEvent.StartRequested(simulated = true),
        GuidanceEvent.Arrived,
        GuidanceEvent.Stopped(StopReason.USER_CANCELLED),
        GuidanceEvent.Stopped(StopReason.CAR_DISCONNECTED),
        GuidanceEvent.ArrivalDismissed,
    )

    private fun reduce(state: GuidanceState, event: GuidanceEvent) = GuidanceStateMachine.reduce(state, event)

    @Test
    fun `routes ready opens the preview without any trip session`() {
        assertEquals(GuidanceTransition(PREVIEW, emptyList()), reduce(IDLE, GuidanceEvent.RoutesReady(3)))
    }

    @Test
    fun `starting from the preview commits routes and then starts the session`() {
        assertEquals(
            GuidanceTransition(ACTIVE, listOf(CommitRoutes, StartTripSession(simulated = true))),
            reduce(PREVIEW, GuidanceEvent.StartRequested(simulated = true)),
        )
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
    fun `a trip session starts only when the driver starts guidance from the preview`() {
        for (state in GuidanceState.entries) {
            for (event in allEvents) {
                val starts = reduce(state, event).commands.any { it is StartTripSession }
                val allowed = state == PREVIEW && event is GuidanceEvent.StartRequested
                assertEquals(allowed, starts, "$state + $event")
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
