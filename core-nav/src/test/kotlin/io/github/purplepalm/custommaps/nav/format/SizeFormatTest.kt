package io.github.purplepalm.custommaps.nav.format

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SizeFormatTest {

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @CsvSource(
        "0, 0 B",
        "999, 999 B",
        "1000, 1 KB",
        "12400, 12 KB",
        "999499, 999 KB",
        "1000000, 1.0 MB",
        "2400000, 2.4 MB",
        "52428800, 52.4 MB",
    )
    fun `sizes use decimal units`(bytes: Long, expected: String) {
        assertEquals(expected, SizeFormat.bytes(bytes))
    }

    @Test
    fun `the decimal point does not follow the phone's language`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            assertEquals("2.4 MB", SizeFormat.bytes(2_400_000))
        } finally {
            Locale.setDefault(default)
        }
    }
}
