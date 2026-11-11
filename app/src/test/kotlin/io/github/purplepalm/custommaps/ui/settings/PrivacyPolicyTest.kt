package io.github.purplepalm.custommaps.ui.settings

import android.content.ActivityNotFoundException
import java.io.File
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Settings, About opens the privacy policy: the public GitHub page of docs/PRIVACY.md. */
class PrivacyPolicyTest {

    // Unit tests run with the module directory as the working directory.
    private val policy = File("../docs/PRIVACY.md")

    @Test
    fun `the link is the public page of docs PRIVACY md on main`() {
        assertEquals("https://github.com/Purple-Palm/Custom-Maps/blob/main/docs/PRIVACY.md", PRIVACY_POLICY_URL)
        val uri = URI(PRIVACY_POLICY_URL)
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
    }

    @Test
    fun `the page the link names is in the repository`() {
        assertTrue(File("..", PRIVACY_POLICY_URL.substringAfter("/blob/main/")).isFile)
        assertTrue(policy.isFile)
    }

    @Test
    fun `the policy names the Mapbox terms that the data is used under`() {
        // Mapbox's Product Terms (1.11) ask every app's privacy notice for these three.
        val text = policy.readText()
        for (link in listOf(
            "https://www.mapbox.com/legal/product-terms",
            "https://www.mapbox.com/legal/dpa",
            "https://www.mapbox.com/legal/privacy",
        )) {
            assertTrue(link in text, link)
        }
    }

    @Test
    fun `a phone that cannot open the page gets false instead of a crash`() {
        assertTrue(opened {})
        assertFalse(opened { throw ActivityNotFoundException() })
        assertFalse(opened { throw SecurityException() })
    }
}
