package dev.remux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seed test proving the :core JVM test pipeline runs without an emulator. */
class SeedTest {
    @Test
    fun taglineIsPresent() {
        assertTrue(Remux.TAGLINE.isNotEmpty())
    }

    @Test
    fun nameIsRemux() {
        assertEquals("Remux", Remux.NAME)
    }
}
