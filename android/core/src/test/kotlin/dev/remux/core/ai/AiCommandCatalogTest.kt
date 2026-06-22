package dev.remux.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCommandCatalogTest {
    @Test fun containsCommonCommands() {
        val labels = AiCommandCatalog.commands.map { it.label }
        assertTrue(labels.contains("/compact"))
        assertTrue(labels.contains("/clear"))
        assertTrue(labels.contains("yes"))
        assertTrue(labels.contains("no"))
    }

    @Test fun compactIsSentImmediately() {
        val cmd = AiCommandCatalog.byLabel("/compact")
        assertNotNull(cmd)
        assertEquals("/compact", cmd!!.insert)
        assertTrue(cmd.sendImmediately)
    }

    @Test fun modelCommandStagesWithoutSending() {
        val cmd = AiCommandCatalog.byLabel("/model")
        assertNotNull(cmd)
        assertEquals(false, cmd!!.sendImmediately)
    }

    @Test fun labelsAreUnique() {
        val labels = AiCommandCatalog.commands.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test fun everyCommandHasInsertAndDescription() {
        for (c in AiCommandCatalog.commands) {
            assertTrue("insert empty for ${c.label}", c.insert.isNotEmpty())
            assertTrue("description empty for ${c.label}", c.description.isNotEmpty())
        }
    }
}
