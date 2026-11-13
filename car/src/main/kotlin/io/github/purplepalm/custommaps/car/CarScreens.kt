package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.model.GuidanceState

/**
 * A message the car's screen shows over its map. Anything that needs the phone says so, to be done
 * when the car is parked; nothing here takes text input, lists or scrolls.
 *
 * @property back whether the driver can go back from it to the map, by its Back
 * @property loading whether it shows that something is loading instead of an icon
 */
enum class CarMessage(val back: Boolean, val loading: Boolean) {
    /** The token store is being read. */
    LOADING(back = false, loading = true),

    /** No Mapbox token: it is added on the phone. */
    NO_TOKEN(back = false, loading = false),

    /** No precise location: it is allowed on the phone. */
    NO_PRECISE_LOCATION(back = false, loading = false),

    /** How a drive starts, asked before one: a destination and Start, on the phone. */
    START_IDLE(back = true, loading = false),

    /** How a drive starts, asked while the phone shows the routes: Start, on the phone. */
    START_PREVIEW(back = true, loading = false),
    ;

    /** Whether the driver asked for it with Start on phone. */
    val start: Boolean get() = this == START_IDLE || this == START_PREVIEW
}

/** How the stack of the car's screens changes: the home screen at its root, at most one message on top. */
sealed interface CarStackStep {
    data object None : CarStackStep

    data class Push(val message: CarMessage) : CarStackStep

    /** Back to the home screen. */
    data object Pop : CarStackStep

    /** The message on top gives way to [message]: back to the home screen, then [message] on top. */
    data class Replace(val message: CarMessage) : CarStackStep
}

/**
 * Which message the car's screen shows over the home screen, and how the stack gets there.
 *
 * The host allows 5 templates in a task and closes the app past that; going back gives the count back,
 * and the home screen's navigation template starts it again (docs/STAGE-2.md, The template quota). So
 * the stack is the home screen and at most one message, a message is never changed in place (a new
 * message text would count), and a change of message goes back to the home screen first. Every flow
 * ends on the home screen.
 */
object CarScreens {

    /**
     * The message over the map: the gate's own while it is not [CarGate.READY]; then the one the driver
     * asked for with Start on phone, while the drive is still in [startAsked], the state it was asked in;
     * otherwise none, and the map shows.
     */
    fun shown(gate: CarGate, startAsked: GuidanceState?, guidance: GuidanceState): CarMessage? = when (gate) {
        CarGate.LOADING -> CarMessage.LOADING
        CarGate.NO_TOKEN -> CarMessage.NO_TOKEN
        CarGate.NO_PRECISE_LOCATION -> CarMessage.NO_PRECISE_LOCATION
        CarGate.READY -> if (startAsked == guidance) startMessage(guidance) else null
    }

    /** What Start on phone says in [guidance]; nothing during a drive, which has End instead. */
    fun startMessage(guidance: GuidanceState): CarMessage? = when (guidance) {
        GuidanceState.IDLE, GuidanceState.ARRIVED -> CarMessage.START_IDLE
        GuidanceState.PREVIEW -> CarMessage.START_PREVIEW
        GuidanceState.ACTIVE -> null
    }

    /** The step from showing [shown] to showing [wanted]; null is the home screen alone. */
    fun step(shown: CarMessage?, wanted: CarMessage?): CarStackStep = when {
        shown == wanted -> CarStackStep.None
        wanted == null -> CarStackStep.Pop
        shown == null -> CarStackStep.Push(wanted)
        else -> CarStackStep.Replace(wanted)
    }
}

/**
 * The car screens' state between steps: the message on top as the stack has it, and the drive's state
 * when the driver asked how to start. The session applies the steps to the screen manager; plain state,
 * main thread only.
 */
class CarStack {

    /** The message on top of the home screen; null while the home screen is on top. */
    var shown: CarMessage? = null
        private set

    /** The drive's state when Start on phone was pressed; null when its message is not asked for. */
    private var startAsked: GuidanceState? = null

    /** The driver pressed Start on phone while the drive was in [guidance]. */
    fun onStartAsked(guidance: GuidanceState) {
        startAsked = guidance
    }

    /**
     * The screen of [message] is gone: the driver went back from it, or a step took it away. Its
     * question is answered; a step that already put another message on top keeps that one.
     */
    fun onGone(message: CarMessage) {
        if (message == shown) shown = null
        if (message.start) startAsked = null
    }

    /**
     * The step to what [gate] and [guidance] ask for now. A change of the drive's state takes the
     * driver's Start on phone message away: the map shows the drive in its new state.
     */
    fun step(gate: CarGate, guidance: GuidanceState): CarStackStep {
        if (startAsked != null && startAsked != guidance) startAsked = null
        val wanted = CarScreens.shown(gate, startAsked, guidance)
        val step = CarScreens.step(shown, wanted)
        shown = wanted
        return step
    }
}
