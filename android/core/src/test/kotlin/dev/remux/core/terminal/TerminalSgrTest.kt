package dev.remux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = "\u001b"

class TerminalSgrTest {
    private fun cellAfter(seq: String): Cell {
        val t = Terminal(80, 24)
        t.feed(seq)
        return t.cellAt(0, 0)
    }

    @Test fun ansi16ForegroundColor() {
        assertEquals(TerminalColor.Indexed(1), cellAfter("$ESC[31mX").fg)
    }

    @Test fun ansi16BackgroundColor() {
        assertEquals(TerminalColor.Indexed(2), cellAfter("$ESC[42mX").bg)
    }

    @Test fun brightForegroundColor() {
        assertEquals(TerminalColor.Indexed(9), cellAfter("$ESC[91mX").fg)
    }

    @Test fun indexed256Color() {
        assertEquals(TerminalColor.Indexed(200), cellAfter("$ESC[38;5;200mX").fg)
    }

    @Test fun truecolorForeground() {
        assertEquals(TerminalColor.Rgb(10, 20, 30), cellAfter("$ESC[38;2;10;20;30mX").fg)
    }

    @Test fun truecolorBackground() {
        assertEquals(TerminalColor.Rgb(200, 100, 50), cellAfter("$ESC[48;2;200;100;50mX").bg)
    }

    @Test fun boldAttribute() {
        assertTrue(cellAfter("$ESC[1mX").bold)
    }

    @Test fun underlineAttribute() {
        assertTrue(cellAfter("$ESC[4mX").underline)
    }

    @Test fun reverseAttribute() {
        assertTrue(cellAfter("$ESC[7mX").reverse)
    }

    @Test fun combinedAttributes() {
        val c = cellAfter("$ESC[1;4;31mX")
        assertTrue(c.bold)
        assertTrue(c.underline)
        assertEquals(TerminalColor.Indexed(1), c.fg)
    }

    @Test fun resetClearsAttributes() {
        val t = Terminal(80, 24)
        t.feed("$ESC[1;31mA${ESC}[0mB")
        assertTrue(t.cellAt(0, 0).bold)
        val b = t.cellAt(0, 1)
        assertFalse(b.bold)
        assertEquals(TerminalColor.Default, b.fg)
    }

    @Test fun defaultForegroundReset() {
        val t = Terminal(80, 24)
        t.feed("$ESC[31mA${ESC}[39mB")
        assertEquals(TerminalColor.Indexed(1), t.cellAt(0, 0).fg)
        assertEquals(TerminalColor.Default, t.cellAt(0, 1).fg)
    }

    @Test fun turnBoldOff() {
        val t = Terminal(80, 24)
        t.feed("$ESC[1mA${ESC}[22mB")
        assertTrue(t.cellAt(0, 0).bold)
        assertFalse(t.cellAt(0, 1).bold)
    }
}
