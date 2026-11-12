package io.github.purplepalm.custommaps.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Every one of the 48 roles of the day and the night scheme holds the colour the design gives it,
 * so no role is left to Material's baseline, whose lavender and purple would show wherever a
 * component reads it. A role left out fails here unless the design chose Material's very value for
 * it, as it does for seven of them: white for the day's onPrimary, onSecondary, onTertiary,
 * onError and surfaceContainerLowest, and black for the scrim in both looks.
 */
class ColorSchemeTest {

    private class Role(val name: String, val of: (ColorScheme) -> Color, val day: Long, val night: Long)

    private val roles = listOf(
        Role("primary", { it.primary }, 0xFF8A5A00, 0xFFF5A524),
        Role("onPrimary", { it.onPrimary }, 0xFFFFFFFF, 0xFF1C1300),
        Role("primaryContainer", { it.primaryContainer }, 0xFFFCE8C8, 0xFF3A2E17),
        Role("onPrimaryContainer", { it.onPrimaryContainer }, 0xFF2B1C00, 0xFFFFE3B8),
        Role("inversePrimary", { it.inversePrimary }, 0xFFF5A524, 0xFFF5A524),
        Role("secondary", { it.secondary }, 0xFF4A5059, 0xFFA9ADB3),
        Role("onSecondary", { it.onSecondary }, 0xFFFFFFFF, 0xFF0E1013),
        Role("secondaryContainer", { it.secondaryContainer }, 0xFFFCE8C8, 0xFF3A2E17),
        Role("onSecondaryContainer", { it.onSecondaryContainer }, 0xFF2B1C00, 0xFFFFE3B8),
        Role("tertiary", { it.tertiary }, 0xFF15703F, 0xFF4CC38A),
        Role("onTertiary", { it.onTertiary }, 0xFFFFFFFF, 0xFF0E1013),
        Role("tertiaryContainer", { it.tertiaryContainer }, 0xFFD5F0DF, 0xFF17382A),
        Role("onTertiaryContainer", { it.onTertiaryContainer }, 0xFF0A3A21, 0xFFC2F0D4),
        Role("background", { it.background }, 0xFFF3F4F6, 0xFF0E1013),
        Role("onBackground", { it.onBackground }, 0xFF15181C, 0xFFE5E4E2),
        Role("surface", { it.surface }, 0xFFF3F4F6, 0xFF0E1013),
        Role("onSurface", { it.onSurface }, 0xFF15181C, 0xFFE5E4E2),
        Role("surfaceVariant", { it.surfaceVariant }, 0xFFEEF0F2, 0xFF20242A),
        Role("onSurfaceVariant", { it.onSurfaceVariant }, 0xFF4A5059, 0xFFA9ADB3),
        Role("surfaceTint", { it.surfaceTint }, 0xFFF3F4F6, 0xFF0E1013),
        Role("inverseSurface", { it.inverseSurface }, 0xFF23272C, 0xFF2B3038),
        Role("inverseOnSurface", { it.inverseOnSurface }, 0xFFF1F1EF, 0xFFE5E4E2),
        Role("error", { it.error }, 0xFFB42318, 0xFFFF8A80),
        Role("onError", { it.onError }, 0xFFFFFFFF, 0xFF0E1013),
        Role("errorContainer", { it.errorContainer }, 0xFFFDE4E1, 0xFF5C1C17),
        Role("onErrorContainer", { it.onErrorContainer }, 0xFF5A0F0A, 0xFFFFDAD5),
        Role("outline", { it.outline }, 0xFF7A818A, 0xFF6E747C),
        Role("outlineVariant", { it.outlineVariant }, 0xFFE1E3E7, 0xFF2A2E34),
        Role("scrim", { it.scrim }, 0xFF000000, 0xFF000000),
        Role("surfaceBright", { it.surfaceBright }, 0xFFFFFFFF, 0xFF2B3038),
        Role("surfaceDim", { it.surfaceDim }, 0xFFE4E6E9, 0xFF0B0D10),
        Role("surfaceContainer", { it.surfaceContainer }, 0xFFFFFFFF, 0xFF171A1E),
        Role("surfaceContainerHigh", { it.surfaceContainerHigh }, 0xFFEEF0F2, 0xFF20242A),
        Role("surfaceContainerHighest", { it.surfaceContainerHighest }, 0xFFE4E6E9, 0xFF2A2E34),
        Role("surfaceContainerLow", { it.surfaceContainerLow }, 0xFFF8F9FA, 0xFF131619),
        Role("surfaceContainerLowest", { it.surfaceContainerLowest }, 0xFFFFFFFF, 0xFF0B0D10),
        Role("primaryFixed", { it.primaryFixed }, 0xFFFCE8C8, 0xFFFCE8C8),
        Role("primaryFixedDim", { it.primaryFixedDim }, 0xFFF5C47A, 0xFFF5C47A),
        Role("onPrimaryFixed", { it.onPrimaryFixed }, 0xFF2B1C00, 0xFF2B1C00),
        Role("onPrimaryFixedVariant", { it.onPrimaryFixedVariant }, 0xFF6B4600, 0xFF6B4600),
        Role("secondaryFixed", { it.secondaryFixed }, 0xFFE4E6E9, 0xFFE4E6E9),
        Role("secondaryFixedDim", { it.secondaryFixedDim }, 0xFFC9CDD2, 0xFFC9CDD2),
        Role("onSecondaryFixed", { it.onSecondaryFixed }, 0xFF15181C, 0xFF15181C),
        Role("onSecondaryFixedVariant", { it.onSecondaryFixedVariant }, 0xFF4A5059, 0xFF4A5059),
        Role("tertiaryFixed", { it.tertiaryFixed }, 0xFFD5F0DF, 0xFFD5F0DF),
        Role("tertiaryFixedDim", { it.tertiaryFixedDim }, 0xFF9ED9B6, 0xFF9ED9B6),
        Role("onTertiaryFixed", { it.onTertiaryFixed }, 0xFF0A3A21, 0xFF0A3A21),
        Role("onTertiaryFixedVariant", { it.onTertiaryFixedVariant }, 0xFF15703F, 0xFF15703F),
    )

    @Test
    fun `the table holds every role a colour scheme has, each once`() {
        // A role is a getter of a colour, which the compiler names after the role and returns as a long.
        val schemeRoles = ColorScheme::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE && it.name.startsWith("get") }
            .map { it.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercaseChar) }
            .toSet()
        assertEquals(48, schemeRoles.size)
        assertEquals(48, roles.map { it.name }.toSet().size)
        assertEquals(schemeRoles, roles.map { it.name }.toSet())
    }

    @Test
    fun `every role of the day scheme is the design's`() {
        assertAll(roles.map { role -> Executable { assertEquals(Color(role.day), role.of(DayScheme), role.name) } })
    }

    @Test
    fun `every role of the night scheme is the design's`() {
        assertAll(roles.map { role -> Executable { assertEquals(Color(role.night), role.of(NightScheme), role.name) } })
    }
}
