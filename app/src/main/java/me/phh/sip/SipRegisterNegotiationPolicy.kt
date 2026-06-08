package me.phh.sip

/**
 * Generic REGISTER negotiation helpers.
 *
 * Some IMS cores challenge the initial home-realm REGISTER with a shorter operator
 * registrar realm, for example realm="ims.example.com" while our default target is
 * ims.mncXXX.mccYYY.3gppnetwork.org.  The authenticated REGISTER must then use the
 * challenged registrar authority consistently as both the Request-URI and digest URI.
 *
 * This is intentionally not carrier-gated: the network-provided AKA challenge is the
 * source of truth, but we only accept syntactically safe host-like realms.
 */
object SipRegisterNegotiationPolicy {
    private val SAFE_HOST_RE = Regex("^[A-Za-z0-9.-]+$")
    /*
     * Some networks challenge IMS REGISTER with an EPC/AKA realm such as
     * epc.mncXXX.mccYYY.3gppnetwork.org. That realm is valid for the
     * Authorization realm, but it is not necessarily the SIP registrar
     * authority. Do not promote it to the protected REGISTER Request-URI or
     * digest URI, otherwise carriers that expect the IMS home domain there can
     * reject the authenticated REGISTER with 403.
     */
    private fun isEpcAuthRealm(realm: String): Boolean =
        realm.startsWith("epc.", ignoreCase = true) &&
            realm.endsWith(".3gppnetwork.org", ignoreCase = true)


    fun challengedRegistrarRealm(defaultRealm: String, challengeRealm: String?): String {
        val candidate = challengeRealm
            ?.trim()
            ?.trim('"')
            ?.lowercase()
            ?: return defaultRealm

        if (candidate.isEmpty()) return defaultRealm
        if (candidate.equals(defaultRealm, ignoreCase = true)) return defaultRealm
        if (!candidate.contains('.')) return defaultRealm
        if (!SAFE_HOST_RE.matches(candidate)) return defaultRealm
        if (isEpcAuthRealm(candidate)) return defaultRealm
        if (candidate.startsWith(".") || candidate.endsWith(".")) return defaultRealm
        if (candidate.contains("..")) return defaultRealm

        return candidate
    }

    fun selectedSecurityClientHeader(
        securityServerParams: Map<String, String>,
        ipsecSettings: SipIpsecSettings,
        clientPort: Int,
        serverPort: Int,
    ): String? {
        val selectedAlg = securityServerParams["alg"] ?: return null
        val selectedEalg = securityServerParams["ealg"] ?: "null"

        return SipSecurityClientHeader.build(
            ipsecSettings = ipsecSettings,
            clientPort = clientPort,
            serverPort = serverPort,
            algs = listOf(selectedAlg),
            ealgs = listOf(selectedEalg),
        )
    }
}
