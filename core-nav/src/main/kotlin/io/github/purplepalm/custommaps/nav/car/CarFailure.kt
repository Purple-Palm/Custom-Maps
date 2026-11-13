package io.github.purplepalm.custommaps.nav.car

/**
 * The car, the phone and the drive share one process: an exception that escapes the car's code on the
 * main thread would end the phone's guidance too. So every way into the car's code catches
 * `RuntimeException`, writes what this gives to the debug log and carries on with something safe.
 *
 * The line names the call and the exception's class and message, no stack. A message is the
 * library's own text; any decimal number with four or more decimals in it is masked, so that no
 * position can reach the log through one ([masked]). Tokens are masked in every line anyway.
 */
object CarFailure {

    /** The longest message kept, in characters. */
    const val MAX_MESSAGE = 200

    /** A number with four or more decimals, as a latitude or a longitude is written. */
    private val LONG_DECIMAL = Regex("""-?\d+\.\d{4,}""")

    /** The fields for the debug log of [error], caught in [call]. */
    fun fields(call: String, error: Throwable): Map<String, Any?> = mapOf(
        "call" to call,
        "error" to error.javaClass.name,
        "message" to error.message?.let(::masked),
    ).filterValues { it != null }

    /** [message] with every long decimal number masked, and no longer than [MAX_MESSAGE]. */
    fun masked(message: String): String = message.replace(LONG_DECIMAL, "#").take(MAX_MESSAGE)
}
