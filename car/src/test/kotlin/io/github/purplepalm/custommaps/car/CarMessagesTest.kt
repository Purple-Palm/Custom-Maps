package io.github.purplepalm.custommaps.car

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * The words on the car's screen, as its resources have them. Driving comes first: anything that needs
 * the phone says so, to be done when the car is parked.
 */
class CarMessagesTest {

    // Unit tests run with the module directory as the working directory.
    private val strings: Map<String, String> = run {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun words(id: Int): String {
        val name = R.string::class.java.fields.single { it.getInt(null) == id }.name
        return strings.getValue(name)
    }

    private fun text(message: CarMessage) = words(CarMessages.text(message))

    @Test
    fun `Start on phone says how a drive begins, before one and in the preview`() {
        assertEquals("Start on phone", strings["car_start_on_phone"])
        assertEquals(
            "Choose a destination and press Start on the phone when you are parked. The drive then shows here.",
            text(CarMessage.START_IDLE),
        )
        assertEquals("Press Start on the phone when you are parked.", text(CarMessage.START_PREVIEW))
        for (message in listOf(CarMessage.START_IDLE, CarMessage.START_PREVIEW)) {
            assertEquals("Start on phone", words(checkNotNull(CarMessages.title(message))))
        }
    }

    @Test
    fun `the setup's messages say what is missing and that it is done on the phone when parked`() {
        assertTrue("Mapbox token" in text(CarMessage.NO_TOKEN))
        assertTrue("precise location" in text(CarMessage.NO_PRECISE_LOCATION))
        for (message in listOf(CarMessage.NO_TOKEN, CarMessage.NO_PRECISE_LOCATION)) {
            val text = text(message)
            assertTrue("on the phone" in text && "when you are parked" in text, text)
            assertEquals("Set up on the phone", words(checkNotNull(CarMessages.title(message))))
        }
    }

    @Test
    fun `loading says so, under the app's icon alone`() {
        assertEquals("Loading Custom Maps…", text(CarMessage.LOADING))
        assertNull(CarMessages.title(CarMessage.LOADING))
    }

    @Test
    fun `every message has words of its own`() {
        val texts = CarMessage.entries.map(::text)
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(texts.size, texts.toSet().size)
        for (message in CarMessage.entries) {
            if (message != CarMessage.LOADING) assertNotNull(CarMessages.title(message), "$message")
        }
    }

    @Test
    fun `the drive's action is End, as on the phone`() {
        assertEquals("End", strings["car_end"])
    }
}
