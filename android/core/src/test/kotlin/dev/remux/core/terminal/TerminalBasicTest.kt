package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalBasicTest {
    @Test fun printsPlainText() {
        val t = Terminal(80, 24)
        t.feed("hello")
        assertEquals("hello", t.rowText(0).trimEnd())
        assertEquals(0, t.cursorRow)
        assertEquals(5, t.cursorCol)
    }

    @Test fun carriageReturnAndLineFeed() {
        val t = Terminal(80, 24)
        t.feed("ab\r\ncd")
        assertEquals("ab", t.rowText(0).trimEnd())
        assertEquals("cd", t.rowText(1).trimEnd())
        assertEquals(1, t.cursorRow)
    }

    @Test fun lineFeedWithoutCarriageReturnKeepsColumn() {
        val t = Terminal(80, 24)
        t.feed("ab\nc")
        assertEquals("ab", t.rowText(0).trimEnd())
        assertEquals("  c", t.rowText(1).trimEnd())
        assertEquals('c', t.cellAt(1, 2).char)
    }

    @Test fun autoWrapsAtRightEdge() {
        val t = Terminal(3, 4)
        t.feed("abcd")
        assertEquals("abc", t.rowText(0).trimEnd())
        assertEquals("d", t.rowText(1).trimEnd())
        assertEquals(1, t.cursorRow)
        assertEquals(1, t.cursorCol)
    }

    @Test fun tabAdvancesToNextStop() {
        val t = Terminal(80, 24)
        t.feed("a\tb")
        assertEquals('a', t.cellAt(0, 0).char)
        assertEquals('b', t.cellAt(0, 8).char)
        assertEquals(9, t.cursorCol)
    }

    @Test fun backspaceMovesCursorLeft() {
        val t = Terminal(80, 24)
        t.feed("abc")
        t.feed(byteArrayOf(0x08)) // BS
        assertEquals(2, t.cursorCol)
    }

    @Test fun scrollsWhenWritingPastBottom() {
        val t = Terminal(10, 2)
        t.feed("L0\r\nL1\r\nL2")
        assertEquals("L1", t.rowText(0).trimEnd())
        assertEquals("L2", t.rowText(1).trimEnd())
        assertEquals(1, t.scrollbackSize)
        assertEquals("L0", t.scrollbackText(0).trimEnd())
    }

    @Test fun utf8MultiByteDecodes() {
        val t = Terminal(80, 24)
        t.feed("café") // é is 2 UTF-8 bytes
        assertEquals("café", t.rowText(0).trimEnd())
    }
}
