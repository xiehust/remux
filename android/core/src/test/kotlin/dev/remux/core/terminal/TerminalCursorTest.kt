package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCursorTest {
    private val esc = "\u001b"

    @Test fun cursorPosition() {
        val t = Terminal(80, 24)
        t.feed("$esc[5;10H")
        assertEquals(4, t.cursorRow)
        assertEquals(9, t.cursorCol)
    }

    @Test fun cursorUp() {
        val t = Terminal(80, 24)
        t.feed("$esc[10;10H$esc[3A")
        assertEquals(6, t.cursorRow)
    }

    @Test fun cursorDown() {
        val t = Terminal(80, 24)
        t.feed("$esc[2;2H$esc[5B")
        assertEquals(6, t.cursorRow)
    }

    @Test fun cursorForward() {
        val t = Terminal(80, 24)
        t.feed("$esc[1;1H$esc[7C")
        assertEquals(7, t.cursorCol)
    }

    @Test fun cursorBack() {
        val t = Terminal(80, 24)
        t.feed("$esc[1;20H$esc[5D")
        assertEquals(14, t.cursorCol)
    }

    @Test fun cursorColumnAbsolute() {
        val t = Terminal(80, 24)
        t.feed("$esc[1;1H$esc[40G")
        assertEquals(39, t.cursorCol)
    }

    @Test fun cursorRowAbsolute() {
        val t = Terminal(80, 24)
        t.feed("$esc[12d")
        assertEquals(11, t.cursorRow)
    }

    @Test fun cursorNextLine() {
        val t = Terminal(80, 24)
        t.feed("$esc[3;5H$esc[2E")
        assertEquals(4, t.cursorRow)
        assertEquals(0, t.cursorCol)
    }

    @Test fun cursorClampsToBounds() {
        val t = Terminal(10, 5)
        t.feed("$esc[100;100H")
        assertEquals(4, t.cursorRow)
        assertEquals(9, t.cursorCol)
    }

    @Test fun saveAndRestoreCursor() {
        val t = Terminal(80, 24)
        t.feed("$esc[5;5H${esc}7$esc[20;20H${esc}8")
        assertEquals(4, t.cursorRow)
        assertEquals(4, t.cursorCol)
    }
}
