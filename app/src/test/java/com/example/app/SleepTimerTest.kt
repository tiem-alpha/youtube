package com.example.app

import com.example.app.ui.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test fun `starting timer stores requested duration`() {
        val timer = SleepTimer().start(15)
        assertTrue(timer.active)
        assertEquals(900, timer.remainingSeconds)
    }

    @Test fun `tick never produces negative time`() {
        assertFalse(SleepTimer(1).tick().tick().active)
        assertEquals(0, SleepTimer(1).tick().remainingSeconds)
    }
}
