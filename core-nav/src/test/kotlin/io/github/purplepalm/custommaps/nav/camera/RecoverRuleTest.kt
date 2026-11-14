package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a cancelled move of the Mapbox camera styles is asked for again: a made-up camera with its request serial, raised by
 * every request and every map taken, and the SDK's state, as the posted check finds them.
 */
class RecoverRuleTest {

    /** The camera as the check sees it: its serial, and the move the SDK names as on its way, if any. */
    private class Camera {
        var serial = 0L
        var onItsWay: String? = null
        val asked = ArrayList<String>()

        fun request(move: String): Long {
            serial += 1
            onItsWay = move
            return serial
        }

        /** A gesture or the compass took the map, or the map was sent idle: the SDK goes idle. */
        fun taken() {
            serial += 1
            onItsWay = null
        }

        /** The posted check of a move asked for at [at] that ended cancelled. */
        fun check(move: String, at: Long, recovered: Boolean): Boolean {
            val ask = RecoverRule.shouldAsk(at, serial, stillInThatTransition = onItsWay == move, alreadyRecovered = recovered)
            if (ask) asked += move
            return ask
        }
    }

    @Test
    fun `a move replaced by a newer request is not asked again`() {
        val camera = Camera()
        val following = camera.request("following")
        // The overview replaces it; the SDK tells the following move it ended cancelled, from inside this request.
        camera.request("overview")
        assertFalse(camera.check("following", following, recovered = false))
        // Also when the newer request asks for the same move again.
        val first = camera.request("following")
        camera.request("following")
        assertFalse(camera.check("following", first, recovered = false))
        assertTrue(camera.asked.isEmpty())
    }

    @Test
    fun `a move ended by a request to go idle or by a drag is not asked again`() {
        val camera = Camera()
        val overview = camera.request("overview")
        camera.taken()
        assertFalse(camera.check("overview", overview, recovered = false))
        // A drag during a flight: the gesture takes the map, and the SDK is idle when the check runs.
        val following = camera.request("following")
        camera.taken()
        assertFalse(camera.check("following", following, recovered = false))
        assertTrue(camera.asked.isEmpty())
    }

    @Test
    fun `a move cancelled with nothing newer and still on its way is asked again, once`() {
        val camera = Camera()
        val following = camera.request("following")
        // Cancelled by something that asked nothing and left the SDK on its way: it computes no frames.
        assertTrue(camera.check("following", following, recovered = false))
        // The move asked again ends cancelled the same way: never a second time.
        val again = camera.request("following")
        assertFalse(camera.check("following", again, recovered = true))
        assertEquals(listOf("following"), camera.asked)
    }

    @Test
    fun `a move no longer on its way is not asked again`() {
        val camera = Camera()
        val overview = camera.request("overview")
        // It got there, or the SDK left it for another state: nothing to recover.
        camera.onItsWay = null
        assertFalse(camera.check("overview", overview, recovered = false))
        camera.onItsWay = "following"
        assertFalse(camera.check("overview", overview, recovered = false))
    }
}
