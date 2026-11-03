package io.github.purplepalm.custommaps.nav.trip

import java.time.Instant
import java.time.YearMonth
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow

enum class TripKind {
    ACTIVE_GUIDANCE,
    FREE_DRIVE,
}

/** One trip session start, as the navigation SDK reported it. */
data class TripSessionStart(
    val startedAt: Instant,
    val kind: TripKind,
    /** A replayed drive; still billed as a trip. */
    val simulated: Boolean,
)

/** Persistent record of trip session starts. */
interface TripLogStore {
    val entries: Flow<List<TripSessionStart>>

    suspend fun record(start: TripSessionStart)
}

/**
 * Pure helpers for the trip log. The count is an estimate of what Mapbox bills: short sessions,
 * resumed sessions and route legs are billed differently, and the statistics page is the truth.
 */
object TripLog {

    const val MAX_ENTRIES = 500

    fun countInMonth(entries: List<TripSessionStart>, now: ZonedDateTime): Int {
        val month = YearMonth.from(now)
        return entries.count { YearMonth.from(it.startedAt.atZone(now.zone)) == month }
    }

    fun append(entries: List<TripSessionStart>, start: TripSessionStart): List<TripSessionStart> =
        (entries + start).takeLast(MAX_ENTRIES)

    /** One entry per line: epoch millis, kind, 1 if simulated. */
    fun encode(entries: List<TripSessionStart>): String = entries.joinToString("\n") { entry ->
        "${entry.startedAt.toEpochMilli()},${entry.kind.name},${if (entry.simulated) 1 else 0}"
    }

    /** Skips lines it cannot read instead of failing, so one bad entry never hides the rest. */
    fun decode(text: String): List<TripSessionStart> = text.lineSequence().mapNotNull(::decodeLine).toList()

    private fun decodeLine(line: String): TripSessionStart? {
        val parts = line.trim().split(',')
        if (parts.size != 3) return null
        val millis = parts[0].toLongOrNull() ?: return null
        val kind = TripKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        val simulated = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return TripSessionStart(Instant.ofEpochMilli(millis), kind, simulated)
    }
}
