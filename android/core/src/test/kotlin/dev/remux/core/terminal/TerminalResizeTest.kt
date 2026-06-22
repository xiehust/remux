package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResizeTest {
    @Test fun shrinkColumnsPreservesContentInBounds() {
        val t = Terminal(80, 24)
        t.feed("hello world")
        t.resize(40, 12)
        assertEquals(40, t.cols)
        assertEquals(12, t.rows)
        assertEquals("hello world", t.rowText(0).trimEnd())
    }

    @Test fun growColumns() {
        val t = Terminal(20, 5)
        t.feed("data")
        t.resize(120, 30)
        assertEquals(120, t.cols)
        assertEquals(30, t.rows)
        assertEquals("data", t.rowText(0).trimEnd())
    }

    @Test fun cursorClampedOnShrink() {
        val t = Terminal(80, 24)
        t.feed("\u001b[20;70H")
        t.resize(40, 12)
        assertTrue(t.cursorRow <= 11)
        assertTrue(t.cursorCol <= 39)
    }

    @Test fun scrollbackPreservedAcrossResize() {
        val t = Terminal(10, 2)
        t.feed("a\r\nb\r\nc\r\nd")
        val before = t.scrollbackSize
        assertTrue(before > 0)
        t.resize(20, 4)
        assertEquals(before, t.scrollbackSize)
    }

    @Test fun resizeDoesNotCrashOnExtremeShrink() {
        val t = Terminal(80, 24)
        t.feed("content here")
        t.resize(1, 1)
        assertEquals(1, t.cols)
        assertEquals(1, t.rows)
    }
}
