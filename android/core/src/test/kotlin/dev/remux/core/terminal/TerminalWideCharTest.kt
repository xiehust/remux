package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWideCharTest {
    @Test fun cjkCharOccupiesTwoCells() {
        val t = Terminal(10, 2)
        t.feed("中")
        assertTrue(t.cellAt(0, 0).wide)
        assertEquals(0x4E2D, t.cellAt(0, 0).codePoint)
        assertTrue(t.cellAt(0, 1).widePlaceholder)
        assertEquals(2, t.cursorCol)
    }

    @Test fun twoCjkCharsAdvanceCursorByFour() {
        val t = Terminal(10, 2)
        t.feed("中文")
        assertTrue(t.cellAt(0, 2).wide)
        assertEquals(0x6587, t.cellAt(0, 2).codePoint)
        assertEquals(4, t.cursorCol)
        assertEquals("中文", t.rowText(0).trimEnd())
    }

    @Test fun combiningMarkHasZeroWidth() {
        val t = Terminal(10, 2)
        t.feed("é") // e + combining acute accent
        assertEquals('e', t.cellAt(0, 0).char)
        assertEquals(1, t.cursorCol)
    }

    @Test fun asciiAfterCjkAdvancesCorrectly() {
        val t = Terminal(10, 2)
        t.feed("中a")
        assertEquals('a', t.cellAt(0, 2).char)
        assertEquals(3, t.cursorCol)
    }

    @Test fun wideCharWrapsWhenOnlyOneColumnLeft() {
        val t = Terminal(3, 2)
        t.feed("ab中") // 'a','b' fill cols 0,1; cursor at col 2 (last) — no room for wide
        assertTrue(t.cellAt(1, 0).wide)
        assertEquals(0x4E2D, t.cellAt(1, 0).codePoint)
    }

    @Test fun charWidthHelper() {
        assertEquals(1, CharWidth.width('a'.code))
        assertEquals(2, CharWidth.width(0x4E2D)) // 中
        assertEquals(0, CharWidth.width(0x0301)) // combining acute
        assertFalse(CharWidth.isWide('a'.code))
    }
}
