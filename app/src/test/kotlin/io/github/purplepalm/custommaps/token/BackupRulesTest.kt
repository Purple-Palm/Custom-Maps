package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.AppGraph
import io.github.purplepalm.custommaps.settings.DataStoreAppSettings
import io.github.purplepalm.custommaps.trip.DataStoreTripLogStore
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Keeps the backup rules in sync with where the app's files really live. Only the settings and the
 * trip log are included; with an include list, nothing else is backed up or transferred.
 */
class BackupRulesTest {

    // Unit tests run with the module directory as the working directory.
    private val xmlDirectory = File("src/main/res/xml")

    private val backedUp = setOf(DataStoreAppSettings.RELATIVE_PATH, DataStoreTripLogStore.RELATIVE_PATH)

    @Test
    fun `cloud backup and device transfer include only the settings and the trip log`() {
        val root = parse("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = root.getElementsByTagName(section).item(0) as Element
            assertEquals(backedUp, includedFiles(element), section)
            assertEquals(0, element.getElementsByTagName("exclude").length, "$section needs no excludes")
        }
    }

    @Test
    fun `full backup on older Android versions includes only the settings and the trip log`() {
        val root = parse("backup_rules.xml")
        assertEquals(backedUp, includedFiles(root))
    }

    @Test
    fun `the token and the debug log are never backed up`() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val included = includedFiles(parse(name))
            assertFalse(DataStoreTokenStore.RELATIVE_PATH in included, name)
            assertFalse(included.any { it.startsWith(AppGraph.DEBUG_LOG_DIRECTORY) }, name)
        }
    }

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDirectory, name)).documentElement

    private fun includedFiles(parent: Element): Set<String> {
        val nodes = parent.getElementsByTagName("include")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .onEach { assertEquals("file", it.getAttribute("domain"), "only app files are included") }
            .map { it.getAttribute("path") }
            .toSet()
    }
}
