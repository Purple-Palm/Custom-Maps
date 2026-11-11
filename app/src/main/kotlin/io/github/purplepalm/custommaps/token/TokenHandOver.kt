package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.nav.model.GuidanceState

/**
 * Which saved token the SDKs have, and a change that waits for the trip to end. AppGraph keeps one
 * for the process, gives it every read of the list with guidance's state, and carries out each
 * [Effect] on the main thread. [TokenSwitch] decides when a token is handed over; this remembers
 * what was handed over and what waits, so a trip session is counted under the token it ran on.
 *
 * Nothing here picks a token: the active entry is the driver's choice, handed over at once or when
 * the trip ends.
 */
class TokenHandOver {

    /** What AppGraph does with one read of the list. */
    sealed interface Effect {
        /** The SDKs have the active entry's token already, under this name or another. */
        data object Keep : Effect

        /**
         * Guidance runs: the SDKs keep their token until it ends, and [entry], the active one or
         * null when none is saved, waits. [log] the first time it waits, and again when another
         * token waits instead; not when the one waiting is only renamed.
         */
        data class Hold(val entry: TokenEntry?, val log: Boolean) : Effect

        /**
         * Hand [entry]'s token to the SDKs, or tell the screens there is none when null; close the
         * open preview first when [closePreview].
         */
        data class Apply(val entry: TokenEntry?, val closePreview: Boolean) : Effect
    }

    /** The entry whose token the SDKs were last given, under its latest name; null while none was. */
    @Volatile
    private var applied: TokenEntry? = null

    /** Nothing was read yet: the first read after the app started hands over whatever is saved. */
    private var first = true

    /** A change waits for the trip to end, with [heldToken], null when it leaves no token at all. */
    private var holding = false
    private var heldToken: String? = null

    /**
     * The id of the saved token the SDKs have, null while they have none: each trip session the
     * engine logs is counted under it. Read on any thread.
     */
    val appliedTokenId: String? get() = applied?.id

    /**
     * What to do with [list], as just read, while guidance is in [guidance]. Every entry of a list
     * holds a valid public token, which its constructor checks.
     */
    fun next(list: TokenList, guidance: GuidanceState): Effect {
        val wanted = list.active
        val step = TokenSwitch.step(first, applied?.token, wanted?.token, guidance)
        first = false
        return when (step) {
            // A rename, or the same token saved again under another entry: its trips count under that one.
            TokenSwitch.Step.Keep -> {
                applied = wanted
                holding = false
                Effect.Keep
            }
            TokenSwitch.Step.Hold -> {
                val log = !holding || heldToken != wanted?.token
                holding = true
                heldToken = wanted?.token
                Effect.Hold(wanted, log)
            }
            is TokenSwitch.Step.Apply -> {
                applied = wanted
                holding = false
                Effect.Apply(wanted, step.closePreview)
            }
        }
    }
}
