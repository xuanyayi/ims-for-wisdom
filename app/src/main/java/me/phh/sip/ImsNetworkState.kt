// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import java.net.Inet6Address
import java.net.InetAddress

internal sealed class ImsNetworkEndpointResolution {
    data class Success(
        val pcscfAddr: InetAddress,
        val localAddr: InetAddress,
    ) : ImsNetworkEndpointResolution()

    object WaitingForPcscf : ImsNetworkEndpointResolution()
    object NoLocalAddress : ImsNetworkEndpointResolution()
}

internal object ImsNetworkState {
    fun registrationTechName(tech: Int): String =
        when (tech) {
            REGISTRATION_TECH_IWLAN -> "IWLAN"
            REGISTRATION_TECH_LTE -> "LTE"
            else -> "unknown($tech)"
        }

    fun detectRegistrationTech(
        connectivityManager: ConnectivityManager,
        network: Network?,
        lp: LinkProperties,
    ): Int {
        val iface = lp.interfaceName ?: ""
        if (iface.startsWith("ipsec", ignoreCase = true)) {
            return REGISTRATION_TECH_IWLAN
        }

        val caps = if (network != null) {
            try {
                connectivityManager.getNetworkCapabilities(network)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            REGISTRATION_TECH_IWLAN
        } else {
            REGISTRATION_TECH_LTE
        }
    }

    fun getPcscfServers(lp: LinkProperties): List<InetAddress> {
        return (lp.javaClass.getMethod("getPcscfServers").invoke(lp) as List<*>)
            .filterIsInstance<InetAddress>()
            .sortedBy { if (it is Inet6Address) 0 else 1 }
    }

    fun getImsLocalAddress(lp: LinkProperties): InetAddress? {
        return lp.linkAddresses
            .map { it.address }
            .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
            .sortedBy { if (it is Inet6Address) 0 else 1 }
            .firstOrNull()
    }

    fun resolveEndpoint(
        tag: String,
        network: Network,
        lp: LinkProperties,
        mnc: String,
        mcc: String,
        mncDiscoveryCandidates: List<String>,
        isimPcscfCandidates: List<String>,
        preferUdp: Boolean,
    ): ImsNetworkEndpointResolution {
        val pcscfs = getPcscfServers(lp)
        val pcscf = if (pcscfs.isNotEmpty()) {
            pcscfs[0]
        } else {
            val dnsFallback = resolveFallbackPcscf(
                tag,
                network,
                listOf(mnc) + mncDiscoveryCandidates,
                mcc,
                isimPcscfCandidates,
                preferUdp,
            )

            if (dnsFallback != null) {
                Rlog.w(tag, "No P-CSCF from RIL, using fallback: $dnsFallback")
                dnsFallback
            } else {
                Rlog.w(tag, "No P-CSCF and all fallbacks failed, waiting for onLinkPropertiesChanged")
                return ImsNetworkEndpointResolution.WaitingForPcscf
            }
        }

        val localAddr = getImsLocalAddress(lp)
        if (localAddr == null) {
            Rlog.w(tag, "No usable local address on IMS link properties")
            return ImsNetworkEndpointResolution.NoLocalAddress
        }

        return ImsNetworkEndpointResolution.Success(pcscf, localAddr)
    }

    private fun resolveFallbackPcscf(
        tag: String,
        network: Network,
        mncCandidates: List<String>,
        mcc: String,
        isimPcscfCandidates: List<String>,
        preferUdp: Boolean,
    ): InetAddress? {
        val normalizedMncCandidates = mncCandidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val discoveredPcscfs = try {
            ImsDnsDiscovery.discoverPcscf(tag, network, normalizedMncCandidates, mcc, preferUdp)
        } catch (t: Throwable) {
            Rlog.w(tag, "P-CSCF DNS discovery failed", t)
            emptyList()
        }

        if (discoveredPcscfs.isNotEmpty()) {
            Rlog.w(tag, "P-CSCF DNS discovery returned addresses=$discoveredPcscfs")
            return discoveredPcscfs[0]
        }

        val candidates = buildList {
            addAll(isimPcscfCandidates)
            for (mnc in normalizedMncCandidates) {
                add("pcscf.ims.mnc${mnc}.mcc${mcc}.pub.3gppnetwork.org")
                add("pcscf.ims.mnc${mnc}.mcc${mcc}.3gppnetwork.org")
                add("pcscf.mnc${mnc}.mcc${mcc}.pub.3gppnetwork.org")
                add("pcscf.mnc${mnc}.mcc${mcc}.3gppnetwork.org")
                add("p-cscf.ims.mnc${mnc}.mcc${mcc}.pub.3gppnetwork.org")
                add("p-cscf.ims.mnc${mnc}.mcc${mcc}.3gppnetwork.org")
                add("ims.mnc${mnc}.mcc${mcc}.pub.3gppnetwork.org")
                add("ims.mnc${mnc}.mcc${mcc}.3gppnetwork.org")
            }
            android.os.SystemProperties
                .get("persist.ims.pcscf_fallback", "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { add(it) }
        }.distinct()

        for (candidate in candidates) {
            val resolved = try {
                network.getAllByName(candidate)
            } catch (t: Throwable) {
                Rlog.w(tag, "P-CSCF fallback resolve failed candidate=$candidate", t)
                emptyArray()
            }
                .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
                .sortedBy { if (it is Inet6Address) 0 else 1 }

            if (resolved.isNotEmpty()) {
                Rlog.w(tag, "P-CSCF fallback resolved candidate=$candidate addresses=$resolved")
                return resolved[0]
            }

            Rlog.w(tag, "P-CSCF fallback returned no usable address candidate=$candidate")
        }

        return null
    }

    fun ratName(rat: Int): String =
        when (rat) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
            else -> "rat($rat)"
        }

    fun isRatReadyForImsNetworkRequest(
        tag: String,
        telephonyManager: TelephonyManager,
    ): Boolean {
        val dataRat = try {
            telephonyManager.dataNetworkType
        } catch (t: Throwable) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }

        val voiceRat = try {
            telephonyManager.voiceNetworkType
        } catch (t: Throwable) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }

        val ready =
            dataRat == TelephonyManager.NETWORK_TYPE_LTE ||
                dataRat == TelephonyManager.NETWORK_TYPE_NR ||
                dataRat == TelephonyManager.NETWORK_TYPE_IWLAN ||
                voiceRat == TelephonyManager.NETWORK_TYPE_LTE ||
                voiceRat == TelephonyManager.NETWORK_TYPE_NR

        Rlog.d(
            tag,
            "IMS network request RAT gate: data=${ratName(dataRat)} voice=${ratName(voiceRat)} ready=$ready",
        )

        return ready
    }
}
