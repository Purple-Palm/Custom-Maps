package io.github.purplepalm.custommaps.nav.trip

import java.time.Instant
import java.time.LocalDate
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
    /**
     * The app's own id of the saved token the session ran on, never the token itself; null for the
     * sessions logged before the app kept several tokens.
     */
    val tokenId: String? = null,
)

/** Persistent record of trip session starts. */
interface TripLogStore {
    val entries: Flow<List<TripSessionStart>>

    suspend fun record(start: TripSessionStart)
}

/** Why a count of the month's trips may miss some: see [MonthCount.partialSince]. */
enum class LogBegan {

    /** The log began when the app was installed: it starts empty after every installation. */
    INSTALLATION,

    /**
     * The log starts with the oldest trip it keeps: it is full, and older trips were dropped, or
     * it came back from a backup made before this installation.
     */
    OLDEST_KEPT,
}

/**
 * The trip sessions of the current month in the log, [count]. When the log began after the month
 * did, [partialSince] is the day it began, the first day the count covers, and [began] says why;
 * the count is then only of the trips since that day, and a limit for the month must not read it
 * as the whole month. Both are null when the log covers the whole month.
 */
data class MonthCount(val count: Int, val partialSince: LocalDate? = null, val began: LogBegan? = null)

/**
 * Pure helpers for the trip log. The count is an estimate of what Mapbox bills: short sessions,
 * resumed sessions and route legs are billed differently, and the statistics page is the truth.
 */
object TripLog {

    const val MAX_ENTRIES = 500

    fun countInMonth(entries: List<TripSessionStart>, now: ZonedDateTime): Int = entries.count { inMonth(it, now) }

    /**
     * The trip sessions of the month of [now], in its time zone, for each token id they ran on;
     * the key null holds the sessions logged without one. Ids with no session this month are left
     * out. The counts add up to [countInMonth].
     */
    fun countByToken(entries: List<TripSessionStart>, now: ZonedDateTime): Map<String?, Int> =
        entries.filter { inMonth(it, now) }.groupingBy { it.tokenId }.eachCount()

    private fun inMonth(entry: TripSessionStart, now: ZonedDateTime): Boolean =
        YearMonth.from(entry.startedAt.atZone(now.zone)) == YearMonth.from(now)

    /**
     * The trips of the month of [now], in its time zone, and since when the log covers it.
     *
     * The log starts empty at each installation of the app, [installedAt], and keeps the newest
     * [MAX_ENTRIES] trips. It began at the installation, unless it holds trips from before it, as
     * from a restored backup, or is full: then it begins with its oldest trip, as far as anyone can
     * tell. When it began after the month's first moment, the count is partial since that day.
     */
    fun monthCount(entries: List<TripSessionStart>, now: ZonedDateTime, installedAt: Instant): MonthCount {
        val count = countInMonth(entries, now)
        val oldest = entries.minOfOrNull { it.startedAt }
        val (began, why) = when {
            oldest != null && (entries.size >= MAX_ENTRIES || oldest < installedAt) -> oldest to LogBegan.OLDEST_KEPT
            else -> installedAt to LogBegan.INSTALLATION
        }
        val monthStart = YearMonth.from(now).atDay(1).atStartOfDay(now.zone).toInstant()
        return if (began > monthStart) MonthCount(count, began.atZone(now.zone).toLocalDate(), why) else MonthCount(count)
    }

    fun append(entries: List<TripSessionStart>, start: TripSessionStart): List<TripSessionStart> =
        (entries + start).takeLast(MAX_ENTRIES)

    /**
     * One entry per line: epoch millis, kind, 1 if simulated, and the token id when there is one.
     * A line without a token id has the three fields that versions before token ids wrote and read.
     */
    fun encode(entries: List<TripSessionStart>): String = entries.joinToString("\n") { entry ->
        val line = "${entry.startedAt.toEpochMilli()},${entry.kind.name},${if (entry.simulated) 1 else 0}"
        if (entry.tokenId == null) line else "$line,${entry.tokenId}"
    }

    /**
     * Reads lines of three fields, as every version wrote them before token ids, and of four.
     * Skips lines it cannot read instead of failing, so one bad entry never hides the rest.
     */
    fun decode(text: String): List<TripSessionStart> = text.lineSequence().mapNotNull(::decodeLine).toList()

    /** Whether [text] can be a token id in a line: 1 to 32 letters, digits, '_' and '-'. */
    fun isTokenId(text: String): Boolean = TOKEN_ID.matches(text)

    private val TOKEN_ID = Regex("[A-Za-z0-9_-]{1,32}")

    private fun decodeLine(line: String): TripSessionStart? {
        val parts = line.trim().split(',')
        if (parts.size != 3 && parts.size != 4) return null
        val millis = parts[0].toLongOrNull() ?: return null
        val kind = TripKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        val simulated = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val tokenId = parts.getOrNull(3)?.also { if (!isTokenId(it)) return null }
        return TripSessionStart(Instant.ofEpochMilli(millis), kind, simulated, tokenId)
    }
}
