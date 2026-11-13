package io.github.purplepalm.custommaps.engine.mapbox.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's and the car's hold on the navigator, on fakes of the navigator's attach and detach. */
class NavigatorHostsTest {

    private class FakeLifecycle(override val currentState: State) : Lifecycle() {
        override fun addObserver(observer: LifecycleObserver) = Unit

        override fun removeObserver(observer: LifecycleObserver) = Unit
    }

    private class FakeOwner(state: Lifecycle.State) : LifecycleOwner {
        override val lifecycle: Lifecycle = FakeLifecycle(state)
    }

    private val calls = mutableListOf<Pair<String, LifecycleOwner>>()
    private val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var guidance = GuidanceState.IDLE
    private var attachFails = false

    private val hosts = NavigatorHosts(
        attach = { owner ->
            if (attachFails) throw IllegalStateException("not set up")
            calls += "attach" to owner
        },
        detach = { owner -> calls += "detach" to owner },
        debugLog = object : DebugLog {
            override fun log(type: String, fields: Map<String, Any?>) {
                lines += type to fields
            }
        },
        guidance = { guidance },
    )

    @Test
    fun `the phone attaches and detaches as before, with no line`() {
        val activity = FakeOwner(Lifecycle.State.STARTED)
        hosts.attachPhone(activity)
        hosts.detachPhone(activity)
        assertEquals(listOf("attach" to activity, "detach" to activity), calls)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a car session attaches and says so`() {
        val session = FakeOwner(Lifecycle.State.CREATED)
        guidance = GuidanceState.ACTIVE
        hosts.attachCar(session)
        assertEquals(listOf("attach" to session), calls)
        assertEquals(listOf("host_attached" to mapOf("kind" to "car", "state" to "CREATED", "guidance" to GuidanceState.ACTIVE)), lines)
    }

    @Test
    fun `a car session is never detached by the hosts`() {
        // There is no call for it: the navigator lets a destroyed lifecycle go by itself.
        val session = FakeOwner(Lifecycle.State.CREATED)
        hosts.attachCar(session)
        hosts.attachCar(FakeOwner(Lifecycle.State.DESTROYED))
        assertTrue(calls.none { it.first == "detach" })
        assertTrue(NavigatorHosts::class.java.declaredMethods.none { it.name.contains("detachCar", ignoreCase = true) })
    }

    @Test
    fun `a failed attach is a line, never an exception in the car's code`() {
        attachFails = true
        hosts.attachCar(FakeOwner(Lifecycle.State.CREATED))
        assertEquals(
            listOf("car_error" to mapOf("call" to "attachCarHost", "error" to "java.lang.IllegalStateException", "message" to "not set up")),
            lines,
        )
    }
}
