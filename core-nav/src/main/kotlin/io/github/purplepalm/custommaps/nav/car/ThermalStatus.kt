package io.github.purplepalm.custommaps.nav.car

/**
 * The phone's thermal status as Android reports it from Android 10 on (`PowerManager`'s
 * `THERMAL_STATUS_*`), coolest first, each with the number Android gives it. The app reads it with the
 * one listener that writes the "thermal" lines; the car's map gets lighter while the phone is [hot]
 * ([CarLightRule]).
 */
enum class ThermalStatus(val code: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6),
    ;

    /** From [MODERATE] on: Android begins to hold the phone back, and the car's map gets lighter. */
    val hot: Boolean get() = this >= MODERATE

    companion object {
        /** The status Android reports as [code]; null for a number it has no name for here. */
        fun of(code: Int): ThermalStatus? = entries.firstOrNull { it.code == code }
    }
}
