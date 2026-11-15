/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.net

import com.google.protobuf.ByteString
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.lublin.humla.Constants
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.protocol.HumlaUDPMessageListener

/**
 * The interim link's side of issues 16 and 17: the route changes only on
 * SetVoiceRoute, and the UDP format only at voice start. No test here opens
 * a socket: UDP needs both a started endpoint and a UDP route, and each case
 * withholds one of them.
 */
class LegacyVoiceLinkContractTest {
    private val tunnelled: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    private val link = LegacyVoiceLink(tunnelSink = { tunnelled.add(it) })
    private val packet = byteArrayOf(1, 2, 3)

    /** True if a voice packet sent now goes to the tunnel sink. */
    private fun routedToTunnel(): Boolean {
        val before = tunnelled.size
        link.sendVoice(packet, packet.size)
        return tunnelled.size > before
    }

    @Test
    fun routeFollowsOnlySetVoiceRoute() {
        try {
            // Tunnel until the transport says otherwise.
            assertTrue(routedToTunnel())
            link.submit(VoiceCommand.SetVoiceRoute(true))
            assertTrue(!routedToTunnel())
            link.submit(VoiceCommand.SetVoiceRoute(false))
            assertTrue(routedToTunnel())
            // Starting and stopping voice leave the route alone (the old link
            // reset it to UDP at start, issue 16). Route is tunnel here, so
            // start opens no socket.
            link.startVoice("127.0.0.1", 9, VoiceFormat.DEFAULT)
            assertTrue(routedToTunnel())
            link.stopVoice()
            assertTrue(routedToTunnel())
            link.submit(VoiceCommand.SetVoiceRoute(true))
            link.startVoiceWithoutUdp(VoiceFormat.DEFAULT)
            assertTrue(!routedToTunnel())
        } finally {
            link.close()
        }
    }

    @Test
    fun udpPingVersionDoesNotChangeTheFormat() {
        val voice = Collections.synchronizedList(ArrayList<List<DecodedVoiceFrame>>())
        val pings = Collections.synchronizedList(ArrayList<MumbleUDP.Ping>())
        link.addUdpHandler(object : HumlaUDPMessageListener.Stub() {
            override fun messageDecodedVoice(frames: List<DecodedVoiceFrame>) {
                voice.add(frames)
            }

            override fun messageProtobufPing(msg: MumbleUDP.Ping) {
                pings.add(msg)
            }
        })
        try {
            val v150 = Constants.toVersionV2(1, 5, 0)
            link.startVoiceWithoutUdp(VoiceFormat.fromServerVersion(v150, Constants.toLegacyVersion(v150)))
            assertEquals(UdpFormat.PROTOBUF, link.udpFormat)
            // A protobuf ping reporting a pre-1.5 server: reported, not applied.
            val ping = MumbleUDP.Ping.newBuilder()
                .setTimestamp(1)
                .setServerVersionV2(Constants.toVersionV2(1, 4, 0))
                .build()
            link.onUDPDataReceived(byteArrayOf(1) + ping.toByteArray())
            assertEquals(1, pings.size)
            assertEquals(UdpFormat.PROTOBUF, link.udpFormat)
            // Protobuf voice still parses after the ping.
            val audio = MumbleUDP.Audio.newBuilder()
                .setSenderSession(42)
                .setFrameNumber(7)
                .setOpusData(ByteString.copyFrom(byteArrayOf(0x0A, 0x0B)))
                .build()
            link.onUDPDataReceived(byteArrayOf(0) + audio.toByteArray())
            assertEquals(1, voice.size)
            assertEquals(42, voice[0][0].session)
        } finally {
            link.close()
        }
    }
}
