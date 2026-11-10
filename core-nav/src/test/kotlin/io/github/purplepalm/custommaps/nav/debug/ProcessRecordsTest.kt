package io.github.purplepalm.custommaps.nav.debug

import java.io.File
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProcessRecordsTest {

    @TempDir
    lateinit var temp: File

    /** Exit times are epoch milliseconds; these are seconds after midnight on 2000-01-01 UTC. */
    private val midnight = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
    private fun at(seconds: Int) = midnight + seconds * 1_000L

    /** Stands in for Android's exit record; the pids are made up. */
    private data class Exit(val pid: Int, val diedAt: Long)

    private fun exit(seconds: Int, pid: Int = 4_200 + seconds) = Exit(pid, at(seconds))
    private val key = { exit: Exit -> ExitKey(exit.pid, exit.diedAt) }
    private fun keys(vararg exits: Exit) = exits.map(key)

    @Test
    fun `records are logged once each, oldest first`() {
        val logged = LoggedExits(File(temp, "state/exits-logged"))
        // Android lists exits newest first.
        val exits = listOf(exit(3), exit(2), exit(1))

        assertEquals(listOf(exit(1), exit(2), exit(3)), logged.unseen(exits, key))
        logged.markLogged(exits.map(key))

        assertEquals(listOf(exit(4)), logged.unseen(listOf(exit(4), exit(3), exit(2)), key))
    }

    @Test
    fun `after a restart or an app update the records already logged stay logged`() {
        val file = File(temp, "exits-logged")
        LoggedExits(file).markLogged(keys(exit(3), exit(2), exit(1)))

        // Android keeps its records across app updates; the next process sees the same ones again.
        val afterUpdate = LoggedExits(file)

        assertEquals(emptyList<Exit>(), afterUpdate.unseen(listOf(exit(3), exit(2), exit(1)), key))
        assertEquals(listOf(exit(5)), afterUpdate.unseen(listOf(exit(5), exit(3), exit(1)), key))
    }

    @Test
    fun `a record older than one already logged is still new`() {
        val logged = LoggedExits(File(temp, "exits-logged"))
        logged.markLogged(keys(exit(10)))

        // The phone's clock moved back before this process died.
        val afterClockChange = exit(5, pid = 4_305)

        assertEquals(listOf(afterClockChange), logged.unseen(listOf(exit(10), afterClockChange), key))
    }

    @Test
    fun `a pid that comes back is a new record`() {
        val logged = LoggedExits(File(temp, "exits-logged"))
        logged.markLogged(keys(exit(1, pid = 4_242)))

        assertEquals(listOf(exit(2, pid = 4_242)), logged.unseen(listOf(exit(2, pid = 4_242), exit(1, pid = 4_242)), key))
    }

    @Test
    fun `the file lists the records Android still holds, one pid and time per line`() {
        val file = File(temp, "exits-logged")
        val logged = LoggedExits(file)
        logged.markLogged(keys(exit(2), exit(1)))

        // The oldest record dropped out of Android's list.
        logged.markLogged(keys(exit(3), exit(2)))

        assertEquals(setOf("4203:${at(3)}", "4202:${at(2)}"), file.readLines().toSet())
        assertEquals(listOf(file.name), temp.list().orEmpty().toList())
        assertEquals(listOf(exit(1)), logged.unseen(listOf(exit(1)), key))
    }

    @Test
    fun `an empty list from Android keeps what is stored`() {
        val file = File(temp, "exits-logged")
        val logged = LoggedExits(file)
        logged.markLogged(keys(exit(1)))

        // Right after a reboot Android lists nothing until it has read its records back.
        logged.markLogged(emptyList())

        assertEquals(emptyList<Exit>(), logged.unseen(listOf(exit(1)), key))
    }

    @Test
    fun `a write that fails keeps the previous list`() {
        val file = File(temp, "exits-logged")
        LoggedExits(file).markLogged(keys(exit(3)))
        // The temporary file cannot be opened, so the new list is never written.
        File(temp, "exits-logged.new").mkdirs()

        LoggedExits(file).markLogged(keys(exit(9), exit(3)))

        assertEquals(listOf("4203:${at(3)}"), file.readLines())
        assertEquals(listOf(exit(9)), LoggedExits(file).unseen(listOf(exit(9), exit(3)), key))
    }

    @Test
    fun `a half-written temporary file from a dead process is ignored and replaced`() {
        val file = File(temp, "exits-logged")
        LoggedExits(file).markLogged(keys(exit(3)))
        // The process died while writing the next list: only the temporary file is half written.
        File(temp, "exits-logged.new").writeText("4209:${at(9)}".take(6))

        val afterRestart = LoggedExits(file)

        assertEquals(listOf(exit(4)), afterRestart.unseen(listOf(exit(4), exit(3)), key))
        afterRestart.markLogged(keys(exit(4), exit(3)))
        assertEquals(setOf("4204:${at(4)}", "4203:${at(3)}"), file.readLines().toSet())
        assertEquals(listOf(file.name), temp.list().orEmpty().toList())
    }

    @Test
    fun `lines that are not keys are skipped and their records logged again`() {
        val file = File(temp, "exits-logged").apply { writeText("not a key\n4201:${at(1)}\n4202\n") }

        assertEquals(listOf(exit(2)), LoggedExits(file).unseen(listOf(exit(2), exit(1)), key))
    }

    @Test
    fun `a key reads back from its text`() {
        val exitKey = ExitKey(4_242, at(7))

        assertEquals(exitKey, ExitKey.parse(exitKey.toString()))
        assertNull(ExitKey.parse("4242"))
        assertNull(ExitKey.parse("pid:${at(7)}"))
        assertNull(ExitKey.parse(at(7).toString()))
    }

    @Test
    fun `an ANR trace keeps the subject and the main thread's stack`() {
        val trace = """
            Subject: Input dispatching timed out (Waited 5001ms for FocusEvent(hasFocus=false))
            RssHwmKb: 512000

            ----- pid 4242 at 2000-01-01 00:00:00.000 +0000 -----
            Cmd line: io.github.purplepalm.custommaps.debug

            "Signal Catcher" daemon prio=10 tid=2 Runnable
              | group="system" sCount=0 ucsCount=0 flags=0 obj=0x10000000 self=0xb400
              at dalvik.system.VMStack.getThreadStackTrace(Native method)

            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x20000000 self=0xb400007
              | state=S schedstat=( 912 1 2 ) utm=82 stm=9 core=4 HZ=100
              native: #00 pc 00000000  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
              native: #01 pc 0001     /apex/libart.so (art::ConditionVariable::WaitHoldingLocks+136)
              native: #02 pc 0002     /apex/libart.so
              native: #03 pc 0003     /apex/libart.so
              native: #04 pc 0004     /apex/libart.so
              native: #05 pc 0005     /apex/libart.so
              native: #06 pc 0006     /apex/libart.so
              native: #07 pc 0007     /apex/libart.so
              native: #08 pc 0008     /apex/libart.so
              native: #09 pc 0009     /apex/libart.so
              at io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap.render(PhoneMap.kt:290)
              - waiting to lock <0x0a0a0a0a> (a java.lang.Object) held by thread 23
              at io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap.access${'$'}render(PhoneMap.kt:72)

            "DefaultDispatcher-worker-1" daemon prio=5 tid=23 Runnable
              at io.github.purplepalm.custommaps.Other.run(Other.kt:1)
        """.trimIndent()

        val summary = AnrTrace.summarize(trace.lineSequence())

        assertEquals("Input dispatching timed out (Waited 5001ms for FocusEvent(hasFocus=false))", summary["subject"])
        @Suppress("UNCHECKED_CAST")
        val stack = summary["mainThread"] as List<String>
        assertEquals("\"main\" prio=5 tid=1 Blocked", stack.first())
        assertEquals(1 + 8 + 3, stack.size)
        assertEquals("- waiting to lock <0x0a0a0a0a> (a java.lang.Object) held by thread 23", stack[10])
    }

    @Test
    fun `a trace without a main thread gives an empty stack`() {
        val summary = AnrTrace.summarize(sequenceOf("\"Binder:1\" prio=5 tid=9 Native", "  at x.y(Native method)"))

        assertNull(summary["subject"])
        assertEquals(emptyList<String>(), summary["mainThread"])
    }

    @Test
    fun `the main thread's stack is capped`() {
        val lines = sequenceOf("\"main\" prio=5 tid=1 Runnable") + generateSequence(0) { it + 1 }.map { "  at a.B.c$it(B.kt:$it)" }

        @Suppress("UNCHECKED_CAST")
        val stack = AnrTrace.summarize(lines, maxStackLines = 5)["mainThread"] as List<String>

        assertEquals(5, stack.size)
    }
}
