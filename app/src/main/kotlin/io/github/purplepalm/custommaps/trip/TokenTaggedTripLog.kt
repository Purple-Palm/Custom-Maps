package io.github.purplepalm.custommaps.trip

import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart

/**
 * The trip session log as the engine writes it: each start it records gets [tokenId], the id of the
 * saved token the SDKs had when the session began. The id is the app's own; the token itself never
 * reaches the log, which is backed up. A start that names a token already keeps it.
 */
class TokenTaggedTripLog(private val log: TripLogStore, private val tokenId: () -> String?) : TripLogStore {

    override val entries = log.entries

    override suspend fun record(start: TripSessionStart) {
        log.record(if (start.tokenId != null) start else start.copy(tokenId = tokenId()))
    }
}
