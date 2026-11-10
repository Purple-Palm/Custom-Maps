package io.github.purplepalm.custommaps.nav.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Invented roads. */
class MainRoadsTest {

    private fun closestFirst(chosen: String?, vararg summaries: String?): List<String?> =
        MainRoads.closestFirst(chosen, summaries.toList()) { it }

    @Test
    fun `a summary gives its road names`() {
        assertEquals(setOf("oak street", "pine avenue"), MainRoads.of("Oak Street, Pine Avenue"))
        assertEquals(setOf("elm street"), MainRoads.of("Elm Street"))
    }

    @Test
    fun `space and letter case do not matter, and no summary has no roads`() {
        assertEquals(setOf("oak street", "pine avenue"), MainRoads.of("  oak STREET ,Pine Avenue,, "))
        assertTrue(MainRoads.of(null).isEmpty())
        assertTrue(MainRoads.of("").isEmpty())
        assertTrue(MainRoads.of(" , ").isEmpty())
    }

    @Test
    fun `shared roads are counted whatever their order`() {
        assertEquals(2, MainRoads.shared("Oak Street, Pine Avenue", "Pine Avenue, Oak Street"))
        assertEquals(1, MainRoads.shared("Oak Street, Pine Avenue", "Oak Street, Elm Street"))
        assertEquals(0, MainRoads.shared("Oak Street, Pine Avenue", "Elm Street"))
        assertEquals(0, MainRoads.shared(null, "Elm Street"))
        assertEquals(0, MainRoads.shared("Elm Street", null))
    }

    @Test
    fun `the route with the chosen roads comes first and the others keep their order`() {
        assertEquals(
            listOf("Oak Street, Pine Avenue", "Elm Street", "Pine Avenue, Birch Road"),
            closestFirst("Oak Street, Pine Avenue", "Elm Street", "Pine Avenue, Birch Road", "Oak Street, Pine Avenue"),
        )
    }

    @Test
    fun `the route that has the most of the chosen roads wins`() {
        assertEquals(
            listOf("Pine Avenue, Oak Street", "Elm Street", "Oak Street, Birch Road"),
            closestFirst("Oak Street, Pine Avenue", "Elm Street", "Oak Street, Birch Road", "Pine Avenue, Oak Street"),
        )
    }

    @Test
    fun `of two routes with as many chosen roads the earlier one stays ahead`() {
        assertEquals(
            listOf("Oak Street, Birch Road", "Elm Street", "Oak Street, Cedar Lane"),
            closestFirst("Oak Street, Pine Avenue", "Elm Street", "Oak Street, Birch Road", "Oak Street, Cedar Lane"),
        )
    }

    @Test
    fun `routes that share no road stay in the order they came`() {
        val routes = listOf("Elm Street", "Birch Road")
        assertSame(routes, MainRoads.closestFirst("Oak Street, Pine Avenue", routes) { it })
    }

    @Test
    fun `a first route that is the closest already stays first`() {
        val routes = listOf("Oak Street, Pine Avenue", "Oak Street, Elm Street")
        assertSame(routes, MainRoads.closestFirst("Oak Street, Pine Avenue", routes) { it })
    }

    @Test
    fun `without a chosen summary nothing is moved`() {
        val routes = listOf("Elm Street", "Oak Street")
        assertSame(routes, MainRoads.closestFirst(null, routes) { it })
        assertSame(routes, MainRoads.closestFirst(" ", routes) { it })
    }

    @Test
    fun `routes without a summary are passed over, and a list of one or none stays as it is`() {
        assertEquals(listOf("Oak Street", null, "Elm Street"), closestFirst("Oak Street", null, "Elm Street", "Oak Street"))
        assertEquals(listOf<String?>("Elm Street"), closestFirst("Oak Street", "Elm Street"))
        assertEquals(emptyList<String?>(), closestFirst("Oak Street"))
    }

    @Test
    fun `no route is lost or doubled`() {
        val routes = listOf("Elm Street", "Oak Street", "Elm Street", "Pine Avenue, Oak Street")
        val ordered = MainRoads.closestFirst("Pine Avenue, Oak Street", routes) { it }
        assertEquals(routes.size, ordered.size)
        assertEquals(routes.sorted(), ordered.sorted())
        assertEquals("Pine Avenue, Oak Street", ordered.first())
    }
}
