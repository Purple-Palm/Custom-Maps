package io.github.purplepalm.custommaps.nav.format

import java.util.Locale

/** File sizes for the English UI, in decimal units like the rest of Android: "950 B", "12 KB", "2.4 MB". */
object SizeFormat {

    private const val KILO = 1_000L
    private const val MEGA = 1_000_000L

    fun bytes(bytes: Long): String = when {
        bytes < KILO -> "$bytes B"
        bytes < MEGA -> String.format(Locale.US, "%d KB", (bytes + KILO / 2) / KILO)
        else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / MEGA)
    }
}
