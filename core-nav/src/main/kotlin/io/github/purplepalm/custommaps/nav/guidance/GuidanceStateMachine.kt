package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.GuidanceState

enum class StopReason {
    USER_CANCELLED,
    CAR_DISCONNECTED,
    CAR_HOST_REQUEST,

    /** The trip session was stopped outside the app, from the End Navigation button of its notification. */
    STOPPED_OUTSIDE_APP,
}

sealed interface GuidanceEvent {
    data class RoutesReady(val count: Int) : GuidanceEvent

    data object PreviewCleared : GuidanceEvent

    data class StartRequested(val simulated: Boolean) : GuidanceEvent

    /** The navigator took the routes handed over by [GuidanceCommand.CommitRoutes]. */
    data class RoutesCommitted(val simulated: Boolean) : GuidanceEvent

    /** The navigator rejected the routes handed over by [GuidanceCommand.CommitRoutes]. */
    data object CommitFailed : GuidanceEvent

    data object Arrived : GuidanceEvent

    data class Stopped(val reason: StopReason) : GuidanceEvent

    data object ArrivalDismissed : GuidanceEvent
}

/** Side effects the engine performs, in list order. */
sealed interface GuidanceCommand {
    /**
     * Hands the previewed routes to the navigator. The engine reports [GuidanceEvent.RoutesCommitted]
     * (or [GuidanceEvent.CommitFailed]) once the navigator has them.
     */
    data class CommitRoutes(val simulated: Boolean) : GuidanceCommand

    data class StartTripSession(val simulated: Boolean) : GuidanceCommand

    data object StopTripSession : GuidanceCommand

    /** Clears the navigator's routes. Only ever after [StopTripSession], so no free drive is billed. */
    data object ClearRoutes : GuidanceCommand

    data object ClearPreview : GuidanceCommand
}

data class GuidanceTransition(val state: GuidanceState, val commands: List<GuidanceCommand>)

/**
 * Rules that keep Mapbox costs down: a trip session starts only when the driver starts guidance,
 * and only once the navigator has the routes, because a session that starts without routes begins
 * as (billed) free drive. It is always stopped before routes are cleared, for the same reason.
 * Events that do not fit the current state are ignored.
 */
object GuidanceStateMachine {

    fun reduce(state: GuidanceState, event: GuidanceEvent): GuidanceTransition = when (state) {
        GuidanceState.IDLE, GuidanceState.ARRIVED -> when (event) {
            is GuidanceEvent.RoutesReady ->
                if (event.count > 0) GuidanceTransition(GuidanceState.PREVIEW, emptyList()) else stay(state)
            GuidanceEvent.ArrivalDismissed ->
                if (state == GuidanceState.ARRIVED) GuidanceTransition(GuidanceState.IDLE, emptyList()) else stay(state)
            else -> stay(state)
        }
        GuidanceState.PREVIEW -> when (event) {
            is GuidanceEvent.RoutesReady ->
                if (event.count > 0) stay(state) else GuidanceTransition(GuidanceState.IDLE, listOf(GuidanceCommand.ClearPreview))
            GuidanceEvent.PreviewCleared -> GuidanceTransition(GuidanceState.IDLE, listOf(GuidanceCommand.ClearPreview))
            is GuidanceEvent.StartRequested ->
                GuidanceTransition(GuidanceState.ACTIVE, listOf(GuidanceCommand.CommitRoutes(event.simulated)))
            else -> stay(state)
        }
        GuidanceState.ACTIVE -> when (event) {
            is GuidanceEvent.RoutesCommitted ->
                GuidanceTransition(GuidanceState.ACTIVE, listOf(GuidanceCommand.StartTripSession(event.simulated)))
            // No session has started; back to the routes so the driver can try again.
            GuidanceEvent.CommitFailed -> GuidanceTransition(
                GuidanceState.PREVIEW,
                listOf(GuidanceCommand.StopTripSession, GuidanceCommand.ClearRoutes),
            )
            GuidanceEvent.Arrived -> GuidanceTransition(GuidanceState.ARRIVED, stopSequence)
            is GuidanceEvent.Stopped -> GuidanceTransition(GuidanceState.IDLE, stopSequence)
            else -> stay(state)
        }
    }

    /**
     * Whether going from [from] to [to] ends a drive: End, in the app, in the trip notification or
     * from the car, or the arrival. A start whose routes the navigator refused goes back to the
     * preview and ends nothing, since no trip had begun.
     */
    fun endsDrive(from: GuidanceState, to: GuidanceState): Boolean =
        from == GuidanceState.ACTIVE && (to == GuidanceState.IDLE || to == GuidanceState.ARRIVED)

    private val stopSequence = listOf(
        GuidanceCommand.StopTripSession,
        GuidanceCommand.ClearRoutes,
        GuidanceCommand.ClearPreview,
    )

    private fun stay(state: GuidanceState) = GuidanceTransition(state, emptyList())
}
