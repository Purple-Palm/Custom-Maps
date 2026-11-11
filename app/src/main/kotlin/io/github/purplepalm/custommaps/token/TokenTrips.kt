package io.github.purplepalm.custommaps.token

/** Whose trip sessions a count is: a saved token's, those of tokens deleted since, or those from before tokens had ids. */
sealed interface TripsOf {
    /** The saved token named [name]. */
    data class Token(val name: String) : TripsOf

    /**
     * A token that is no longer saved on this phone: deleted, or never on it, as after the trip
     * log came back from a backup, which never holds the tokens. Its sessions keep counting, all
     * of them together.
     */
    data object Deleted : TripsOf

    /** Sessions logged before the app kept several tokens; they have no token id. */
    data object BeforeNames : TripsOf
}

/** One line of the month's trip sessions by token. */
data class TokenTrips(val of: TripsOf, val count: Int)

/** The trip session counts of Settings, Debug, by the token they ran on. Names only: never a token. */
object TokenTripCounts {

    /**
     * The month's sessions, [counts] by token id as [io.github.purplepalm.custommaps.nav.trip.TripLog.countByToken]
     * gives them: one line for each saved token of [list], in its order and with none as 0, then
     * the sessions of tokens deleted since, then those from before tokens had ids, each of these
     * two only when there are some. The lines add up to the month's count.
     */
    fun rows(counts: Map<String?, Int>, list: TokenList): List<TokenTrips> {
        val saved = list.entries.map { TokenTrips(TripsOf.Token(it.name), counts[it.id] ?: 0) }
        val deleted = counts.filterKeys { id -> id != null && list.slotOf(id) == null }.values.sum()
        val beforeNames = counts[null] ?: 0
        return saved +
            listOfNotNull(
                TokenTrips(TripsOf.Deleted, deleted).takeIf { deleted > 0 },
                TokenTrips(TripsOf.BeforeNames, beforeNames).takeIf { beforeNames > 0 },
            )
    }

    /** Whose one session with [tokenId] was, for the list of recent ones. */
    fun of(tokenId: String?, list: TokenList): TripsOf = when {
        tokenId == null -> TripsOf.BeforeNames
        else -> list.entries.firstOrNull { it.id == tokenId }?.let { TripsOf.Token(it.name) } ?: TripsOf.Deleted
    }
}
