package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdleFollowTest {
    private val follow = IdleFollow()

    @Test
    fun `follows from the car's first position and starts only once`() {
        assertFalse(follow.following)
        assertTrue(follow.onCarLocated())
        assertTrue(follow.following)
        // The car's later positions, one for every frame it is drawn in, start nothing again.
        repeat(100) { assertFalse(follow.onCarLocated()) }
        assertTrue(follow.following)
    }

    @Test
    fun `a map the driver moved stays where it is`() {
        follow.onCarLocated()
        follow.onMapMoved()
        assertFalse(follow.following)
        assertFalse(follow.onCarLocated())
        assertFalse(follow.following)
    }

    @Test
    fun `My location brings the camera back to the car`() {
        follow.onCarLocated()
        follow.onMapMoved()
        assertTrue(follow.onMyLocation())
        assertTrue(follow.following)
        // The camera is on its way already, and the car's next positions keep it following.
        assertFalse(follow.onCarLocated())
        assertTrue(follow.following)
    }

    @Test
    fun `My location while following sends the camera to the car again`() {
        follow.onCarLocated()
        assertTrue(follow.onMyLocation())
        assertTrue(follow.following)
    }

    @Test
    fun `My location before the first position follows from the first position`() {
        assertFalse(follow.onMyLocation())
        assertFalse(follow.following)
        assertTrue(follow.onCarLocated())
        assertTrue(follow.following)
    }

    @Test
    fun `a map moved before the first position is left alone when the car appears`() {
        follow.onMapMoved()
        assertFalse(follow.onCarLocated())
        assertFalse(follow.following)
        assertTrue(follow.onMyLocation())
        assertFalse(follow.onCarLocated())
        assertTrue(follow.following)
    }

    @Test
    fun `a route on the map ends following`() {
        follow.onCarLocated()
        follow.onRouteShown()
        assertFalse(follow.following)
        assertFalse(follow.onCarLocated())
        // The screen with a route offers no My location; a stray one moves nothing.
        assertFalse(follow.onMyLocation())
        assertFalse(follow.following)
    }

    @Test
    fun `the camera follows again when the route goes and its camera still had the map`() {
        follow.onCarLocated()
        follow.onRouteShown()
        assertTrue(follow.onRouteGone(routeCameraHadMap = true))
        assertTrue(follow.following)
    }

    @Test
    fun `the map stays put when the route goes after the driver took the map`() {
        follow.onCarLocated()
        follow.onRouteShown()
        // A drag during guidance.
        follow.onMapMoved()
        assertFalse(follow.onRouteGone(routeCameraHadMap = false))
        assertFalse(follow.following)
        assertFalse(follow.onCarLocated())
    }

    @Test
    fun `a route that goes brings back a map the driver had moved before it came`() {
        follow.onCarLocated()
        follow.onMapMoved()
        follow.onRouteShown()
        // The preview is closed with its overview still on screen.
        assertTrue(follow.onRouteGone(routeCameraHadMap = true))
        assertTrue(follow.following)
    }

    @Test
    fun `no route, said again, changes nothing`() {
        // As at the start of the app, and whenever the screen without a route is drawn again.
        assertFalse(follow.onRouteGone(routeCameraHadMap = false))
        assertTrue(follow.onCarLocated())
        assertFalse(follow.onRouteGone(routeCameraHadMap = false))
        assertTrue(follow.following)
        follow.onMapMoved()
        assertFalse(follow.onRouteGone(routeCameraHadMap = true))
        assertFalse(follow.following)
    }

    @Test
    fun `a route that goes before the car has a position waits for the position`() {
        follow.onRouteShown()
        assertFalse(follow.onRouteGone(routeCameraHadMap = true))
        assertFalse(follow.following)
        assertTrue(follow.onCarLocated())
    }
}
