package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollbackTest {
    @Test fun pushesScrolledLinesToScrollback() {
        val t = Terminal(10, 2)
        t.feed("L0\r\nL1\r\nL2\r\nL3\r\nL4")
        assertEquals(3, t.scrollbackSize)
        assertEquals("L0", t.scrollbackText(0).trimEnd())
        assertEquals("L1", t.scrollbackText(1).trimEnd())
        assertEquals("L3", t.rowText(0).trimEnd())
        assertEquals("L4", t.rowText(1).trimEnd())
    }

    @Test fun respectsScrollbackLimit() {
        val t = Terminal(10, 2, scrollbackLimit = 2)
        for (i in 0 until 8) t.feed("line$i\r\n")
        assertTrue("scrollback should be capped at 2, was ${t.scrollbackSize}", t.scrollbackSize <= 2)
    }

    @Test fun scrollbackTextIsRetrievable() {
        val t = Terminal(10, 1)
        t.feed("one\r\ntwo\r\nthree")
        assertTrue(t.scrollbackSize >= 2)
        assertEquals("one", t.scrollbackText(0).trimEnd())
    }
}
