package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.AppGraph
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Keeps the backup exclusions in sync with where the token file and the debug log really live. */
class BackupRulesTest {

    // Unit tests run with the module directory as the working directory.
    private val xmlDirectory = File("src/main/res/xml")

    @Test
    fun `token file is excluded from cloud backup and device transfer`() {
        val root = parse("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = root.getElementsByTagName(section).item(0) as Element
            assertTrue(
                DataStoreTokenStore.RELATIVE_PATH in excludedFiles(element),
                "$section must exclude ${DataStoreTokenStore.RELATIVE_PATH}",
            )
        }
    }

    @Test
    fun `token file is excluded from full backup on older Android versions`() {
        val root = parse("backup_rules.xml")
        assertTrue(DataStoreTokenStore.RELATIVE_PATH in excludedFiles(root))
    }

    @Test
    fun `debug log stays out of every backup and transfer`() {
        val dataExtraction = parse("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = dataExtraction.getElementsByTagName(section).item(0) as Element
            assertTrue(AppGraph.DEBUG_LOG_DIRECTORY in excludedFiles(element), "$section must exclude the debug log")
        }
        assertTrue(AppGraph.DEBUG_LOG_DIRECTORY in excludedFiles(parse("backup_rules.xml")))
    }

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDirectory, name)).documentElement

    private fun excludedFiles(parent: Element): Set<String> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }
}
