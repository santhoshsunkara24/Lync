package app.wallhop.ui

import app.wallhop.ui.screens.reflow
import org.junit.Assert.assertEquals
import org.junit.Test

class ReflowTest {
    @Test fun joinsWrappedLinesOfAParagraph() {
        assertEquals("The goals of the licence are to stimulate worldwide development.",
            reflow("The goals of the licence are to stimulate\nworldwide development."))
    }

    @Test fun keepsBlankLinesHeadingsRulesAndLists() {
        val input = "PREAMBLE\nThe goals are\nsimple.\n\n-----\n1. First\nsecond line\n2. Next"
        assertEquals("PREAMBLE\nThe goals are simple.\n\n-----\n1. First second line\n2. Next", reflow(input))
    }
}
