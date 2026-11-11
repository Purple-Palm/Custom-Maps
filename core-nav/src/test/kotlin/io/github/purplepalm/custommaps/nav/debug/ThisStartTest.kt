package io.github.purplepalm.custommaps.nav.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Invented records, newest first, as Android lists them. This process is pid 4321, asked for at
 * 120.0 s of uptime and running from 120.6 s.
 */
class ThisStartTest {

    private val pid = 4_321
    private val requested = 120_000L
    private val started = 120_600L

    private fun find(vararg records: StartRecord) = ThisStart.find(records.toList(), pid, requested, started)

    @Test
    fun `the record with this pid is this start`() {
        val result = find(
            StartRecord(pid = 4_321, cold = true, bindUptimeMillis = 120_400L),
            StartRecord(pid = 1_234, cold = true, bindUptimeMillis = 50_000L),
        )

        assertEquals(0 to StartMatch.PID, result)
    }

    @Test
    fun `a newest record of another process is passed over`() {
        // The case of the drive logs: the newest record was not this process's, and nothing was written.
        val result = find(
            StartRecord(pid = 1_234, cold = true, bindUptimeMillis = 50_000L),
            StartRecord(pid = 4_321, cold = true, bindUptimeMillis = 120_400L),
        )

        assertEquals(1 to StartMatch.PID, result)
    }

    @Test
    fun `a record noted before the process had a pid is told by when the app was bound`() {
        val result = find(
            StartRecord(pid = 0, cold = true, bindUptimeMillis = 120_400L),
            StartRecord(pid = 0, cold = true, bindUptimeMillis = 50_000L),
        )

        assertEquals(0 to StartMatch.BIND_TIME, result)
    }

    @Test
    fun `the bind time may lie a second outside the start, and no more`() {
        assertEquals(1_000L, ThisStart.SLACK_MILLIS)
        assertEquals(0 to StartMatch.BIND_TIME, find(StartRecord(0, true, 119_000L)))
        assertEquals(0 to StartMatch.BIND_TIME, find(StartRecord(0, true, 121_600L)))
        assertNull(find(StartRecord(0, true, 118_999L)))
        assertNull(find(StartRecord(0, true, 121_601L)))
    }

    @Test
    fun `an earlier process's record without a pid is not this start`() {
        // Its app was bound long before this process was asked for.
        assertNull(find(StartRecord(pid = 0, cold = true, bindUptimeMillis = 50_000L)))
        // And a record without any time says nothing.
        assertNull(find(StartRecord(pid = 0, cold = true, bindUptimeMillis = null)))
    }

    @Test
    fun `a later launch within this process is no start of it`() {
        val result = find(
            StartRecord(pid = 4_321, cold = false, bindUptimeMillis = null),
            StartRecord(pid = 0, cold = true, bindUptimeMillis = 120_300L),
        )

        assertEquals(1 to StartMatch.BIND_TIME, result)
    }

    @Test
    fun `no records, no start`() {
        assertNull(ThisStart.find(emptyList(), pid, requested, started))
    }

    @Test
    fun `a completed start is this one when it is a new process with this pid or none`() {
        assertTrue(ThisStart.isCompletedStart(StartRecord(pid = 0, cold = true, bindUptimeMillis = null), pid))
        assertTrue(ThisStart.isCompletedStart(StartRecord(pid = 4_321, cold = true, bindUptimeMillis = null), pid))
        assertFalse(ThisStart.isCompletedStart(StartRecord(pid = 4_321, cold = false, bindUptimeMillis = null), pid))
        assertFalse(ThisStart.isCompletedStart(StartRecord(pid = 1_234, cold = true, bindUptimeMillis = null), pid))
    }
}
