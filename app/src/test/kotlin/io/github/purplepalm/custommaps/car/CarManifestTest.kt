package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.ui.theme.Sources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * What the manifest says about the car's screen (docs/STAGE-2.md, Manifest), and the rules Google
 * Play and the car's security hold it to: the car app service in the navigation category, Car API
 * level 7, the template permissions, and no location in the background, no cluster and no NAVIGATE
 * action, which the step does not build. The merged manifest adds the libraries' own entries; these
 * are the app's.
 */
class CarManifestTest {

    // Unit tests run with the module directory as the working directory.
    private val manifest = parse(File("src/main/AndroidManifest.xml"))

    @Test
    fun `the car app service is exported in the navigation category, in the app's own process`() {
        val service = elements(manifest, "service").single { it.android("name") == ".car.CustomMapsCarAppService" }
        assertEquals("true", service.android("exported"))
        assertEquals("@string/app_name", service.android("label"))
        assertEquals("@mipmap/ic_launcher", service.android("icon"))
        assertNull(service.android("process"), "the car, the phone and the drive share one process")
        assertNull(service.android("permission"), "the host validator decides who may bind")
        val filter = elements(service, "intent-filter").single()
        assertEquals(listOf("androidx.car.app.CarAppService"), elements(filter, "action").map { it.android("name") })
        assertEquals(listOf("androidx.car.app.category.NAVIGATION"), elements(filter, "category").map { it.android("name") })
    }

    @Test
    fun `the class the manifest names is the service`() {
        assertEquals("io.github.purplepalm.custommaps.car.CustomMapsCarAppService", CustomMapsCarAppService::class.java.name)
    }

    @Test
    fun `the app declares Car API level 7, the template app description and the car theme`() {
        val metaData = elements(manifest.documentElement, "meta-data").associate { it.android("name") to it }
        assertEquals("7", metaData.getValue("androidx.car.app.minCarApiLevel").android("value"))
        assertEquals("@xml/automotive_app_desc", metaData.getValue("com.google.android.gms.car.application").android("resource"))
        assertEquals("@style/Theme.CustomMaps.Car", metaData.getValue("androidx.car.app.theme").android("resource"))
        val description = parse(File("src/main/res/xml/automotive_app_desc.xml")).documentElement
        assertEquals("automotiveApp", description.tagName)
        assertEquals(listOf("template"), elements(description, "uses").map { it.getAttribute("name") })
    }

    @Test
    fun `the car's theme sets both variants of both colours`() {
        val themes = parse(File("src/main/res/values/themes.xml"))
        val car = elements(themes.documentElement, "style").single { it.getAttribute("name") == "Theme.CustomMaps.Car" }
        assertEquals(
            setOf("carColorPrimary", "carColorPrimaryDark", "carColorSecondary", "carColorSecondaryDark"),
            elements(car, "item").map { it.getAttribute("name") }.toSet(),
        )
    }

    @Test
    fun `the car's templates and surface are asked for, and location in the background is not`() {
        val permissions = elements(manifest.documentElement, "uses-permission").map { it.android("name") }
        assertTrue("androidx.car.app.NAVIGATION_TEMPLATES" in permissions, "$permissions")
        assertTrue("androidx.car.app.ACCESS_SURFACE" in permissions, "$permissions")
        // Every manifest of the app, of every build type: Google Play reviews this permission.
        val manifests = File("src").walkTopDown().filter { it.name == "AndroidManifest.xml" }.toList()
        assertTrue(manifests.isNotEmpty())
        for (file in manifests) {
            assertFalse("ACCESS_BACKGROUND_LOCATION" in file.readText(), file.path)
        }
    }

    @Test
    fun `no cluster display and no NAVIGATE action`() {
        val text = File("src/main/AndroidManifest.xml").readText()
        assertFalse("FEATURE_CLUSTER" in text)
        assertFalse("androidx.car.app.action.NAVIGATE" in text)
    }

    @Test
    fun `every build type checks the host against the library's allow-list`() {
        // Google's sample lets any host bind to a debuggable build; the debug build is driven every day.
        val code = Sources.code("car/CustomMapsCarAppService.kt")
        assertTrue("addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)" in code)
        assertFalse("FLAG_DEBUGGABLE" in code)
        val app = Sources.filesUnder("")
        assertEquals(emptySet<String>(), app.filterKeys { "ALLOW_ALL_HOSTS_VALIDATOR" in Sources.code(it) }.keys)
    }

    private fun parse(file: File): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

    private fun elements(parent: Document, tag: String): List<Element> = elements(parent.documentElement, tag)

    /** The children of [parent] named [tag], at any depth. */
    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String? =
        getAttributeNS(ANDROID, name).takeIf { hasAttributeNS(ANDROID, name) }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
