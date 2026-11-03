package io.github.purplepalm.custommaps.ui.settings

import io.github.purplepalm.custommaps.AppGraph
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** The share sheet can only open exported log files from the directory the FileProvider exposes. */
class DebugLogPathsTest {

    @Test
    fun `the file provider exposes the export directory and nothing else`() {
        // Unit tests run with the module directory as the working directory.
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/debug_log_paths.xml")).documentElement
        val paths = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("cache-path"), paths.map { it.tagName })
        assertEquals("${AppGraph.DEBUG_LOG_EXPORT_DIRECTORY}/", paths.single().getAttribute("path"))
    }
}
