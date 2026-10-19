package app.wallhop.ui.design

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which way changing text rolls: up when its first differing number grew. */
class MotionTest {
    @Test fun latencyUp() = assertTrue(rose(" · 9 ms", " · 12 ms"))
    @Test fun latencyDown() = assertFalse(rose(" · 40 ms", " · 12 ms"))
    @Test fun batteryDrainingRollsDown() = assertFalse(rose("82% battery · on for 2 h 14 m", "81% battery · on for 2 h 15 m"))
    @Test fun laterNumberDecides() = assertTrue(rose("82% battery · on for 2 h 14 m", "82% battery · on for 2 h 15 m"))
    @Test fun noNumbersRollsUp() = assertTrue(rose("just connected", "on for 1 min"))
}
