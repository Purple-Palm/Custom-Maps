package io.github.purplepalm.custommaps.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** At most one update a second, at once when what the card shows changes. */
class CarThrottleTest {

    @Test
    fun `the first update goes at once, the next within the second waits for its end`() {
        val throttle = CarThrottle()
        assertEquals(CarThrottle.Decision.Now, throttle.offer(0, "exit"))
        throttle.sent(0, "exit")
        assertEquals(CarThrottle.Decision.At(1_000), throttle.offer(10, "exit"))
        assertEquals(CarThrottle.Decision.At(1_000), throttle.offer(999, "exit"))
        assertEquals(CarThrottle.Decision.Now, throttle.offer(1_000, "exit"))
    }

    @Test
    fun `a change of what the card shows goes at once`() {
        val throttle = CarThrottle()
        throttle.sent(0, "exit")
        assertEquals(CarThrottle.Decision.Now, throttle.offer(5, "turn"))
    }

    @Test
    fun `after the drive, or a clock that went back, the next update goes at once`() {
        val throttle = CarThrottle()
        throttle.sent(5_000, "exit")
        assertEquals(CarThrottle.Decision.Now, throttle.offer(4_000, "exit"))
        throttle.reset()
        assertEquals(CarThrottle.Decision.Now, throttle.offer(5_001, "exit"))
    }
}
