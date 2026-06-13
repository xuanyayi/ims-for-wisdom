//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress

class SipUpdateSdpTests {
    @Test
    fun `update sdp answer mirrors precondition qos and ends with crlf`() {
        val rtpSocket = DatagramSocket(0)
        try {
            val call = SipHandler.Call(
                outgoing = true,
                callHeaders = emptyMap(),
                sdp = ByteArray(0),
                audioCodec = SipAudioCodecs.AMR_WB,
                amrTrack = 98,
                amrTrackDesc = "rtpmap:98 AMR-WB/16000",
                dtmfTrack = 101,
                dtmfTrackDesc = "rtpmap:101 telephone-event/16000",
                rtpRemoteAddr = InetAddress.getByName("127.0.0.1"),
                rtpRemotePort = 14082,
                rtpSocket = rtpSocket,
                hasEarlyMedia = true,
                remoteContact = "sip:test@example.com",
            )
            val request = SipRequest(
                method = SipMethod.UPDATE,
                destination = "sip:+8613085738050@[2408:8556:2c06:47d:1:1:2f9b:f277]:59742;transport=tcp",
                headersParam = emptyMap(),
                autofill = false,
            )
            val attributes = listOf(
                "ptime:20",
                "maxptime:240",
                "rtpmap:98 AMR-WB/16000",
                "fmtp:98 octet-align=0;mode-change-capability=2;max-red=0",
                "rtpmap:101 telephone-event/16000",
                "fmtp:101 0-15",
                "curr:qos local sendrecv",
                "curr:qos remote none",
                "des:qos mandatory local sendrecv",
                "des:qos optional remote sendrecv",
                "content:g.3gpp.cat",
            )

            val answer = SipUpdateSdpAnswerBuilder.build(
                request = request,
                call = call,
                attributes = attributes,
                amrTrack = 98,
                amrTrackDesc = "rtpmap:98 AMR-WB/16000",
                amrFmtpAnswer = "fmtp:98 octet-align=0;mode-change-capability=2;max-red=0",
                dtmfTrack = 101,
                dtmfTrackDesc = "rtpmap:101 telephone-event/16000",
                localAddr = InetAddress.getByName("2408:8556:2c06:47d:1:1:2f9b:f277"),
            ).toString(Charsets.US_ASCII)

            require(answer.endsWith("\r\n"))
            require("o=- 1 3 IN IP6 2408:8556:2c06:47d:1:1:2f9b:f277\r\n" in answer)
            require("a=curr:qos local sendrecv\r\n" in answer)
            require("a=curr:qos remote sendrecv\r\n" in answer)
            require("a=des:qos optional local sendrecv\r\n" in answer)
            require("a=des:qos mandatory remote sendrecv\r\n" in answer)
            require("a=content:g.3gpp.cat\r\n" in answer)
            require("a=rtpmap:98 AMR-WB/16000\r\n" in answer)
            require("a=fmtp:101 0-15\r\n" in answer)
        } finally {
            rtpSocket.close()
        }
    }
}
