package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.nav.model.GuidanceState

/**
 * When the token of the active entry reaches the SDKs.
 *
 * Every SDK reads the token when it makes a request, so a token handed over in the middle of a trip
 * would put the rest of that trip session, its reroutes, traffic updates and tiles, on another
 * token. Settings does not let the driver switch while guidance runs; a change that arrives all the
 * same, as one that raced the start of a drive, waits here for the trip to end, so a trip session
 * runs on one token from its start to its end. Nothing here picks a token: it only decides when
 * the one the driver chose is handed over.
 *
 * A switch while routes are previewed closes the preview first. Its routes were asked for with the
 * token before; the next ones are asked for with the new token when the driver asks for them. The
 * switch itself asks for nothing.
 */
object TokenSwitch {

    sealed interface Step {
        /** The SDKs have the wanted token already. */
        data object Keep : Step

        /** Guidance runs: the SDKs keep the token they have until it ends. */
        data object Hold : Step

        /** Hand the wanted token to the SDKs, or tell the screens there is none; close the open preview first when [closePreview]. */
        data class Apply(val closePreview: Boolean) : Step
    }

    /**
     * The step for [wanted], the active entry's token or null when none is saved, while the SDKs
     * were last given [applied] (null when none was) and guidance is in [guidance]. [first] is the
     * first read after the app started, when nothing has been handed over yet and no screen shows a map.
     */
    fun step(first: Boolean, applied: String?, wanted: String?, guidance: GuidanceState): Step = when {
        first -> Step.Apply(closePreview = false)
        wanted == applied -> Step.Keep
        guidance == GuidanceState.ACTIVE -> Step.Hold
        else -> Step.Apply(closePreview = guidance == GuidanceState.PREVIEW)
    }
}
