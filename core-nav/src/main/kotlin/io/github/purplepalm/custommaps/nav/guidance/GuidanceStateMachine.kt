package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.GuidanceState

enum class StopReason {
    USER_CANCELLED,
    CAR_DISCONNECTED,
    CAR_HOST_REQUEST,
}

sealed interface GuidanceEvent {
    data class RoutesReady(val count: Int) : GuidanceEvent

    data object PreviewCleared : GuidanceEvent

    data class StartRequested(val simulated: Boolean) : GuidanceEvent

    data object Arrived : GuidanceEvent

    data class Stopped(val reason: StopReason) : GuidanceEvent

    data object ArrivalDismissed : GuidanceEvent
}

/** Side effects the engine performs, in list order. */
sealed interface GuidanceCommand {
    /** Hands the previewed routes to the navigator. */
    data object CommitRoutes : GuidanceCommand

    data class StartTripSession(val simulated: Boolean) : GuidanceCommand

    data object StopTripSession : GuidanceCommand

    /** Clears the navigator's routes. Only ever after [StopTripSession], so no free drive is billed. */
    data object ClearRoutes : GuidanceCommand

    data object ClearPreview : GuidanceCommand
}

data class GuidanceTransition(val state: GuidanceState, val commands: List<GuidanceCommand>)

/**
 * Rules that keep Mapbox costs down: a trip session starts only when the driver starts guidance,
 * and it is always stopped before routes are cleared, because clearing routes while a session
 * runs switches it to (billed) free drive. Events that do not fit the current state are ignored.
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
            is GuidanceEvent.StartRequested -> GuidanceTransition(
                GuidanceState.ACTIVE,
                listOf(GuidanceCommand.CommitRoutes, GuidanceCommand.StartTripSession(event.simulated)),
            )
            else -> stay(state)
        }
        GuidanceState.ACTIVE -> when (event) {
            GuidanceEvent.Arrived -> GuidanceTransition(GuidanceState.ARRIVED, stopSequence)
            is GuidanceEvent.Stopped -> GuidanceTransition(GuidanceState.IDLE, stopSequence)
            else -> stay(state)
        }
    }

    private val stopSequence = listOf(
        GuidanceCommand.StopTripSession,
        GuidanceCommand.ClearRoutes,
        GuidanceCommand.ClearPreview,
    )

    private fun stay(state: GuidanceState) = GuidanceTransition(state, emptyList())
}
