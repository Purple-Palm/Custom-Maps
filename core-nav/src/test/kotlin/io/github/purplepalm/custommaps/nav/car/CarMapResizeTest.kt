package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A new size of the car's map: the options first, then, with a surface, the camera read and the map made again with the
 * camera kept and the surface counted as shown meanwhile; without one the next surface takes it. The camera is a word
 * here, as the map's would be a camera of its own.
 */
class CarMapResizeTest {

    private val calls = mutableListOf<String>()
    private var surface = true
    private var camera: String? = "the camera at zoom 15"
    private var remakes = true
    private var remakeThrows = false

    /** What the remake saw of the resize while it ran: whether it was remaking, and the camera it kept. */
    private val seen = mutableListOf<Pair<Boolean, String?>>()

    private lateinit var resize: CarMapResize<String>

    private val port = object : CarMapResize.Port<String> {
        override val hasSurface: Boolean get() = surface

        override fun camera(): String? {
            calls += "camera"
            return camera
        }

        override fun useSize(size: CarMapSize) {
            calls += "useSize ${size.factorText}"
        }

        override fun remake(): Boolean {
            calls += "remake"
            seen += resize.remaking to resize.keptCamera
            if (remakeThrows) throw IllegalStateException("The library refused")
            return remakes
        }
    }

    init {
        resize = CarMapResize(port)
    }

    @Test
    fun `x1_5 until a size comes, and the same size again does nothing`() {
        assertEquals(CarMapSize.X1_5, resize.size)
        assertNull(resize.request(CarMapSize.X1_5))
        assertEquals(emptyList<String>(), calls)
        assertFalse(resize.remaking)
    }

    @Test
    fun `with a surface the options come first, then the camera, then the map is made again with that camera kept`() {
        assertEquals(CarMapResize.Outcome.REMADE, resize.request(CarMapSize.X2))
        assertEquals(listOf("useSize 2", "camera", "remake"), calls)
        // The detach and the attach the remake brings see the surface as shown and the old map's camera.
        assertEquals(listOf(true to "the camera at zoom 15"), seen)
        assertEquals(CarMapSize.X2, resize.size)
        // Over once it returned: a host's own surface later is a surface like any other.
        assertFalse(resize.remaking)
        assertNull(resize.keptCamera)
    }

    @Test
    fun `without a surface the next one takes the size, and nothing is made`() {
        surface = false
        assertEquals(CarMapResize.Outcome.NEXT_SURFACE, resize.request(CarMapSize.X1))
        assertEquals(listOf("useSize 1"), calls)
        assertEquals(CarMapSize.X1, resize.size)
        assertNull(resize.request(CarMapSize.X1), "the size there is")
    }

    @Test
    fun `a map that never left the whole world keeps no camera`() {
        camera = null
        assertEquals(CarMapResize.Outcome.REMADE, resize.request(CarMapSize.X1_25))
        assertEquals(listOf(true to null), seen)
    }

    @Test
    fun `a remake that fails leaves the size to the next surface`() {
        remakes = false
        assertEquals(CarMapResize.Outcome.FAILED, resize.request(CarMapSize.X1_75))
        assertEquals(listOf("useSize 1.75", "camera", "remake"), calls)
        assertEquals(CarMapSize.X1_75, resize.size, "the options of every later map")
        assertFalse(resize.remaking)
        assertNull(resize.request(CarMapSize.X1_75), "asked again, it is the size there is")
    }

    @Test
    fun `a remake that throws ends the remaking and keeps the size for the next surface`() {
        remakeThrows = true
        assertThrows<IllegalStateException> { resize.request(CarMapSize.X1) }
        assertFalse(resize.remaking)
        assertNull(resize.keptCamera)
        assertEquals(CarMapSize.X1, resize.size)
    }

    @Test
    fun `each change is one request with an answer, every size in turn`() {
        val answers = listOf(CarMapSize.X1, CarMapSize.X2, CarMapSize.X2, CarMapSize.X1_25, CarMapSize.X1_5).map { resize.request(it) }
        assertEquals(
            listOf(CarMapResize.Outcome.REMADE, CarMapResize.Outcome.REMADE, null, CarMapResize.Outcome.REMADE, CarMapResize.Outcome.REMADE),
            answers,
        )
        assertEquals(4, calls.count { it == "remake" })
        assertEquals(listOf("useSize 1", "useSize 2", "useSize 1.25", "useSize 1.5"), calls.filter { it.startsWith("useSize") })
    }
}
