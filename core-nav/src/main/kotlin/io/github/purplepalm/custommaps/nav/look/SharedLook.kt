package io.github.purplepalm.custommaps.nav.look

import io.github.purplepalm.custommaps.nav.LookApplied
import kotlinx.coroutines.flow.StateFlow

/** The screens that show the app's look: the phone's own and the car's. */
enum class LookScreen { PHONE, CAR }

/**
 * The app's one look, the day look or the night look, as each screen takes it: the phone's activity
 * and the car's session both keep it deciding while they are started ([runWhileStarted]) and show it
 * on their maps ([showOn]), so the car's map shows what the phone shows and a theme that waits for a
 * quiet moment waits on both. One for the process; the app decides it. Main thread only.
 */
interface SharedLook {

    /** True once the first look of this process is decided; then true for as long as the process lives. */
    val decided: StateFlow<Boolean>

    /** What every map is to show now; a map made now takes this value as it is made. */
    val lookOnMap: StateFlow<LookOnMap>

    /** Decides the first look of this process, if no screen has yet; later calls do nothing. */
    fun start()

    /**
     * While [screen] is started, until the caller is cancelled: the look is decided again as the
     * first started screen comes on screen, then by the sun and the drive. Screens started together
     * share that one loop, so a screen that starts while another is decides nothing of its own.
     */
    suspend fun runWhileStarted(screen: LookScreen = LookScreen.PHONE)

    /** Shows [lookOnMap] on a map until the caller is cancelled: each request goes to [setLook] as it comes. */
    suspend fun showOn(setLook: (request: LookOnMap, onSettled: (idleMs: Long?) -> Unit) -> LookApplied)

    /** Whether the guidance camera of [screen]'s map shows a close view now, which holds a switch. */
    fun onCloseView(closeView: Boolean, screen: LookScreen = LookScreen.PHONE)
}
