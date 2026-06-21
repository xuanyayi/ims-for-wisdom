package me.phh.sip

import android.telephony.Rlog

internal data class OutgoingInviteRequestContext(
    val request: SipRequest,
    val baseHeaders: Map<String, List<String>>,
    val targetUri: String,
    val telUri: String,
    val normalizedPhoneNumber: String,
)

private data class OutgoingInviteBaseRequestContext(
    val normalizedPhoneNumber: String,
    val telUri: String,
    val sipInstance: String,
    val localEndpoint: String,
    val transport: String,
    val baseHeaders: Map<String, List<String>>,
)

private data class OutgoingInviteCarrierRequestShape(
    val targetUri: String,
    val headers: Map<String, List<String>>,
)

internal object SipOutgoingInviteRequestBuilder {

    // Scoped short service TEL URI policy.
    //
    // Local TEL numbers need a phone-context. Some IMS cores reject plain local
    // short-code targets such as tel:121 with "Local phone number without phone
    // context". Keep E.164 targets unchanged, and keep the known Vodafone TR
    // service-number exception plain because that carrier rejected the generic
    // MCC/MNC context for 542.
    private fun normalizedMncForPhoneContext(mnc: String): String =
        mnc.trim().trimStart('0').ifBlank { "0" }.padStart(3, '0')

    private fun phoneContextForLocalTelUri(realm: String, mcc: String, mnc: String): String {
        val candidate = realm.trim()
            .removePrefix("sip:")
            .substringBefore(";")
            .substringAfter("@")
            .trim()

        if (candidate.isNotBlank() &&
            candidate.none { it.isWhitespace() || it == '<' || it == '>' || it == '"' } &&
            !candidate.contains(":")) {
            return candidate
        }

        return "ims.mnc${normalizedMncForPhoneContext(mnc)}.mcc${mcc.trim().padStart(3, '0')}.3gppnetwork.org"
    }

    // Kept separate because Vodafone TR outgoing PANI policy uses this too.
    private fun isVodafoneTurkeyCarrier(mcc: String, mnc: String): Boolean =
        mcc.trim() == "286" && normalizedMncForPhoneContext(mnc) == "002"

    private fun shouldKeepShortServicePlainTel(
        normalizedPhoneNumber: String,
        mcc: String,
        mnc: String,
    ): Boolean {
        // Confirmed compatibility exception: Vodafone TR service code 542 worked
        // as plain tel:<digits> and broke with the generic MCC/MNC phone-context.
        return isVodafoneTurkeyCarrier(mcc, mnc) &&
            normalizedPhoneNumber.length in 3..6 &&
            normalizedPhoneNumber.all { it.isDigit() }
    }

    private fun shortServiceTelUri(
        normalizedPhoneNumber: String,
        mcc: String,
        mnc: String,
        realm: String,
    ): String? {
        if (normalizedPhoneNumber.length !in 3..6 ||
            !normalizedPhoneNumber.all { it.isDigit() }) {
            return null
        }

        val fallbackEmergencyShortCodes = setOf(
            "000", // AU and others
            "110", // DE police and others
            "112", // EU/common emergency
            "118",
            "119",
            "911", // NANP/common emergency
            "999", // UK/common emergency
        )

        // If an emergency-like code reaches this normal MMTel path anyway, do
        // not add a phone-context here. The real emergency path should handle it.
        if (normalizedPhoneNumber in fallbackEmergencyShortCodes) {
            return "tel:$normalizedPhoneNumber"
        }

        if (shouldKeepShortServicePlainTel(normalizedPhoneNumber, mcc, mnc)) {
            return "tel:$normalizedPhoneNumber"
        }

        return "tel:$normalizedPhoneNumber;phone-context=${phoneContextForLocalTelUri(realm, mcc, mnc)}"
    }

    // Vodafone TR carrier policy helpers.

    // Vodafone TR outgoing PANI policy.
    private fun vodafoneTurkeyOutgoingPaniHeaders(
        mcc: String,
        mnc: String,
        registrationTech: Int,
    ): Map<String, List<String>> {
        if (!isVodafoneTurkeyCarrier(mcc, mnc)) {
            return emptyMap()
        }

        val paniValue = when (registrationTech) {
            android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN -> "IEEE-802.11"
            android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE -> "3GPP-E-UTRAN-FDD"
            else -> null
        }

        return paniValue
            ?.let { mapOf("P-Access-Network-Info" to listOf(it)) }
            ?: emptyMap()
    }


    fun build(
        logTag: String,
        phoneNumber: String,
        outgoingInviteBody: ByteArray,
        normalizedPhoneNumber: String,
        mcc: String,
        mnc: String,
        realm: String,
        registrationTech: Int,
        mySip: String,
        myTel: String,
        imsi: String,
        imei: String,
        commonHeaders: Map<String, List<String>>,
        localEndpoint: String,
        transport: String,
        sessionExpiresSeconds: Int,
        minSeSeconds: Int,
        generatedCallIdHeaders: Map<String, List<String>>,
        singtelStockOutgoingCarrier: Boolean,
        chinaUnicomStockOutgoingCarrier: Boolean,
        singtelPublicSipUri: (String) -> String,
    ): OutgoingInviteRequestContext {
        val baseRequestContext = buildBaseRequestContext(
            logTag = logTag,
            phoneNumber = phoneNumber,
            normalizedPhoneNumber = normalizedPhoneNumber,
            mcc = mcc,
            mnc = mnc,
            realm = realm,
            registrationTech = registrationTech,
            mySip = mySip,
            myTel = myTel,
            imei = imei,
            commonHeaders = commonHeaders,
            localEndpoint = localEndpoint,
            transport = transport,
            sessionExpiresSeconds = sessionExpiresSeconds,
            minSeSeconds = minSeSeconds,
            generatedCallIdHeaders = generatedCallIdHeaders,
        )
        val carrierRequestShape = buildCarrierRequestShape(
            normalizedPhoneNumber = baseRequestContext.normalizedPhoneNumber,
            telUri = baseRequestContext.telUri,
            baseHeaders = baseRequestContext.baseHeaders,
            sipInstance = baseRequestContext.sipInstance,
            localEndpoint = baseRequestContext.localEndpoint,
            transport = baseRequestContext.transport,
            mySip = mySip,
            myTel = myTel,
            imsi = imsi,
            mcc = mcc,
            mnc = mnc,
            commonHeaders = commonHeaders,
            singtelStockOutgoingCarrier = singtelStockOutgoingCarrier,
            chinaUnicomStockOutgoingCarrier = chinaUnicomStockOutgoingCarrier,
            singtelPublicSipUri = singtelPublicSipUri,
        )
        return buildRequestContext(
            outgoingInviteBody = outgoingInviteBody,
            baseRequestContext = baseRequestContext,
            carrierRequestShape = carrierRequestShape,
        )
    }

    private fun buildBaseRequestContext(
        logTag: String,
        phoneNumber: String,
        normalizedPhoneNumber: String,
        mcc: String,
        mnc: String,
        realm: String,
        registrationTech: Int,
        mySip: String,
        myTel: String,
        imei: String,
        commonHeaders: Map<String, List<String>>,
        localEndpoint: String,
        transport: String,
        sessionExpiresSeconds: Int,
        minSeSeconds: Int,
        generatedCallIdHeaders: Map<String, List<String>>,
    ): OutgoingInviteBaseRequestContext {
        val to = shortServiceTelUri(
            normalizedPhoneNumber = normalizedPhoneNumber,
            mcc = mcc,
            mnc = mnc,
            realm = realm,
        ) ?: if (normalizedPhoneNumber.startsWith("+")) {
            // Global TEL URIs must stand on their own. Adding phone-context to +E.164
            // numbers makes some IMS cores drop the INVITE without any SIP response.
            "tel:$normalizedPhoneNumber"
        } else {
            // Short service numbers were handled above. Other local numbers
            // keep the generic IMS phone-context.
            "tel:$normalizedPhoneNumber;phone-context=${phoneContextForLocalTelUri(realm, mcc, mnc)}"
        }
        Rlog.d(logTag, "Outgoing dial target raw=$phoneNumber normalized=$normalizedPhoneNumber uri=$to")
        val sipInstance = "<urn:gsma:imei:${imei.substring(0, 8)}-${imei.substring(8, 14)}-0>"
        val contactTel =
            """<sip:$myTel@$localEndpoint;transport=$transport>;expires=7200;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
        val vodafoneTurkeyPaniHeaders =
            vodafoneTurkeyOutgoingPaniHeaders(mcc, mnc, registrationTech)

        val myHeaders = commonHeaders +
            """
                From: <$mySip>
                To: <$to>
                P-Preferred-Identity: <$mySip>
                P-Asserted-Identity: <$mySip>
                Expires: 7200
                Require: sec-agree
                Proxy-Require: sec-agree
                Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                P-Early-Media: supported
                Content-Type: application/sdp
                Session-Expires: $sessionExpiresSeconds
                Supported: 100rel, replaces, timer, precondition
                Accept: application/sdp
                Min-SE: $minSeSeconds
                Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
                P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                Contact: $contactTel
                """.toSipHeadersMap() + vodafoneTurkeyPaniHeaders +
            generatedCallIdHeaders - "p-asserted-identity"
        // P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
        // Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"

        return OutgoingInviteBaseRequestContext(
            normalizedPhoneNumber = normalizedPhoneNumber,
            telUri = to,
            sipInstance = sipInstance,
            localEndpoint = localEndpoint,
            transport = transport,
            baseHeaders = myHeaders,
        )
    }

    private fun buildCarrierRequestShape(
        normalizedPhoneNumber: String,
        telUri: String,
        baseHeaders: Map<String, List<String>>,
        sipInstance: String,
        localEndpoint: String,
        transport: String,
        mySip: String,
        myTel: String,
        imsi: String,
        mcc: String,
        mnc: String,
        commonHeaders: Map<String, List<String>>,
        singtelStockOutgoingCarrier: Boolean,
        chinaUnicomStockOutgoingCarrier: Boolean,
        singtelPublicSipUri: (String) -> String,
    ): OutgoingInviteCarrierRequestShape {
        val singtelStockOutgoingTargetUri = if (singtelStockOutgoingCarrier) {
            singtelPublicSipUri(normalizedPhoneNumber)
        } else {
            telUri
        }
        val chinaUnicomOutgoingTargetUri =
            if (chinaUnicomStockOutgoingCarrier && normalizedPhoneNumber.all { it.isDigit() }) {
                "tel:$normalizedPhoneNumber;phone-context=ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
            } else {
                singtelStockOutgoingTargetUri
            }

        val singtelStockOutgoingHeaders = if (singtelStockOutgoingCarrier) {
            val singtelStockIdentity = singtelPublicSipUri(myTel)
            val singtelStockFromTag = baseHeaders["from"]?.firstOrNull()
                ?.substringAfter(";tag=", missingDelimiterValue = "")
                ?.substringBefore(";")
                ?.takeIf { it.isNotBlank() }
                ?: "phh${System.currentTimeMillis().toString(16)}"
            val singtelStockContact = "<sip:$imsi@$localEndpoint;transport=$transport>;expires=7200;" +
                "+sip.instance=\"$sipInstance\";audio;+g.3gpp.accesstype=\"cellular\";" +
                "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.3gpp.smsip"
            val singtelCompactContact = "<sip:$imsi@$localEndpoint;transport=$transport>"
            val singtelStockPaniValue = commonHeaders.entries
                .firstOrNull { it.key.equals("p-access-network-info", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?: "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=5250102C6B611D01"

            val singtelStockBaseHeaders = baseHeaders.filterKeys { key ->
                key.equals("via", ignoreCase = true) ||
                    key.equals("max-forwards", ignoreCase = true) ||
                    key.equals("user-agent", ignoreCase = true) ||
                    key.equals("route", ignoreCase = true) ||
                    key.equals("call-id", ignoreCase = true) ||
                    key.equals("security-verify", ignoreCase = true) ||
                    key.equals("proxy-require", ignoreCase = true)
            }

            // Direct stock-like SingTel INVITE: whitelist only the dynamic dialog and
            // security headers, then add the originating MMTEL shape explicitly. Do not
            // carry the generic TEL-URI identity headers from main.
            /*
             * Keep the originating SingTel header set intentionally small.
             * Security-Verify and Content-Type are required/accepted, but
             * optional identity/access/capability headers make the first
             * protected INVITE large enough to be dropped by this IMS path.
             */
            singtelStockBaseHeaders + """
                From: <$singtelStockIdentity>;tag=$singtelStockFromTag
                To: <$singtelStockOutgoingTargetUri>
                Contact: $singtelCompactContact
                P-Preferred-Identity: <$singtelStockIdentity>
                Expires: 7200
                Require: sec-agree
                Proxy-Require: sec-agree
                Content-Type: application/sdp
                Allow: INVITE, ACK, CANCEL, BYE, OPTIONS
                Supported: sec-agree
                Request-Disposition: no-fork
                P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                CSeq: 1 INVITE
            """.toSipHeadersMap()
        } else {
            baseHeaders
        }

        val chinaUnicomOutgoingHeaders = if (chinaUnicomStockOutgoingCarrier) {
            val stockIdentity = mySip
            val stockFromTag = baseHeaders["from"]?.firstOrNull()
                ?.substringAfter(";tag=", missingDelimiterValue = "")
                ?.substringBefore(";")
                ?.takeIf { it.isNotBlank() }
                ?: "phh${System.currentTimeMillis().toString(16)}"
            val stockContact = "<sip:$imsi@$localEndpoint;transport=$transport>"
            val stockBaseHeaders = baseHeaders.filterKeys { key ->
                key.equals("via", ignoreCase = true) ||
                    key.equals("max-forwards", ignoreCase = true) ||
                    key.equals("user-agent", ignoreCase = true) ||
                    key.equals("route", ignoreCase = true) ||
                    key.equals("call-id", ignoreCase = true) ||
                    key.equals("security-verify", ignoreCase = true) ||
                    key.equals("proxy-require", ignoreCase = true)
            }

            stockBaseHeaders + """
                From: <$stockIdentity>;tag=$stockFromTag
                To: <$chinaUnicomOutgoingTargetUri>
                Contact: $stockContact
                P-Preferred-Identity: <$stockIdentity>
                Require: sec-agree
                Proxy-Require: sec-agree
                Content-Type: application/sdp
                Allow: INVITE, ACK, CANCEL, BYE, UPDATE, PRACK, OPTIONS
                Supported: 100rel, sec-agree, precondition
                CSeq: 1 INVITE
            """.toSipHeadersMap()
        } else {
            singtelStockOutgoingHeaders
        }

        return OutgoingInviteCarrierRequestShape(
            targetUri = chinaUnicomOutgoingTargetUri,
            headers = chinaUnicomOutgoingHeaders,
        )
    }

    private fun buildRequestContext(
        outgoingInviteBody: ByteArray,
        baseRequestContext: OutgoingInviteBaseRequestContext,
        carrierRequestShape: OutgoingInviteCarrierRequestShape,
    ): OutgoingInviteRequestContext {
        val request =
            SipRequest(
                SipMethod.INVITE,
                carrierRequestShape.targetUri,
                carrierRequestShape.headers,
                outgoingInviteBody
            )

        return OutgoingInviteRequestContext(
            request = request,
            baseHeaders = baseRequestContext.baseHeaders,
            targetUri = carrierRequestShape.targetUri,
            telUri = baseRequestContext.telUri,
            normalizedPhoneNumber = baseRequestContext.normalizedPhoneNumber,
        )
    }
}
