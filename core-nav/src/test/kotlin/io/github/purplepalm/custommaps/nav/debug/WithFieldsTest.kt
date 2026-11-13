package io.github.purplepalm.custommaps.nav.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WithFieldsTest {

    private val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val base = object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }
    }

    @Test
    fun `every line carries the fields, ahead of its own`() {
        val car = base.withFields(mapOf("screen" to "car"))
        car.log("camera", mapOf("zoom" to 16.0))
        car.log("camera_state")
        assertEquals(listOf("camera", "camera_state"), lines.map { it.first })
        assertEquals(listOf("screen", "zoom"), lines[0].second.keys.toList())
        assertEquals(mapOf("screen" to "car"), lines[1].second)
    }

    @Test
    fun `a line's own field of the same name wins`() {
        base.withFields(mapOf("screen" to "car")).log("x", mapOf("screen" to "phone"))
        assertEquals(mapOf("screen" to "phone"), lines.single().second)
    }

    @Test
    fun `errors carry them too, and the plain log is left as it is`() {
        base.withFields(mapOf("screen" to "car")).error("onAttached", IllegalStateException("gone"))
        base.log("camera", mapOf("zoom" to 16.0))
        assertEquals("car", lines[0].second["screen"])
        assertEquals("error", lines[0].first)
        assertEquals(mapOf("zoom" to 16.0), lines[1].second)
    }

    @Test
    fun `fields changed afterwards do not reach the lines`() {
        val tags = mutableMapOf<String, Any?>("screen" to "car")
        val car = base.withFields(tags)
        tags["screen"] = "other"
        car.log("x")
        assertEquals(mapOf("screen" to "car"), lines.single().second)
    }
}
