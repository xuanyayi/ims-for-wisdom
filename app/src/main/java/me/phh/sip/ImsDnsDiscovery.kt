// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.DnsResolver
import android.net.Network
import android.telephony.Rlog
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal object ImsDnsDiscovery {
    private const val CLASS_IN = 1
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_SRV = 33
    private const val TYPE_NAPTR = 35
    private const val DNS_TIMEOUT_MS = 3_000L

    fun discoverPcscf(
        tag: String,
        network: Network,
        mncCandidates: List<String>,
        mcc: String,
        preferUdp: Boolean,
    ): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        val imsDomains = mncCandidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .flatMap { mnc ->
                listOf(
                    "ims.mnc${mnc}.mcc${mcc}.pub.3gppnetwork.org",
                    "ims.mnc${mnc}.mcc${mcc}.3gppnetwork.org",
                )
            }

        for (domain in imsDomains) {
            val naptrTargets = queryNaptrTargets(tag, network, domain, preferUdp)
            for (target in naptrTargets) {
                val resolved = when (target.type) {
                    NaptrTarget.TYPE_SRV -> querySrvAddresses(tag, network, target.name)
                    NaptrTarget.TYPE_ADDRESS -> resolveHost(tag, network, target.name, "NAPTR A target")
                    else -> emptyList()
                }
                addDistinct(addresses, resolved)
                if (addresses.isNotEmpty()) return sortAddresses(addresses)
            }

            for (srvName in srvCandidateNames(domain, preferUdp)) {
                addDistinct(addresses, querySrvAddresses(tag, network, srvName))
                if (addresses.isNotEmpty()) return sortAddresses(addresses)
            }
        }

        return emptyList()
    }

    private fun srvCandidateNames(domain: String, preferUdp: Boolean): List<String> {
        val candidates = listOf(
            "_sip._udp.$domain",
            "_sip._tcp.$domain",
            "_sips._tcp.$domain",
        )
        val preferred = if (preferUdp) "_sip._udp." else "_sip._tcp."
        return candidates.sortedBy { if (it.startsWith(preferred)) 0 else 1 }
    }

    private fun queryNaptrTargets(
        tag: String,
        network: Network,
        domain: String,
        preferUdp: Boolean,
    ): List<NaptrTarget> {
        val response = queryRaw(tag, network, domain, TYPE_NAPTR) ?: return emptyList()
        val targets = try {
            DnsPacketReader(response).parseNaptrTargets()
        } catch (t: Throwable) {
            Rlog.w(tag, "P-CSCF NAPTR parse failed domain=$domain", t)
            emptyList()
        }

        if (targets.isNotEmpty()) {
            Rlog.w(tag, "P-CSCF NAPTR targets domain=$domain targets=$targets")
        }

        val preferred = if (preferUdp) "D2U" else "D2T"
        return targets
            .filter { it.service.uppercase(Locale.US).contains("SIP") }
            .filter { !it.service.uppercase(Locale.US).contains("SIPS") }
            .sortedWith(
                compareBy<NaptrTarget> {
                    if (it.service.uppercase(Locale.US).contains(preferred)) 0 else 1
                }.thenBy { it.order }.thenBy { it.preference },
            )
    }

    private fun querySrvAddresses(
        tag: String,
        network: Network,
        domain: String,
    ): List<InetAddress> {
        val response = queryRaw(tag, network, domain, TYPE_SRV) ?: return emptyList()
        val srvResponse = try {
            DnsPacketReader(response).parseSrvResponse()
        } catch (t: Throwable) {
            Rlog.w(tag, "P-CSCF SRV parse failed domain=$domain", t)
            return emptyList()
        }

        if (srvResponse.records.isNotEmpty()) {
            Rlog.w(tag, "P-CSCF SRV targets domain=$domain records=${srvResponse.records}")
        }

        val addresses = mutableListOf<InetAddress>()
        for (record in srvResponse.records.sortedWith(compareBy<SrvRecord> { it.priority }.thenBy { it.weight })) {
            val additional = srvResponse.additionalAddresses[record.target.lowercase(Locale.US)].orEmpty()
            if (additional.isNotEmpty()) {
                addDistinct(addresses, additional)
                continue
            }

            addDistinct(addresses, resolveHost(tag, network, record.target, "SRV target"))
        }

        return sortAddresses(addresses)
    }

    private fun resolveHost(
        tag: String,
        network: Network,
        host: String,
        source: String,
    ): List<InetAddress> {
        if (host.isBlank() || host == ".") return emptyList()

        val resolved = try {
            network.getAllByName(host)
        } catch (t: Throwable) {
            Rlog.w(tag, "P-CSCF DNS resolve failed source=$source host=$host", t)
            emptyArray()
        }
            .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }

        if (resolved.isNotEmpty()) {
            Rlog.w(tag, "P-CSCF DNS resolved source=$source host=$host addresses=$resolved")
        }

        return sortAddresses(resolved)
    }

    private fun queryRaw(
        tag: String,
        network: Network,
        domain: String,
        type: Int,
    ): ByteArray? {
        val latch = CountDownLatch(1)
        var answer: ByteArray? = null
        var rcode: Int? = null
        var error: Throwable? = null

        try {
            DnsResolver.getInstance().rawQuery(
                network,
                domain,
                CLASS_IN,
                type,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                Executor { it.run() },
                null,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(response: ByteArray, responseCode: Int) {
                        answer = response
                        rcode = responseCode
                        latch.countDown()
                    }

                    override fun onError(dnsError: DnsResolver.DnsException) {
                        error = dnsError
                        latch.countDown()
                    }
                },
            )
        } catch (t: Throwable) {
            Rlog.w(tag, "P-CSCF DNS query failed before dispatch type=${typeName(type)} domain=$domain", t)
            return null
        }

        if (!latch.await(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Rlog.w(tag, "P-CSCF DNS query timed out type=${typeName(type)} domain=$domain")
            return null
        }

        error?.let {
            Rlog.w(tag, "P-CSCF DNS query error type=${typeName(type)} domain=$domain", it)
            return null
        }

        val responseCode = rcode ?: -1
        if (responseCode != 0) {
            Rlog.w(tag, "P-CSCF DNS query returned rcode=$responseCode type=${typeName(type)} domain=$domain")
            return null
        }

        return answer
    }

    private fun typeName(type: Int): String =
        when (type) {
            TYPE_A -> "A"
            TYPE_AAAA -> "AAAA"
            TYPE_SRV -> "SRV"
            TYPE_NAPTR -> "NAPTR"
            else -> type.toString()
        }

    private fun addDistinct(target: MutableList<InetAddress>, addresses: List<InetAddress>) {
        for (address in addresses) {
            if (target.none { it.hostAddress == address.hostAddress }) {
                target.add(address)
            }
        }
    }

    private fun sortAddresses(addresses: List<InetAddress>): List<InetAddress> =
        addresses
            .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
            .sortedBy { if (it is Inet6Address) 0 else 1 }

    private data class NaptrTarget(
        val name: String,
        val type: Int,
        val service: String,
        val order: Int,
        val preference: Int,
    ) {
        companion object {
            const val TYPE_ADDRESS = 0
            const val TYPE_SRV = 1
        }
    }

    private data class SrvRecord(
        val target: String,
        val port: Int,
        val priority: Int,
        val weight: Int,
    )

    private data class SrvResponse(
        val records: List<SrvRecord>,
        val additionalAddresses: Map<String, List<InetAddress>>,
    )

    private data class DnsRecord(
        val section: Int,
        val name: String,
        val type: Int,
        val dataOffset: Int,
        val dataLength: Int,
    )

    private class DnsPacketReader(private val data: ByteArray) {
        companion object {
            private const val SECTION_ANSWER = 0
            private const val SECTION_AUTHORITY = 1
            private const val SECTION_ADDITIONAL = 2
        }

        fun parseNaptrTargets(): List<NaptrTarget> {
            return records()
                .filter { it.section == SECTION_ANSWER && it.type == TYPE_NAPTR }
                .mapNotNull { parseNaptrRecord(it) }
        }

        fun parseSrvResponse(): SrvResponse {
            val records = records()
            val srvRecords = records
                .filter { it.section == SECTION_ANSWER && it.type == TYPE_SRV }
                .mapNotNull { parseSrvRecord(it) }
            val additionalAddresses = records
                .filter { it.section == SECTION_ADDITIONAL && (it.type == TYPE_A || it.type == TYPE_AAAA) }
                .mapNotNull { parseAddressRecord(it)?.let { address -> it.name.lowercase(Locale.US) to address } }
                .groupBy({ it.first }, { it.second })

            return SrvResponse(srvRecords, additionalAddresses)
        }

        private fun records(): List<DnsRecord> {
            if (data.size < 12) return emptyList()

            val questionCount = u16(4)
            val answerCount = u16(6)
            val authorityCount = u16(8)
            val additionalCount = u16(10)
            var offset = 12

            repeat(questionCount) {
                val questionName = readName(offset)
                offset = questionName.nextOffset + 4
            }

            val records = mutableListOf<DnsRecord>()
            offset = readRecordSection(records, offset, answerCount, SECTION_ANSWER)
            offset = readRecordSection(records, offset, authorityCount, SECTION_AUTHORITY)
            readRecordSection(records, offset, additionalCount, SECTION_ADDITIONAL)

            return records
        }

        private fun readRecordSection(
            records: MutableList<DnsRecord>,
            startOffset: Int,
            count: Int,
            section: Int,
        ): Int {
            var offset = startOffset
            repeat(count) {
                if (offset >= data.size) return offset
                val name = readName(offset)
                offset = name.nextOffset
                if (offset + 10 > data.size) return offset
                val type = u16(offset)
                val dataLength = u16(offset + 8)
                val dataOffset = offset + 10
                if (dataOffset + dataLength > data.size) return data.size
                records.add(
                    DnsRecord(
                        section = section,
                        name = name.value,
                        type = type,
                        dataOffset = dataOffset,
                        dataLength = dataLength,
                    ),
                )
                offset = dataOffset + dataLength
            }
            return offset
        }

        private fun parseNaptrRecord(record: DnsRecord): NaptrTarget? {
            var offset = record.dataOffset
            val end = record.dataOffset + record.dataLength
            if (offset + 4 > end) return null

            val order = u16(offset)
            val preference = u16(offset + 2)
            offset += 4
            val flags = readCharacterString(offset, end) ?: return null
            offset = flags.nextOffset
            val service = readCharacterString(offset, end) ?: return null
            offset = service.nextOffset
            val regex = readCharacterString(offset, end) ?: return null
            offset = regex.nextOffset
            if (offset >= end) return null

            val replacement = readName(offset).value
            val targetType = when (flags.value.lowercase(Locale.US)) {
                "s" -> NaptrTarget.TYPE_SRV
                "a" -> NaptrTarget.TYPE_ADDRESS
                else -> return null
            }

            return NaptrTarget(
                name = replacement,
                type = targetType,
                service = service.value,
                order = order,
                preference = preference,
            )
        }

        private fun parseSrvRecord(record: DnsRecord): SrvRecord? {
            val offset = record.dataOffset
            val end = record.dataOffset + record.dataLength
            if (offset + 7 > end) return null

            return SrvRecord(
                priority = u16(offset),
                weight = u16(offset + 2),
                port = u16(offset + 4),
                target = readName(offset + 6).value,
            )
        }

        private fun parseAddressRecord(record: DnsRecord): InetAddress? {
            val length = record.dataLength
            if (record.type == TYPE_A && length != 4) return null
            if (record.type == TYPE_AAAA && length != 16) return null
            return try {
                InetAddress.getByAddress(data.copyOfRange(record.dataOffset, record.dataOffset + length))
            } catch (_: Throwable) {
                null
            }
        }

        private fun readCharacterString(startOffset: Int, endOffset: Int): StringField? {
            if (startOffset >= endOffset) return null
            val length = u8(startOffset)
            val dataOffset = startOffset + 1
            if (dataOffset + length > endOffset) return null
            return StringField(
                value = String(data, dataOffset, length, StandardCharsets.UTF_8),
                nextOffset = dataOffset + length,
            )
        }

        private fun readName(startOffset: Int): NameField {
            val labels = mutableListOf<String>()
            var offset = startOffset
            var nextOffset = startOffset
            var jumped = false
            var jumps = 0

            while (offset in data.indices && jumps < 32) {
                val length = u8(offset)
                when {
                    length == 0 -> {
                        if (!jumped) nextOffset = offset + 1
                        break
                    }

                    length and 0xC0 == 0xC0 -> {
                        if (offset + 1 >= data.size) break
                        val pointer = ((length and 0x3F) shl 8) or u8(offset + 1)
                        if (!jumped) nextOffset = offset + 2
                        offset = pointer
                        jumped = true
                        jumps++
                    }

                    length and 0xC0 != 0 -> break

                    else -> {
                        val labelOffset = offset + 1
                        if (labelOffset + length > data.size) break
                        labels.add(String(data, labelOffset, length, StandardCharsets.UTF_8))
                        offset = labelOffset + length
                        if (!jumped) nextOffset = offset
                    }
                }
            }

            return NameField(
                value = if (labels.isEmpty()) "." else labels.joinToString("."),
                nextOffset = nextOffset,
            )
        }

        private fun u8(offset: Int): Int = data[offset].toInt() and 0xFF

        private fun u16(offset: Int): Int =
            (u8(offset) shl 8) or u8(offset + 1)
    }

    private data class NameField(val value: String, val nextOffset: Int)
    private data class StringField(val value: String, val nextOffset: Int)
}
