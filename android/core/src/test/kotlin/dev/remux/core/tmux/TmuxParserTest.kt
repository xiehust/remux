package dev.remux.core.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxParserTest {
    @Test fun parsesZeroSessions() {
        assertTrue(TmuxParser.parseSessions("").isEmpty())
    }

    @Test fun parsesMultipleSessions() {
        val out = """
            main: 3 windows (created Mon Jun 22 10:00:00 2026) (attached)
            work: 1 windows (created Mon Jun 22 09:00:00 2026)
        """.trimIndent()
        val sessions = TmuxParser.parseSessions(out)
        assertEquals(2, sessions.size)
        assertEquals("main", sessions[0].name)
        assertEquals(3, sessions[0].windows)
        assertTrue(sessions[0].attached)
        assertEquals("work", sessions[1].name)
        assertEquals(1, sessions[1].windows)
        assertFalse(sessions[1].attached)
    }

    @Test fun parsesSingleWindowSession() {
        val s = TmuxParser.parseSessions("0: 1 windows (created x)")
        assertEquals(1, s.size)
        assertEquals("0", s[0].name)
        assertEquals(1, s[0].windows)
        assertFalse(s[0].attached)
    }

    @Test fun parsesWindows() {
        val out = """
            0: bash* (1 panes) [80x24]
            1: vim- (2 panes) [80x24]
            2: logs (1 panes) [80x24]
        """.trimIndent()
        val windows = TmuxParser.parseWindows(out)
        assertEquals(3, windows.size)
        assertEquals(0, windows[0].index)
        assertEquals("bash", windows[0].name)
        assertTrue(windows[0].active)
        assertEquals(1, windows[0].panes)
        assertEquals("vim", windows[1].name)
        assertFalse(windows[1].active)
        assertEquals(2, windows[1].panes)
    }

    @Test fun ignoresBlankLines() {
        val s = TmuxParser.parseSessions("\n\nmain: 2 windows\n\n")
        assertEquals(1, s.size)
        assertEquals(2, s[0].windows)
    }
}
