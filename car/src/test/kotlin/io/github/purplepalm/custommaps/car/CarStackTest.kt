package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's screens over its map: the setup's messages, Start on phone, and the template count the host
 * allows (docs/STAGE-2.md, The template quota).
 */
class CarStackTest {

    @Test
    fun `the setup's message shows until the map may`() {
        for (guidance in GuidanceState.entries) {
            assertEquals(CarMessage.LOADING, CarScreens.shown(CarGate.LOADING, guidance, guidance))
            assertEquals(CarMessage.NO_TOKEN, CarScreens.shown(CarGate.NO_TOKEN, null, guidance))
            assertEquals(CarMessage.NO_PRECISE_LOCATION, CarScreens.shown(CarGate.NO_PRECISE_LOCATION, null, guidance))
            assertNull(CarScreens.shown(CarGate.READY, null, guidance))
        }
    }

    @Test
    fun `Start on phone says what the state it was asked in needs`() {
        assertEquals(CarMessage.START_IDLE, CarScreens.shown(CarGate.READY, GuidanceState.IDLE, GuidanceState.IDLE))
        assertEquals(CarMessage.START_IDLE, CarScreens.shown(CarGate.READY, GuidanceState.ARRIVED, GuidanceState.ARRIVED))
        assertEquals(CarMessage.START_PREVIEW, CarScreens.shown(CarGate.READY, GuidanceState.PREVIEW, GuidanceState.PREVIEW))
        // During a drive the action is End: nothing to say.
        assertNull(CarScreens.shown(CarGate.READY, GuidanceState.ACTIVE, GuidanceState.ACTIVE))
        // Asked in another state: the map shows the drive as it is now.
        assertNull(CarScreens.shown(CarGate.READY, GuidanceState.IDLE, GuidanceState.PREVIEW))
    }

    @Test
    fun `the step between any two screens`() {
        val all = listOf<CarMessage?>(null) + CarMessage.entries
        for (from in all) {
            for (to in all) {
                val expected = when {
                    from == to -> CarStackStep.None
                    to == null -> CarStackStep.Pop
                    from == null -> CarStackStep.Push(to)
                    else -> CarStackStep.Replace(to)
                }
                assertEquals(expected, CarScreens.step(from, to), "$from to $to")
            }
        }
    }

    @Test
    fun `only the messages the driver asked for have Back, and only loading spins`() {
        assertEquals(setOf(CarMessage.START_IDLE, CarMessage.START_PREVIEW), CarMessage.entries.filter { it.back }.toSet())
        assertEquals(setOf(CarMessage.START_IDLE, CarMessage.START_PREVIEW), CarMessage.entries.filter { it.start }.toSet())
        assertEquals(setOf(CarMessage.LOADING), CarMessage.entries.filter { it.loading }.toSet())
    }

    @Test
    fun `a cold start from the car shows loading, then the map`() {
        val stack = CarStack()
        assertEquals(CarStackStep.Push(CarMessage.LOADING), stack.step(gate(MapsAccess.Checking), GuidanceState.IDLE))
        assertEquals(CarStackStep.Pop, stack.step(gate(MapsAccess.Ready(1)), GuidanceState.IDLE))
        assertNull(stack.shown)
    }

    @Test
    fun `a phone without a token, then without precise location, then set up`() {
        val stack = CarStack()
        stack.step(gate(MapsAccess.Checking), GuidanceState.IDLE)
        assertEquals(CarStackStep.Replace(CarMessage.NO_TOKEN), stack.step(gate(MapsAccess.NeedsToken), GuidanceState.IDLE))
        assertEquals(
            CarStackStep.Replace(CarMessage.NO_PRECISE_LOCATION),
            stack.step(CarGate.of(MapsAccess.Ready(1), preciseLocation = false), GuidanceState.IDLE),
        )
        stack.onGone(CarMessage.NO_TOKEN)
        assertEquals(CarMessage.NO_PRECISE_LOCATION, stack.shown)
        assertEquals(CarStackStep.Pop, stack.step(gate(MapsAccess.Ready(1)), GuidanceState.IDLE))
    }

    @Test
    fun `Start on phone shows its message until the drive changes`() {
        val stack = CarStack()
        stack.onStartAsked(GuidanceState.IDLE)
        assertEquals(CarStackStep.Push(CarMessage.START_IDLE), stack.step(CarGate.READY, GuidanceState.IDLE))
        assertEquals(CarStackStep.None, stack.step(CarGate.READY, GuidanceState.IDLE))
        // The phone shows routes now: back to the map, which shows them.
        assertEquals(CarStackStep.Pop, stack.step(CarGate.READY, GuidanceState.PREVIEW))
        stack.onGone(CarMessage.START_IDLE)
        assertEquals(CarStackStep.None, stack.step(CarGate.READY, GuidanceState.IDLE))
    }

    @Test
    fun `a drive that changes and comes back does not bring the old question back`() {
        val stack = CarStack()
        stack.onStartAsked(GuidanceState.IDLE)
        stack.step(CarGate.READY, GuidanceState.IDLE)
        assertEquals(CarStackStep.Pop, stack.step(CarGate.READY, GuidanceState.PREVIEW))
        // The preview closed on the phone before the message's screen was gone.
        assertEquals(CarStackStep.None, stack.step(CarGate.READY, GuidanceState.IDLE))
    }

    @Test
    fun `Back from Start on phone leaves the map, and the question is answered`() {
        val stack = CarStack()
        stack.onStartAsked(GuidanceState.PREVIEW)
        assertEquals(CarStackStep.Push(CarMessage.START_PREVIEW), stack.step(CarGate.READY, GuidanceState.PREVIEW))
        stack.onGone(CarMessage.START_PREVIEW)
        assertNull(stack.shown)
        assertEquals(CarStackStep.None, stack.step(CarGate.READY, GuidanceState.PREVIEW))
    }

    @Test
    fun `a token taken away while Start on phone shows puts the setup's message there`() {
        val stack = CarStack()
        stack.onStartAsked(GuidanceState.IDLE)
        stack.step(CarGate.READY, GuidanceState.IDLE)
        assertEquals(CarStackStep.Replace(CarMessage.NO_TOKEN), stack.step(CarGate.NO_TOKEN, GuidanceState.IDLE))
        stack.onGone(CarMessage.START_IDLE)
        assertEquals(CarMessage.NO_TOKEN, stack.shown)
        // Set up again: the map, not the old question.
        assertEquals(CarStackStep.Pop, stack.step(CarGate.READY, GuidanceState.IDLE))
    }

    @Test
    fun `the template count never passes the host's 5, and every flow ends on the map`() {
        val random = Random(20001)
        repeat(SEQUENCES) { sequence ->
            val car = SimulatedCar()
            repeat(EVENTS) { car.next(random) }
            assertTrue(car.host.most <= 2, "sequence $sequence: ${car.host.most} templates")
        }
    }

    /** The car's side of a random setup and drive, against the host's rules for the template count. */
    private class SimulatedCar {
        val host = HostModel()
        private val stack = CarStack()
        private var access: MapsAccess = MapsAccess.Checking
        private var generation = 0
        private var precise = false
        private var guidance = GuidanceState.IDLE

        init {
            settle()
        }

        fun next(random: Random) {
            when (random.nextInt(9)) {
                0 -> access = MapsAccess.NeedsToken
                1 -> access = MapsAccess.Ready(++generation)
                2 -> precise = true
                3 -> precise = false
                4 -> guidance = GuidanceState.entries[random.nextInt(GuidanceState.entries.size)]
                5, 6 -> if (host.onMap && gate() == CarGate.READY) stack.onStartAsked(guidance)
                else -> host.back()?.let(stack::onGone)
            }
            settle()
        }

        private fun gate() = CarGate.of(access, precise)

        /** What the session does after every change: one step, applied as the screen manager would. */
        private fun settle() {
            val gate = gate()
            host.apply(stack.step(gate, guidance)).forEach(stack::onGone)
            check(host.stack.size <= 2) { "${host.stack}" }
            if (gate != CarGate.READY) {
                check(host.stack.last() == CarScreens.shown(gate, null, guidance)?.name) { "$gate: ${host.stack}" }
            } else {
                val top = host.stack.last()
                check(top == ROOT || top == CarScreens.startMessage(guidance)?.name) { "$guidance: ${host.stack}" }
            }
            check(host.count <= 5) { "${host.count} templates" }
        }
    }

    /**
     * The host's count of templates in a task, by the rules the library documents for
     * `Screen.onGetTemplate`: a new template counts one, going back gives back what it passes, and the
     * home screen's navigation template starts the count over at one. Messages never change in place.
     */
    private class HostModel {
        val stack = mutableListOf(ROOT)
        var count = 1
            private set
        var most = 1
            private set
        val onMap: Boolean get() = stack.last() == ROOT

        /** Applies [step]; returns the messages it took away. */
        fun apply(step: CarStackStep): List<CarMessage> {
            val gone = when (step) {
                CarStackStep.None -> emptyList()
                is CarStackStep.Push -> emptyList<CarMessage>().also { push(step.message) }
                CarStackStep.Pop -> popToRoot()
                is CarStackStep.Replace -> popToRoot().also { push(step.message) }
            }
            most = maxOf(most, count)
            return gone
        }

        /** The driver's Back: a message the driver asked for goes; the setup's messages stay. */
        fun back(): CarMessage? {
            val top = stack.last()
            if (top == ROOT) return null
            val message = CarMessage.valueOf(top)
            if (!message.back) return null
            popToRoot()
            return message
        }

        private fun push(message: CarMessage) {
            stack += message.name
            count += 1
        }

        private fun popToRoot(): List<CarMessage> {
            val gone = stack.drop(1).map(CarMessage::valueOf)
            stack.retainAll(listOf(ROOT))
            // Back to the navigation template, which starts the task over.
            if (gone.isNotEmpty()) count = 1
            return gone
        }
    }

    private fun gate(access: MapsAccess) = CarGate.of(access, preciseLocation = true)

    private companion object {
        const val ROOT = "HOME"
        const val SEQUENCES = 2_000
        const val EVENTS = 40
    }
}
