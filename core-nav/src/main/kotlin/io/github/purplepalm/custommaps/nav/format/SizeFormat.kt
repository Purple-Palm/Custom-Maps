package io.github.purplepalm.custommaps.nav.format

import java.util.Locale

/** File sizes for the English UI, in decimal units like the rest of Android: "950 B", "12 KB", "2.4 MB". */
object SizeFormat {

    private const val KILO = 1_000L
    private const val MEGA = 1_000_000L

    fun bytes(bytes: Long): String {
        if (bytes < KILO) return "$bytes B"
        // Rounded first, so a size just under a megabyte reads "1.0 MB" rather than "1000 KB".
        val kilobytes = (bytes + KILO / 2) / KILO
        return if (kilobytes < KILO) {
            String.format(Locale.US, "%d KB", kilobytes)
        } else {
            String.format(Locale.US, "%.1f MB", bytes.toDouble() / MEGA)
        }
    }
}
