package io.github.purplepalm.custommaps.car

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * The routing card words things as the phone does: the car module's words for the drive are the
 * phone's own, read from both modules' resources.
 */
class CarDriveWordsTest {

    // Unit tests run with the module directory as the working directory.
    private fun strings(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private val car = strings("src/main/res/values/strings.xml")
    private val phone = strings("../app/src/main/res/values/strings.xml")

    @Test
    fun `the car's words for the drive are the phone's`() {
        val same = mapOf(
            "car_roundabout_exit" to "guidance_roundabout_exit",
            "car_then_on_left" to "guidance_then_on_left",
            "car_then_on_right" to "guidance_then_on_right",
            "car_destination" to "guidance_then_destination",
            "car_arrived" to "guidance_arrived",
            "car_end" to "guidance_end",
        )
        for ((carName, phoneName) in same) assertEquals(phone.getValue(phoneName), car.getValue(carName), carName)
        assertEquals("Exit %1\$d", car["car_roundabout_exit"])
        assertEquals("You have arrived", car["car_arrived"])
    }
}
