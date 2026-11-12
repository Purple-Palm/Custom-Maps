package io.github.purplepalm.custommaps.ui.theme

import kotlin.math.pow
import kotlin.math.roundToLong

/** WCAG 2.2's contrast ratio, for the tests of the looks' colours; colours are ARGB as in [LookPalette]. */
internal object Contrast {

    /** The lighter colour's relative luminance over the darker's, each plus 0.05. Both opaque. */
    fun ratio(a: Long, b: Long): Double {
        require(alpha(a) == 0xFF && alpha(b) == 0xFF) { "contrast is measured between opaque colours" }
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [fg] at its own alpha, times [alpha], drawn over the opaque [bg]: the colour on the screen. */
    fun over(fg: Long, bg: Long, alpha: Double = 1.0): Long {
        val a = alpha(fg) / 255.0 * alpha
        val channels = listOf(16, 8, 0).map { shift ->
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            (a * f + (1 - a) * b).roundToLong()
        }
        return (0xFFL shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    private fun alpha(argb: Long): Int = ((argb shr 24) and 0xFF).toInt()

    private fun luminance(argb: Long): Double {
        fun linear(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
