package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalEraseTest {
    private val esc = "\u001b"

    @Test fun eraseEntireDisplay() {
        val t = Terminal(20, 3)
        t.feed("aaa\r\nbbb\r\nccc")
        t.feed("$esc[2J")
        assertEquals("", t.rowText(0).trimEnd())
        assertEquals("", t.rowText(1).trimEnd())
        assertEquals("", t.rowText(2).trimEnd())
    }

    @Test fun eraseFromCursorToEndOfDisplay() {
        val t = Terminal(20, 3)
        t.feed("aaa\r\nbbb\r\nccc")
        t.feed("$esc[2;1H") // row 2, col 1
        t.feed("$esc[0J")
        assertEquals("aaa", t.rowText(0).trimEnd())
        assertEquals("", t.rowText(1).trimEnd())
        assertEquals("", t.rowText(2).trimEnd())
    }

    @Test fun eraseEntireLine() {
        val t = Terminal(20, 2)
        t.feed("hello")
        t.feed("$esc[2K")
        assertEquals("", t.rowText(0).trimEnd())
    }

    @Test fun eraseFromCursorToEndOfLine() {
        val t = Terminal(20, 2)
        t.feed("abcdef")
        t.feed("$esc[1;4H") // cursor on 'd'
        t.feed("$esc[0K")
        assertEquals("abc", t.rowText(0).trimEnd())
    }

    @Test fun eraseFromStartOfLineToCursor() {
        val t = Terminal(20, 2)
        t.feed("abcdef")
        t.feed("$esc[1;4H") // cursor on 'd' (col index 3)
        t.feed("$esc[1K")
        assertEquals("    ef", t.rowText(0).trimEnd())
    }
}
