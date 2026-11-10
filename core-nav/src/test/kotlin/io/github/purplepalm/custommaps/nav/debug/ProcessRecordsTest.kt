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

    @Test
    fun `records are logged once each, oldest first`() {
        val loggedUpTo = LoggedUpTo(File(temp, "state/exits-logged"))
        // Android lists exits newest first.
        val exits = listOf(at(3), at(2), at(1))

        assertEquals(listOf(at(1), at(2), at(3)), loggedUpTo.unseen(exits) { it })
        loggedUpTo.markLogged(at(3))

        assertEquals(listOf(at(4)), loggedUpTo.unseen(listOf(at(4), at(3), at(2))) { it })
    }

    @Test
    fun `after a restart or an app update only records after the mark are new`() {
        val file = File(temp, "exits-logged")
        LoggedUpTo(file).markLogged(at(3))

        // Android keeps its records across app updates; the next process sees the same ones again.
        val afterUpdate = LoggedUpTo(file)

        assertEquals(emptyList<Long>(), afterUpdate.unseen(listOf(at(3), at(2), at(1))) { it })
        assertEquals(listOf(at(5)), afterUpdate.unseen(listOf(at(5), at(3), at(1))) { it })
    }

    @Test
    fun `an older mark never replaces a newer one`() {
        val loggedUpTo = LoggedUpTo(File(temp, "exits-logged"))
        loggedUpTo.markLogged(at(5))
        loggedUpTo.markLogged(at(4))

        assertEquals(emptyList<Long>(), loggedUpTo.unseen(listOf(at(5))) { it })
    }

    @Test
    fun `the mark is the time as decimal text`() {
        val file = File(temp, "exits-logged")

        LoggedUpTo(file).markLogged(at(7))

        assertEquals(at(7).toString(), file.readText())
        assertEquals(listOf(file.name), temp.list().orEmpty().toList())
    }

    @Test
    fun `a write cut short keeps the previous mark`() {
        val file = File(temp, "exits-logged")
        LoggedUpTo(file).markLogged(at(3))
        // The process died while writing the next mark: only the temporary file is half written.
        File(temp, "exits-logged.new").writeText(at(9).toString().take(4))

        val afterRestart = LoggedUpTo(file)

        assertEquals(listOf(at(4)), afterRestart.unseen(listOf(at(4), at(3))) { it })
        afterRestart.markLogged(at(4))
        assertEquals(at(4).toString(), file.readText())
    }

    @Test
    fun `an unreadable mark starts over`() {
        val file = File(temp, "exits-logged").apply { writeText("not a number") }

        assertEquals(listOf(at(1)), LoggedUpTo(file).unseen(listOf(at(1))) { it })
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
