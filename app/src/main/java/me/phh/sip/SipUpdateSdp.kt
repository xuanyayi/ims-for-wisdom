package me.phh.sip

import android.telephony.Rlog
import java.net.Inet6Address
import java.net.InetAddress

internal data class UpdateSdpOffer(
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePort: Int,
    val offeredPayloads: Set<Int>,
    val attributes: List<String>,
)

internal data class UpdateSdpCallUpdateState(
    val answerSdp: ByteArray,
    val amrTrack: Int,
    val amrTrackDesc: String,
    val dtmfTrack: Int,
    val dtmfTrackDesc: String,
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePort: Int,
    val remoteContact: String,
)

internal object SipUpdateSdpOfferParser {
    fun parse(
        request: SipRequest,
        requestCallId: String,
        requestCseq: String,
        logTag: String,
    ): UpdateSdpOffer? {
        val sdp = request.body
            .toString(Charsets.UTF_8)
            .split("[\\r\\n]+".toRegex())
            .filter { it.isNotBlank() }

        Rlog.d(logTag, "Handling UPDATE SDP offer: callId=$requestCallId cseq=$requestCseq sdp=$sdp")

        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=") } ?: return null
            return v.substring(2)
        }

        val sdpConnectionData = sdpElement("c")
        val sdpMedia = sdpElement("m")
        if (sdpConnectionData == null || sdpMedia == null) {
            Rlog.w(logTag, "Rejecting UPDATE without usable c=/m= SDP: callId=$requestCallId cseq=$requestCseq")
            return null
        }

        val rtpRemote = sdpConnectionData.split(" ").getOrNull(2)
        val rtpRemoteAddr = rtpRemote?.let { InetAddress.getByName(it) }
        val mediaParts = sdpMedia.trim().split("\\s+".toRegex())
        val rtpRemotePort = mediaParts.getOrNull(1)?.toIntOrNull()
        val offeredPayloads = mediaParts.drop(3).mapNotNull { it.toIntOrNull() }.toSet()

        if (rtpRemoteAddr == null || rtpRemotePort == null || offeredPayloads.isEmpty()) {
            Rlog.w(
                logTag,
                "Rejecting UPDATE with incomplete media address/payloads: " +
                    "callId=$requestCallId cseq=$requestCseq c=$sdpConnectionData m=$sdpMedia",
            )
            return null
        }

        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = logTag,
            context = "remote SDP ${request.method} callId=${request.callIdOrEmpty()}",
            sdp = sdp,
        )

        return UpdateSdpOffer(
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            offeredPayloads = offeredPayloads,
            attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2) },
        )
    }
}


internal data class UpdateSdpMediaSelection(
    val selectedAudioCodec: NegotiatedAudioCodec,
    val amrTrack: Int,
    val amrTrackDesc: String,
    val amrFmtpAnswer: String,
    val dtmfTrack: Int,
    val dtmfTrackDesc: String,
)

internal object SipUpdateSdpCallUpdate {
    fun state(
        request: SipRequest,
        answerSdp: ByteArray,
        amrTrack: Int,
        amrTrackDesc: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: Int,
        fallbackRemoteContact: String,
        extractDestinationFromContact: (String) -> String,
    ): UpdateSdpCallUpdateState =
        UpdateSdpCallUpdateState(
            answerSdp = answerSdp,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            remoteContact = request.headers["contact"]?.getOrNull(0)
                ?.let { extractDestinationFromContact(it) }
                ?: fallbackRemoteContact,
        )
}


internal object SipUpdateSdpMediaSelector {
    fun select(
        logTag: String,
        attributes: List<String>,
        offeredPayloads: Set<Int>,
        selectedAudioCodec: NegotiatedAudioCodec,
        requestCallId: String,
    ): UpdateSdpMediaSelection? {
        // Keep the selected speech payload first in SDP answers. Sorting payload IDs can
        // put telephone-event before AMR-WB, e.g. m=audio ... 96 104, which some
        // IMS cores reject as an offer/answer error during precondition UPDATE.
        val amr = SipUpdateSdpAnswerNegotiator.lookTrackMatching(
            logTag = logTag,
            attributes = attributes,
            offeredPayloads = offeredPayloads,
            codec = SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
            notAdditional = "octet-align=1",
        )
        if (amr == null) {
            Rlog.w(
                logTag,
                "Rejecting UPDATE: no compatible ${SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec)} " +
                    "payload in offer callId=$requestCallId offered=$offeredPayloads",
            )
            return null
        }
        val (amrTrack, amrTrackDesc) = amr
        val amrFmtpAnswer = SipUpdateSdpAnswerNegotiator.trackRequirements(attributes, amrTrack)
            ?: SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        val dtmf = SipUpdateSdpAnswerNegotiator.lookTrackMatching(
            logTag = logTag,
            attributes = attributes,
            offeredPayloads = offeredPayloads,
            codec = SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec),
        )
        if (dtmf == null) {
            Rlog.w(
                logTag,
                "Rejecting UPDATE: no compatible ${SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)} " +
                    "payload in offer callId=$requestCallId offered=$offeredPayloads",
            )
            return null
        }
        val (dtmfTrack, dtmfTrackDesc) = dtmf

        return UpdateSdpMediaSelection(
            selectedAudioCodec = selectedAudioCodec,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            amrFmtpAnswer = amrFmtpAnswer,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
        )
    }
}

internal object SipUpdateSdpAnswerNegotiator {
    fun trackRequirements(
        attributes: List<String>,
        track: Int,
    ): String? {
        return attributes.firstOrNull { it.startsWith("fmtp:$track") }
    }

    fun lookTrackMatching(
        logTag: String,
        attributes: List<String>,
        offeredPayloads: Set<Int>,
        codec: String,
        additional: String = "",
        notAdditional: String = "",
    ): Pair<Int, String>? {
        val maps = attributes.filter { it.startsWith("rtpmap:") && it.contains(codec) }
        val matches = maps.mapNotNull { m ->
            val track = m.split("[: ]+".toRegex()).getOrNull(1)?.toIntOrNull()
            if (track != null && offeredPayloads.contains(track)) Pair(track, m) else null
        }
        val sorted = matches.sortedBy { m ->
            val fmtp = trackRequirements(attributes, m.first).orEmpty()
            when {
                // Our RTP encoder currently sends AMR-NB bandwidth-efficient frames.
                // SDP without octet-align defaults to octet-align=0, so prefer that
                // over octet-align=1 when carriers offer both forms in UPDATE.
                codec.startsWith("AMR") && fmtp.contains("octet-align=1", ignoreCase = true) -> 100
                codec.startsWith("AMR") && fmtp.isEmpty() -> 0
                notAdditional.isNotEmpty() && fmtp.contains(notAdditional, ignoreCase = true) -> 90
                additional.isNotEmpty() && fmtp.contains(additional, ignoreCase = true) -> 0
                else -> 10
            }
        }
        Rlog.d(logTag, "UPDATE matching $codec offered=$offeredPayloads got=$sorted")
        return sorted.firstOrNull()
    }
}

internal object SipUpdateSdpAnswerBuilder {
    private fun hasQosSide(line: String, side: String): Boolean =
        line.split("\\s+".toRegex()).any { it.equals(side, ignoreCase = true) }

    private fun mirrorQosPerspective(line: String, from: String, to: String): String =
        line.split("\\s+".toRegex())
            .map { token -> if (token.equals(from, ignoreCase = true)) to else token }
            .joinToString(" ")

    private fun qosAnswerLines(attributes: List<String>): List<String> {
        val hasPrecondition = attributes.any { attr ->
            attr.startsWith("curr:qos", ignoreCase = true) ||
                attr.startsWith("des:qos", ignoreCase = true) ||
                attr.startsWith("conf:qos", ignoreCase = true)
        }
        if (!hasPrecondition) return emptyList()

        val offerCurrentLocal = attributes.firstOrNull {
            it.startsWith("curr:qos", ignoreCase = true) && hasQosSide(it, "local")
        }
        val offerDesiredLocal = attributes.firstOrNull {
            it.startsWith("des:qos", ignoreCase = true) && hasQosSide(it, "local")
        }
        val offerDesiredRemote = attributes.firstOrNull {
            it.startsWith("des:qos", ignoreCase = true) && hasQosSide(it, "remote")
        }

        val currentRemote = offerCurrentLocal
            ?.let { mirrorQosPerspective(it, from = "local", to = "remote") }
            ?: "curr:qos remote sendrecv"
        val desiredLocal = offerDesiredRemote
            ?.let { mirrorQosPerspective(it, from = "remote", to = "local") }
            ?: "des:qos mandatory local sendrecv"
        val desiredRemote = offerDesiredLocal
            ?.let { mirrorQosPerspective(it, from = "local", to = "remote") }
            ?: "des:qos mandatory remote sendrecv"

        return listOf(
            "a=curr:qos local sendrecv",
            "a=$currentRemote",
            "a=$desiredLocal",
            "a=$desiredRemote",
            "a=conf:qos remote sendrecv",
        )
    }

    fun build(
        request: SipRequest,
        call: SipHandler.Call,
        attributes: List<String>,
        amrTrack: Int,
        amrTrackDesc: String,
        amrFmtpAnswer: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        localAddr: InetAddress,
    ): ByteArray {
        val selectedAudioCodec = call.audioCodec
        val allTracks = listOf(amrTrack, dtmfTrack)
        val ipType = if (localAddr is Inet6Address) "IP6" else "IP4"
        val sdpVersion = call.localSdpVersion.incrementAndGet()
        val remotePtime = attributes.firstOrNull { it.startsWith("ptime:") } ?: "ptime:20"
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:240"
        val dtmfFmtpAnswer = attributes.firstOrNull { it.startsWith("fmtp:$dtmfTrack") }
            ?: "fmtp:$dtmfTrack 0-15"
        val contentLines = attributes
            .filter { it.startsWith("content:", ignoreCase = true) }
            .map { "a=$it" }
        val qosLines = qosAnswerLines(attributes)
        val sdpBandwidthAs = SipAudioCodecNegotiator.sdpBandwidthAsKbps(selectedAudioCodec)

        val answerSdpLines = mutableListOf(
            "v=0",
            "o=- 1 $sdpVersion IN $ipType ${localAddr.hostAddress}",
            "s=phh voice call",
            "c=IN $ipType ${localAddr.hostAddress}",
            "b=AS:$sdpBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "t=0 0",
            "m=audio ${call.rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
            "b=AS:$sdpBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "a=$amrTrackDesc",
            "a=$remotePtime",
            "a=$remoteMaxptime",
            "a=$dtmfTrackDesc",
            "a=$amrFmtpAnswer",
            "a=$dtmfFmtpAnswer",
        )
        answerSdpLines += contentLines
        answerSdpLines += qosLines
        answerSdpLines += "a=sendrecv"

        return (answerSdpLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }
}

internal object SipUpdateRtpEndpointConnector {
    fun connectIfNeeded(
        call: SipHandler.Call,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: Int,
        requestCallId: String,
        logTag: String,
    ) {
        try {
            if (!call.rtpSocket.isConnected ||
                call.rtpSocket.inetAddress != rtpRemoteAddr ||
                call.rtpSocket.port != rtpRemotePort) {
                call.rtpSocket.connect(rtpRemoteAddr, rtpRemotePort)
                Rlog.d(
                    logTag,
                    "UPDATE connected RTP socket to ${rtpRemoteAddr}:${rtpRemotePort} " +
                        "local=${call.rtpSocket.localAddress}:${call.rtpSocket.localPort} callId=$requestCallId",
                )
            }
        } catch (t: Throwable) {
            Rlog.w(logTag, "Failed to connect RTP socket from UPDATE to ${rtpRemoteAddr}:${rtpRemotePort} callId=$requestCallId", t)
        }
    }
}
