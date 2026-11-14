package io.github.purplepalm.custommaps.car

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the car's session tells the app's one player of tones of the car: connected at ON_CREATE, gone at ON_DESTROY once
 * the bridge has let go, and taken over or back as the bridge hears from the host. The session needs the car's host, so these
 * statements are pinned by its source; what the link makes of them is [CarSoundTest]'s, what the player does with that the
 * app's ToneDirectorTest's.
 */
class CarSoundWiringTest {

    /** The session's source with its comments taken out: what the compiler reads. */
    private val session = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarSession.kt").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    /** The statements of the block that opens with the first `{` at or after [start], one a line, trimmed, without blank lines. */
    private fun block(start: Int): List<String> {
        val open = session.indexOf('{', start)
        var depth = 0
        for (end in open until session.length) {
            when (session[end]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return session.substring(open + 1, end).lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        error("no end after $start")
    }

    /** The body of the function declared by [declaration], whose parameters hold no brace. */
    private fun body(declaration: String): List<String> {
        val start = session.indexOf(declaration)
        assertTrue(start >= 0, "no $declaration")
        return block(session.indexOf(')', start))
    }

    /** The statements of the lifecycle branch [event]. */
    private fun branch(event: String): List<String> {
        val start = session.indexOf("Lifecycle.Event.$event -> {")
        assertTrue(start >= 0, "no branch $event")
        return block(start)
    }

    @Test
    fun `ON_CREATE connects the session to the player, before anything of the drive can reach it`() {
        val created = branch("ON_CREATE")
        val connect = created.indexOf("guard(\"sound\") { parts.sound.onSession(this, connected = true) }")
        assertTrue(connect >= 0, "$created")
        // The bridge is set up first; the drive's state, whose start tells the player the app guides, only after.
        assertTrue(created.indexOf("guard(\"startGuidance\") { startGuidance() }") in 0 until connect)
        assertTrue(connect < created.indexOfFirst { it.startsWith("scope.launch") })
    }

    @Test
    fun `ON_DESTROY stops the session, which disconnects it from the player once the bridge has let go`() {
        assertTrue("stop()" in branch("ON_DESTROY"))
        val stop = body("private fun stop()")
        val bridge = stop.indexOf("guard(\"stopGuidance\") { bridge?.stop() }")
        val gone = stop.indexOf("guard(\"sound\") { parts.sound.onSession(this, connected = false) }")
        assertTrue(bridge >= 0 && gone > bridge, "$stop")
    }

    @Test
    fun `the bridge's word that another app guides, or that the app guides again, reaches the player for this session`() {
        val start = body("private fun startGuidance()")
        assertTrue("onYielded = { yielded -> parts.sound.onYielded(this, yielded) }," in start, "$start")
        assertTrue("bridge.start()" in start)
    }

    @Test
    fun `the session tells the player nothing else, and each of the three once`() {
        assertEquals(1, Regex("""parts\.sound\.onSession\(this, connected = true\)""").findAll(session).count())
        assertEquals(1, Regex("""parts\.sound\.onSession\(this, connected = false\)""").findAll(session).count())
        assertEquals(1, Regex("""parts\.sound\.onYielded\(this, yielded\)""").findAll(session).count())
        assertEquals(3, Regex("""parts\.sound\.""").findAll(session).count())
    }

    @Test
    fun `in the session's order, connect, another app's start and the disconnect leave the player as with no car`() {
        val told = mutableListOf<Pair<Boolean, Boolean>>()
        val link = CarSoundLink { connected, yielded -> told += connected to yielded }
        val session = Any()
        link.onSession(session, connected = true)
        link.onYielded(session, yielded = true)
        link.onSession(session, connected = false)
        assertEquals(listOf(true to false, true to true, false to false), told)
    }
}
