package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JumpFrameTest {

    @Test
    fun `without a seek every location and no progress moves the camera as usual`() {
        val frame = JumpFrame()
        assertTrue(frame.locationFrames(1_000))
        assertFalse(frame.progressJumps())
        assertTrue(frame.locationFrames(2_000))
    }

    @Test
    fun `after a seek the new place's progress frames it and puts the camera there once`() {
        val frame = JumpFrame()
        frame.onJump(10_000)
        // The new place's first location, and a second one if its progress is a little late.
        assertFalse(frame.locationFrames(10_000))
        assertFalse(frame.locationFrames(11_000))
        assertTrue(frame.progressJumps())
        // From then on as usual.
        assertTrue(frame.locationFrames(12_000))
        assertFalse(frame.progressJumps())
    }

    @Test
    fun `a progress that is overdue after a seek lets the locations move the camera again`() {
        val frame = JumpFrame()
        frame.onJump(10_000)
        assertFalse(frame.locationFrames(11_500))
        assertTrue(frame.locationFrames(11_501))
        // The progress that comes at last still puts the camera on the new place's frame.
        assertTrue(frame.progressJumps())
    }

    @Test
    fun `the end of a drive forgets a seek that waits`() {
        val frame = JumpFrame()
        frame.onJump(10_000)
        frame.reset()
        assertTrue(frame.locationFrames(10_000))
        assertFalse(frame.progressJumps())
    }
}
