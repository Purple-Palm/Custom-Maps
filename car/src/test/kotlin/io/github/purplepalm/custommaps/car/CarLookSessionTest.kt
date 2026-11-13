package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.look.LookScreen
import io.github.purplepalm.custommaps.nav.look.SharedLook
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car session's part in the app's one look, against a look and a car map that record what they
 * are asked. The session's work runs at once on the test's thread ([Dispatchers.Unconfined]), as it
 * runs on the main thread.
 */
class CarLookSessionTest {

    @Test
    fun `a session created with no screen before starts the first look and goes on once it is decided`() {
        val rig = Rig()
        var evaluated = 0
        rig.session.onCreate { evaluated += 1 }
        assertEquals(listOf("start"), rig.look.calls)
        assertEquals(false, rig.session.decided)
        assertEquals(0, evaluated)
        // The stored choice came and the first look is decided: the session looks at its map again, once.
        rig.look.decided.value = true
        assertEquals(1, evaluated)
        assertTrue(rig.session.decided)
        rig.look.lookOnMap.value = LookOnMap(Look.DAY)
        assertEquals(1, evaluated)
        assertEquals(LookOnMap(Look.DAY), rig.session.lookOnMap)
    }

    @Test
    fun `a look decided before the session needs no wait`() {
        val rig = Rig()
        rig.look.decided.value = true
        var evaluated = 0
        rig.session.onCreate { evaluated += 1 }
        // Started all the same, which does nothing once started; the session's own look at its map follows.
        assertEquals(listOf("start"), rig.look.calls)
        assertEquals(0, evaluated)
    }

    @Test
    fun `the look decides as the car's screen while the session is started`() {
        val rig = Rig()
        rig.session.onCreate {}
        assertEquals(0, rig.look.holders)
        rig.session.onStart()
        assertEquals(listOf(LookScreen.CAR), rig.look.held)
        assertEquals(1, rig.look.holders)
        // Started again without a stop in between: still one holder.
        rig.session.onStart()
        assertEquals(1, rig.look.holders)
        rig.session.onStop()
        assertEquals(0, rig.look.holders)
        rig.session.onStop()
        assertEquals(0, rig.look.holders)
    }

    @Test
    fun `the session's end lets go of the look with its scope`() {
        val rig = Rig()
        rig.session.onStart()
        assertEquals(1, rig.look.holders)
        rig.scope.cancel()
        assertEquals(0, rig.look.holders)
    }

    @Test
    fun `every look goes to the car's map, and its close view to the look as the car's`() {
        val rig = Rig()
        val map = FakeMap()
        val mapScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        rig.session.follow(map, mapScope)
        assertEquals(listOf(LookOnMap(Look.NIGHT)), map.looks)
        rig.look.lookOnMap.value = LookOnMap(Look.DAY, Look.NIGHT)
        assertEquals(listOf(LookOnMap(Look.NIGHT), LookOnMap(Look.DAY, Look.NIGHT)), map.looks)
        map.closeViewNow.value = true
        map.closeViewNow.value = false
        map.closeViewNow.value = true
        assertEquals(listOf(false to LookScreen.CAR, true to LookScreen.CAR, false to LookScreen.CAR, true to LookScreen.CAR), rig.look.closeViews)
        // The map goes: its close view holds nothing any more, and it takes no more looks.
        mapScope.cancel()
        assertEquals(false to LookScreen.CAR, rig.look.closeViews.last())
        rig.look.lookOnMap.value = LookOnMap(Look.NIGHT)
        assertEquals(2, map.looks.size)
    }

    @Test
    fun `what fails is reported, not thrown, and the map that failed still takes the next look`() {
        val rig = Rig()
        rig.look.failing = setOf("start", "runWhileStarted", "onCloseView")
        rig.session.onCreate {}
        rig.session.onStart()
        val map = FakeMap(failing = 1)
        rig.session.follow(map, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        // The look heard that the map had nothing to write on, and the next look reached the map.
        assertEquals(listOf(LookApplied(onMap = false)), rig.look.applied)
        rig.look.lookOnMap.value = LookOnMap(Look.DAY)
        assertEquals(listOf(LookOnMap(Look.DAY)), map.looks)
        assertEquals(listOf("lookStart", "lookWhileStarted", "setLook", "closeView"), rig.failures)
    }

    /** The session's part with its look, a scope on the test's thread and the failures it reported. */
    private class Rig {
        val look = FakeLook()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<String>()
        val session = CarLookSession(look, scope) { call, _ -> failures += call }
    }

    /** The app's look as the session sees it, with what it was asked; any call named in [failing] throws. */
    private class FakeLook : SharedLook {
        override val decided = MutableStateFlow(false)
        override val lookOnMap = MutableStateFlow(LookOnMap(Look.NIGHT))
        var failing: Set<String> = emptySet()
        val calls = mutableListOf<String>()
        val held = mutableListOf<LookScreen>()
        var holders = 0
        val applied = mutableListOf<LookApplied>()
        val closeViews = mutableListOf<Pair<Boolean, LookScreen>>()

        private fun call(name: String) {
            calls += name
            if (name in failing) throw IllegalStateException("the look failed $name")
        }

        override fun start() = call("start")

        override suspend fun runWhileStarted(screen: LookScreen) {
            call("runWhileStarted")
            held += screen
            holders += 1
            try {
                awaitCancellation()
            } finally {
                holders -= 1
            }
        }

        override suspend fun showOn(setLook: (request: LookOnMap, onSettled: (idleMs: Long?) -> Unit) -> LookApplied) {
            lookOnMap.collect { request -> applied += setLook(request) {} }
        }

        override fun onCloseView(closeView: Boolean, screen: LookScreen) {
            call("onCloseView")
            closeViews += closeView to screen
        }
    }

    /** The car's map as far as the look goes: the looks it took and its close view; its first [failing] looks throw. */
    private class FakeMap(private var failing: Int = 0) : CarMapRenderer {
        val looks = mutableListOf<LookOnMap>()
        val closeViewNow = MutableStateFlow(false)
        override val closeView: StateFlow<Boolean> = closeViewNow
        override val cameraMode: StateFlow<CameraMode> = MutableStateFlow(CameraMode.IDLE)

        override fun setLook(request: LookOnMap, onSettled: (idleMs: Long?) -> Unit): LookApplied {
            if (failing > 0) {
                failing -= 1
                throw IllegalStateException("the map failed")
            }
            looks += request
            return LookApplied()
        }

        override fun setCameraMode(mode: CameraMode) = Unit
        override fun setViewportPadding(padding: ViewportPadding) = Unit
        override fun setCameraStyle(style: CameraStyle) = Unit
        override fun setLaneZoom(mode: LaneZoom) = Unit
        override fun setLaneDetails(enabled: Boolean) = Unit
        override fun setOwnRouteLine(enabled: Boolean) = Unit
        override fun setDrawOwnLanes(enabled: Boolean) = Unit
        override fun showPlace(place: Place?) = Unit
        override fun zoomBy(delta: Double) = Unit
        override fun setFrameTarget(fps: Int) = Unit
        override fun release() = Unit
    }
}
