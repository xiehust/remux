package dev.remux.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProtocolTest {
    @Test fun authRoundTrip() {
        val raw = TunnelProtocol.encode(MsgType.AUTH, Auth(token = "sekret"))
        val env = TunnelProtocol.decodeEnvelope(raw)
        assertEquals(MsgType.AUTH, env.type)
        val auth = TunnelProtocol.decodeData<Auth>(env)
        assertEquals("sekret", auth.token)
    }

    @Test fun openRequestRoundTrip() {
        val raw = TunnelProtocol.encode(MsgType.OPEN, Open(deviceId = "dev1"))
        val env = TunnelProtocol.decodeEnvelope(raw)
        assertEquals(MsgType.OPEN, env.type)
        assertEquals("dev1", TunnelProtocol.decodeData<Open>(env).deviceId)
    }

    @Test fun devicesRoundTrip() {
        val devices = Devices(
            listOf(
                DeviceInfo("dev1", "ec2", "linux", true),
                DeviceInfo("dev2", "mac", "darwin", false),
            ),
        )
        val raw = TunnelProtocol.encode(MsgType.DEVICES, devices)
        val env = TunnelProtocol.decodeEnvelope(raw)
        val decoded = TunnelProtocol.decodeData<Devices>(env)
        assertEquals(2, decoded.devices.size)
        assertEquals("dev1", decoded.devices[0].deviceId)
        assertTrue(decoded.devices[0].online)
        assertEquals("darwin", decoded.devices[1].platform)
    }

    @Test fun openedRoundTrip() {
        val raw = TunnelProtocol.encode(MsgType.OPENED, Opened("sess-abc"))
        val env = TunnelProtocol.decodeEnvelope(raw)
        assertEquals("sess-abc", TunnelProtocol.decodeData<Opened>(env).sessionId)
    }

    @Test fun bareMessageHasNoData() {
        val raw = TunnelProtocol.encodeBare(MsgType.LIST)
        val env = TunnelProtocol.decodeEnvelope(raw)
        assertEquals(MsgType.LIST, env.type)
        assertEquals(null, env.data)
    }

    @Test fun errorRoundTrip() {
        val raw = TunnelProtocol.encode(MsgType.ERROR, ErrorMsg("bad token"))
        val env = TunnelProtocol.decodeEnvelope(raw)
        assertEquals("bad token", TunnelProtocol.decodeData<ErrorMsg>(env).msg)
    }
}
