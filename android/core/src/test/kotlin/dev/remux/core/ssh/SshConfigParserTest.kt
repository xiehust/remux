package dev.remux.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigParserTest {
    private val config = """
        # Remux test config
        Host myec2
            HostName 1.2.3.4
            User ubuntu
            Port 2222
            IdentityFile ~/.ssh/id_ed25519

        Host bastion
            HostName bastion.example.com
            User jump

        Host internal
            HostName 10.0.0.5
            ProxyJump bastion

        Host *.dev
            User developer
    """.trimIndent()

    @Test fun resolvesBasicAlias() {
        val c = SshConfigParser.resolve(config, "myec2")
        assertEquals("1.2.3.4", c.hostName)
        assertEquals("ubuntu", c.user)
        assertEquals(2222, c.port)
        assertEquals("~/.ssh/id_ed25519", c.identityFile)
    }

    @Test fun resolvesProxyJump() {
        val c = SshConfigParser.resolve(config, "internal")
        assertEquals("10.0.0.5", c.hostName)
        assertEquals("bastion", c.proxyJump)
    }

    @Test fun defaultsPortTo22() {
        val c = SshConfigParser.resolve(config, "bastion")
        assertEquals(22, c.port)
    }

    @Test fun unknownAliasYieldsDefaults() {
        val c = SshConfigParser.resolve(config, "nope")
        assertNull(c.hostName)
        assertEquals(22, c.port)
    }

    @Test fun wildcardPatternMatches() {
        val c = SshConfigParser.resolve(config, "web.dev")
        assertEquals("developer", c.user)
    }

    @Test fun matchesHelper() {
        assertTrue(SshConfigParser.matches("*", "anything"))
        assertTrue(SshConfigParser.matches("*.dev", "web.dev"))
        assertTrue(SshConfigParser.matches("host?", "host1"))
        assertEquals(false, SshConfigParser.matches("*.dev", "web.prod"))
    }

    @Test fun supportsEqualsSyntax() {
        val c = SshConfigParser.resolve("Host x\n  HostName=example.com\n  Port=2022", "x")
        assertEquals("example.com", c.hostName)
        assertEquals(2022, c.port)
    }
}
