package dev.remux.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMappingTest {
    @Test fun ctrlLettersMapToControlCodes() {
        assertEquals("\u0001", KeyMapping.ctrl('A')) // Ctrl-A
        assertEquals("\u0003", KeyMapping.ctrl('C')) // Ctrl-C
        assertEquals("\u0004", KeyMapping.ctrl('D')) // Ctrl-D
        assertEquals("\u001a", KeyMapping.ctrl('Z')) // Ctrl-Z
    }

    @Test fun ctrlIsCaseInsensitive() {
        assertEquals(KeyMapping.ctrl('C'), KeyMapping.ctrl('c'))
    }

    @Test fun ctrlSymbols() {
        assertEquals("\u0000", KeyMapping.ctrl(' ')) // Ctrl-Space = NUL
        assertEquals("\u001b", KeyMapping.ctrl('[')) // Ctrl-[ = ESC
    }

    @Test fun escAndTabBytes() {
        assertEquals("\u001b", KeyMapping.ESC)
        assertEquals("\t", KeyMapping.TAB)
        assertEquals("\r", KeyMapping.ENTER)
    }

    @Test fun arrowKeySequences() {
        assertEquals("\u001b[A", KeyMapping.ARROW_UP)
        assertEquals("\u001b[B", KeyMapping.ARROW_DOWN)
        assertEquals("\u001b[C", KeyMapping.ARROW_RIGHT)
        assertEquals("\u001b[D", KeyMapping.ARROW_LEFT)
    }

    @Test fun functionKeySequences() {
        assertEquals("\u001bOP", KeyMapping.function(1)) // F1
        assertEquals("\u001b[15~", KeyMapping.function(5)) // F5
        assertEquals("\u001b[24~", KeyMapping.function(12)) // F12
    }

    @Test fun defaultToolbarHasExpectedKeys() {
        val labels = ToolbarLayout.default.map { it.label }
        assertTrue(labels.contains("Ctrl"))
        assertTrue(labels.contains("Tab"))
        assertTrue(labels.contains("Esc"))
        assertTrue(labels.contains("↑"))
    }

    @Test fun ctrlModifierIsAModifierKey() {
        val ctrl = ToolbarLayout.default.first { it.label == "Ctrl" }
        assertTrue(ctrl is ToolbarKey.Modifier)
        val tab = ToolbarLayout.default.first { it.label == "Tab" }
        assertTrue(tab is ToolbarKey.Send)
        assertEquals("\t", (tab as ToolbarKey.Send).bytes)
    }
}
