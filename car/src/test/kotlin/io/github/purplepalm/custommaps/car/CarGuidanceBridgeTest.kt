package io.github.purplepalm.custommaps.car

import androidx.car.app.HostException
import io.github.purplepalm.custommaps.car.routing.CarRouting
import io.github.purplepalm.custommaps.car.routing.Drives
import io.github.purplepalm.custommaps.car.routing.Drives.towardsExit
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guidance bridge on a port that behaves as the library's NavigationManager does: a start needs the
 * callback, a trip needs navigation started, the callback cannot be cleared while navigating, and the
 * host's request to stop reaches the app later than the library acts on it.
 */
class CarGuidanceBridgeTest {

    /** The library's NavigationManager as far as the bridge sees it, with any call made to fail. */
    private class Port : NavigationManagerPort {
        val calls = mutableListOf<String>()
        val trips = mutableListOf<CarRouting>()
        var failing: Set<String> = emptySet()
        var stopCallback: (() -> Unit)? = null
        var navigating = false

        private fun call(name: String) {
            calls += name
            if (name in failing) throw HostException("the host failed $name")
        }

        override fun setCallback(onStop: () -> Unit) {
            call("setCallback")
            stopCallback = onStop
        }

        override fun clearCallback() {
            call("clearCallback")
            check(!navigating) { "Removing callback while navigating" }
            stopCallback = null
        }

        override fun navigationStarted() {
            call("navigationStarted")
            if (navigating) return
            checkNotNull(stopCallback) { "No callback has been set" }
            navigating = true
        }

        override fun navigationEnded() {
            call("navigationEnded")
            navigating = false
        }

        override fun updateTrip(routing: CarRouting) {
            call("updateTrip")
            check(navigating) { "Navigation is not started" }
            trips += routing
        }

        /** The host stops the app's navigation: the library at once, the app's callback later. */
        fun hostStops(): () -> Unit {
            navigating = false
            val posted = stopCallback
            return { posted?.invoke() }
        }
    }

    private class Lines : DebugLog {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }

        fun nav(event: String) = lines.filter { it.first == CarGuidanceBridge.NAV_LINE && it.second["event"] == event }.map { it.second }

        fun errors() = lines.filter { it.first == CarGuidanceBridge.NAV_LINE && "call" in it.second }.map { it.second }
    }

    /** A clock and a timer the test moves by hand. */
    private class Time {
        var now = 0L
        val timers = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val timer = (now + delay) to action
            timers += timer
            return { timers.remove(timer) }
        }

        fun advance(millis: Long) {
            now += millis
            val due = timers.filter { it.first <= now }
            timers.removeAll(due)
            due.forEach { it.second() }
        }
    }

    private val port = Port()
    private val log = Lines()
    private val time = Time()
    private var shownCount = 0
    private val bridge = CarGuidanceBridge(
        port = port,
        content = { progress -> CarRouting.of(progress, Drives.OFFICE, Drives.NOW, Drives.WORDS) },
        log = log,
        clock = { time.now },
        schedule = time::schedule,
        lifecycle = { "STARTED" },
        connection = { 2 },
        onShown = { shownCount += 1 },
    )

    private fun drive(vararg progress: RouteProgressSnapshot) {
        bridge.start()
        bridge.onGuidance(GuidanceState.ACTIVE)
        progress.forEach(bridge::onProgress)
    }

    @Test
    fun `the callback comes first, then a drive asks the host to show it`() {
        bridge.start()
        bridge.onGuidance(GuidanceState.IDLE)
        assertEquals(listOf("setCallback"), port.calls)
        bridge.onGuidance(GuidanceState.PREVIEW)
        assertEquals(listOf("setCallback"), port.calls)
        bridge.onGuidance(GuidanceState.ACTIVE)
        assertEquals(listOf("setCallback", "navigationStarted"), port.calls)
        assertTrue(bridge.navigating)
        assertEquals(CarGuidanceBridge.CAUSE_DRIVE, log.nav("navigationStarted").single()["cause"])
    }

    @Test
    fun `a session made during a drive shows it at once`() {
        drive(towardsExit(0.8))
        assertEquals(listOf("setCallback", "navigationStarted", "updateTrip"), port.calls)
        assertEquals("Exit 24: Oak Street · Office", bridge.shown?.current?.cue?.full)
        assertEquals(1, shownCount)
    }

    @Test
    fun `progress goes at most once a second, at once when the maneuver changes`() {
        drive(towardsExit(0.80))
        time.advance(400)
        bridge.onProgress(towardsExit(0.79))
        time.advance(450)
        bridge.onProgress(towardsExit(0.78))
        // Held back: one trip so far, one timer for the end of the second.
        assertEquals(1, port.trips.size)
        assertEquals(1, time.timers.size)
        time.advance(150)
        // At 1 s the newest one goes.
        assertEquals(2, port.trips.size)
        assertEquals(CarRouting.of(towardsExit(0.78), Drives.OFFICE, Drives.NOW, Drives.WORDS), port.trips.last())
        // A new step goes at once, however soon.
        time.advance(100)
        bridge.onProgress(towardsExit(0.3, step = "route/0/4"))
        assertEquals(3, port.trips.size)
        assertEquals(3, shownCount)
        // So does the Then chip coming.
        time.advance(100)
        bridge.onProgress(towardsExit(0.29, step = "route/0/4").copy(showThen = true))
        assertEquals(4, port.trips.size)
        assertNotNull(bridge.shown?.then)
        // Updates a second apart all go.
        for (second in 1..5) {
            time.advance(1_000)
            bridge.onProgress(towardsExit(0.29 - second * 0.01, step = "route/0/4").copy(showThen = true))
        }
        assertEquals(9, port.trips.size)
        assertTrue(time.timers.isEmpty())
    }

    @Test
    fun `a reroute shows the card loading at once and never ends navigation`() {
        drive(towardsExit(0.8))
        time.advance(200)
        bridge.onProgress(towardsExit(0.8).copy(offRoute = true))
        assertTrue(port.trips.last().loading)
        time.advance(200)
        bridge.onProgress(towardsExit(0.8, step = "route2/0/0").copy(pending = true))
        time.advance(700)
        bridge.onProgress(towardsExit(0.75, step = "route2/0/0"))
        assertFalse(port.trips.last().loading)
        assertFalse("navigationEnded" in port.calls)
        assertTrue(bridge.navigating)
    }

    @Test
    fun `End ends navigation, and the next drive starts afresh`() {
        drive(towardsExit(0.8))
        time.advance(300)
        bridge.onProgress(towardsExit(0.7))
        bridge.onGuidance(GuidanceState.IDLE)
        assertEquals("navigationEnded", port.calls.last())
        assertFalse(bridge.navigating)
        assertNull(bridge.shown)
        // The trip held back for the end of the second is gone with the drive.
        assertTrue(time.timers.isEmpty())
        val ended = log.nav("navigationEnded").single()
        assertEquals("IDLE", ended["cause"])
        assertEquals(1, ended["trips"])
        // Progress outside a drive shows nothing.
        bridge.onProgress(towardsExit(0.6))
        assertEquals(1, port.trips.size)
        bridge.onGuidance(GuidanceState.ACTIVE)
        bridge.onProgress(towardsExit(5.0, step = "new/0/0"))
        assertEquals(2, port.trips.size)
        assertEquals(2, log.nav("navigationStarted").size)
    }

    @Test
    fun `the arrival, and a start the navigator refused, end navigation too`() {
        drive(towardsExit(0.1))
        bridge.onGuidance(GuidanceState.ARRIVED)
        assertEquals("navigationEnded", port.calls.last())
        assertEquals("ARRIVED", log.nav("navigationEnded").single()["cause"])
        bridge.onGuidance(GuidanceState.PREVIEW)
        bridge.onGuidance(GuidanceState.ACTIVE)
        bridge.onGuidance(GuidanceState.PREVIEW)
        assertEquals(listOf("navigationStarted", "updateTrip", "navigationEnded", "navigationStarted", "navigationEnded"), port.calls.drop(1))
    }

    @Test
    fun `after the host's stop no trip goes, the card still follows, and the drive goes on`() {
        drive(towardsExit(0.8))
        port.hostStops()()
        assertFalse(bridge.navigating)
        val stop = log.nav("onStopNavigation").single()
        assertEquals("STARTED", stop["lifecycle"])
        assertEquals("projection", stop["connection"])
        assertEquals("ACTIVE", stop["guidance"])
        time.advance(2_000)
        bridge.onProgress(towardsExit(0.7))
        assertEquals(1, port.trips.size)
        assertEquals(2, shownCount)
        assertEquals(0.7, bridge.shown?.current?.distance?.value)
        assertFalse("navigationEnded" in port.calls)
    }

    @Test
    fun `opening the app on the car's screen again takes the car back, once`() {
        drive(towardsExit(0.8))
        port.hostStops()()
        bridge.takeBack(CarGuidanceBridge.CAUSE_INTENT)
        assertTrue(bridge.navigating)
        // The card's content goes to the host at once.
        assertEquals(listOf("navigationStarted", "updateTrip"), port.calls.takeLast(2))
        assertEquals(CarGuidanceBridge.CAUSE_INTENT, log.nav("navigationStarted").last()["cause"])
        // The start that follows the intent asks nothing more, and nor does a start outside a drive.
        bridge.takeBack(CarGuidanceBridge.CAUSE_START)
        bridge.onGuidance(GuidanceState.IDLE)
        bridge.takeBack(CarGuidanceBridge.CAUSE_START)
        assertEquals(2, port.calls.count { it == "navigationStarted" })
    }

    @Test
    fun `a trip between the host's stop and its callback is refused, caught and written down`() {
        drive(towardsExit(0.8))
        val posted = port.hostStops()
        time.advance(1_000)
        bridge.onProgress(towardsExit(0.7))
        val error = log.errors().single()
        assertEquals("updateTrip", error["call"])
        assertEquals(IllegalStateException::class.java.name, error["error"])
        posted()
        assertFalse(bridge.navigating)
        time.advance(1_000)
        bridge.onProgress(towardsExit(0.6))
        assertEquals(1, log.errors().size)
    }

    @Test
    fun `at the session's end the bridge is gone, and a callback posted before only writes its line`() {
        drive(towardsExit(0.8))
        val posted = port.hostStops()
        time.advance(300)
        bridge.onProgress(towardsExit(0.79))
        bridge.stop()
        assertEquals("clearCallback", port.calls.last())
        assertNull(port.stopCallback)
        assertTrue(time.timers.isEmpty())
        assertTrue(bridge.gone)
        // The library reads the callback when its post runs, so none comes after the end; should one come, it only writes its line.
        posted()
        assertEquals(true, log.nav("onStopNavigation").single()["gone"])
        val calls = port.calls.size
        bridge.onProgress(towardsExit(0.7))
        bridge.onGuidance(GuidanceState.IDLE)
        bridge.takeBack(CarGuidanceBridge.CAUSE_START)
        bridge.stop()
        assertEquals(calls, port.calls.size)
        assertEquals(1, log.nav("gone").size)
    }

    @Test
    fun `an exception from every call is caught and written with the call's name`() {
        for (name in listOf("setCallback", "navigationStarted", "updateTrip", "navigationEnded", "clearCallback")) {
            val port = Port().apply { failing = setOf(name) }
            val log = Lines()
            val bridge = CarGuidanceBridge(port, { CarRouting.of(it, Drives.OFFICE, Drives.NOW, Drives.WORDS) }, log, { 0L }, { _, _ -> {} }, { "CREATED" }, { null }, {})
            bridge.start()
            bridge.onGuidance(GuidanceState.ACTIVE)
            bridge.onProgress(towardsExit(0.8))
            bridge.onGuidance(GuidanceState.IDLE)
            bridge.stop()
            val errors = log.errors()
            assertTrue(errors.any { it["call"] == name && it["error"] == HostException::class.java.name }, "$name: $errors")
            assertTrue(bridge.gone, name)
            assertFalse(bridge.navigating, name)
        }
    }

    @Test
    fun `without the callback the host is never asked to show the drive`() {
        port.failing = setOf("setCallback")
        drive(towardsExit(0.8))
        assertFalse(bridge.navigating)
        assertTrue(port.trips.isEmpty())
        // The library's own refusal is caught and written down too.
        assertEquals("navigationStarted", log.errors().last()["call"])
        // The card still shows the drive.
        assertNotNull(bridge.shown)
    }

    @Test
    fun `a card that cannot be refreshed is written down and the trip still goes`() {
        val bridge = CarGuidanceBridge(port, { CarRouting.of(it, Drives.OFFICE, Drives.NOW, Drives.WORDS) }, log, { 0L }, time::schedule, { "STARTED" }, { 2 }, { error("no screen") })
        bridge.start()
        bridge.onGuidance(GuidanceState.ACTIVE)
        bridge.onProgress(towardsExit(0.8))
        assertEquals(1, port.trips.size)
        assertEquals("show", log.errors().single()["call"])
    }
}
