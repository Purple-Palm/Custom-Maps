package io.github.purplepalm.custommaps.ui.settings

import javax.security.auth.x500.X500Principal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The default Android debug key is told by its certificate's subject. Invented other names. */
class DebugKeyTest {

    @Test
    fun `the default debug key's subject is found`() {
        assertTrue(isDefaultDebugSubject("CN=Android Debug,O=Android,C=US"))
        // As the platform writes the name of the key the build tools make.
        assertTrue(isDefaultDebugSubject(X500Principal("C=US, O=Android, CN=Android Debug").name))
        assertTrue(isDefaultDebugSubject("cn=Android Debug"))
    }

    @Test
    fun `a key of its own is not the default one`() {
        assertFalse(isDefaultDebugSubject("CN=Custom Maps Debug,O=Example Owner,C=US"))
        assertFalse(isDefaultDebugSubject("CN=Android Debug Bridge,O=Android,C=US"))
        assertFalse(isDefaultDebugSubject("O=Android Debug,C=US"))
        // An escaped comma is part of the name.
        assertFalse(isDefaultDebugSubject("CN=Android\\, Debug,O=Android"))
        assertFalse(isDefaultDebugSubject(""))
    }
}
