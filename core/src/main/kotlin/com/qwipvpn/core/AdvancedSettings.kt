package com.qwipvpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.net.InetAddress
import java.util.UUID

@Serializable data class XrayNoise(val type: String = "rand", val packet: String = "50-100", val delay: String = "10-20") {
    fun valid() = XraySettings.range(delay) && when (type) {
        "rand" -> XraySettings.range(packet)
        "str" -> packet.isNotEmpty() && packet.length <= 256 && packet.all { it.code in 0x20..0x7e }
        "base64" -> packet.isNotEmpty() && packet.length <= 512 && runCatching { java.util.Base64.getDecoder().decode(packet) }.isSuccess
        else -> false
    }
}
@Serializable data class XraySettings(
    val sniffing: Boolean = true, val muxEnabled: Boolean = false, val muxConcurrency: Int = 8,
    val xudpConcurrency: Int = 16, val fragmentEnabled: Boolean = false, val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "100-200", val fragmentInterval: String = "10-20",
    val noisesEnabled: Boolean = false, val noises: List<XrayNoise> = listOf(XrayNoise()), val applyToProviderJson: Boolean = false,
) {
    fun valid() = muxConcurrency in 1..1024 && xudpConcurrency in 1..1024 &&
        (fragmentPackets == "tlshello" || range(fragmentPackets)) && range(fragmentLength) && range(fragmentInterval) && noises.size <= 4 && noises.all(XrayNoise::valid)
    fun overlay(profile: Profile, input: List<String>): List<String> {
        require(valid())
        if ((!muxEnabled && !fragmentEnabled && !noisesEnabled) || (profile.origin == "json" && !applyToProviderJson)) return input
        val result = input.toMutableList(); val objects = result.map { Json.parseToJsonElement(it).jsonObject }
        val index = objects.indexOfFirst { it.text("protocol") !in ServerSummary.auxiliary }; require(index >= 0)
        var proxy = result[index]
        if (muxEnabled) proxy = RawJson.setting(proxy, "mux", """{"enabled":true,"concurrency":$muxConcurrency,"xudpConcurrency":$xudpConcurrency,"xudpProxyUDP443":"reject"}""")
        if (fragmentEnabled || (noisesEnabled && noises.isNotEmpty())) {
            var tag = "qwip-fragment"; while (objects.any { it.text("tag") == tag }) tag += "-1"
            val helper = buildJsonObject {
                put("tag", tag); put("protocol", "freedom"); putJsonObject("settings") {
                    if (fragmentEnabled) putJsonObject("fragment") { put("packets", fragmentPackets); put("length", fragmentLength); put("interval", fragmentInterval) }
                    if (noisesEnabled) put("noises", JsonArray(noises.map { buildJsonObject { put("type", it.type); put("packet", it.packet); put("delay", it.delay) } }))
                }
            }
            val stream = RawJson.members(proxy)["streamSettings"] ?: "{}"
            val sockopt = RawJson.members(stream)["sockopt"] ?: "{}"
            proxy = RawJson.setting(proxy, "streamSettings", RawJson.setting(stream, "sockopt", RawJson.setting(sockopt, "dialerProxy", RawJson.quote(tag))))
            result += helper.toString()
        }
        result[index] = proxy
        return result
    }
    companion object {
        fun range(text: String): Boolean {
            val parts = text.split('-'); val numbers = parts.mapNotNull { if (it.isNotEmpty() && it.all(Char::isDigit)) it.toIntOrNull() else null }
            return parts.size in 1..2 && numbers.size == parts.size && numbers.all { it in 0..65535 } && (numbers.size == 1 || numbers[0] <= numbers[1])
        }
    }
}

@Serializable data class AutoConnectRule(
    val id: String = UUID.randomUUID().toString(), val action: String = "connect", val network: String = "any",
    val dnsServers: List<String> = emptyList(), val dnsDomains: List<String> = emptyList(),
    val ssids:List<String> = emptyList(),
) {
    fun valid() = action in listOf("connect", "disconnect", "ignore") && network in listOf("any", "wifi", "cellular") &&
        dnsServers.size <= 20 && dnsServers.all(RuntimeConfiguration::isIpLiteral) && dnsDomains.size <= 20 && dnsDomains.all { it.isNotBlank() && it.length <= 253 && it.none(Char::isWhitespace) } &&
        ssids.size<=20&&ssids.all{it.isNotBlank()&&it.toByteArray().size<=32&&'\n' !in it&&'\r' !in it}&&(network!="cellular"||ssids.isEmpty())
    fun matches(type: String, servers: List<String>, domains: List<String>,ssid:String?=null) = (network == "any" || network == type) &&
        (ssids.isEmpty()||type=="wifi"&&ssid!=null&&ssid in ssids)&&
        (dnsServers.isEmpty() || dnsServers.any(servers::contains)) &&
        (dnsDomains.isEmpty() || dnsDomains.any { wanted -> domains.any { it.equals(wanted, true) || it.endsWith(".$wanted", true) } })
}

/** Prefix subtraction works on API 28 as well as newer excludeRoute implementations. No hostname resolution. */
data class Cidr(val address: BigInteger, val prefix: Int, val bits: Int) {
    val end: BigInteger get() = address + BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE
    fun contains(other: Cidr) = bits == other.bits && address <= other.address && end >= other.end
    fun subtract(other: Cidr): List<Cidr> {
        if (bits != other.bits || end < other.address || address > other.end) return listOf(this)
        if (other.contains(this)) return emptyList()
        require(prefix < bits)
        val left = copy(prefix = prefix + 1); val right = Cidr(address + BigInteger.ONE.shiftLeft(bits - prefix - 1), prefix + 1, bits)
        return left.subtract(other) + right.subtract(other)
    }
    override fun toString(): String {
        val raw = address.toByteArray(); val bytes = ByteArray(bits / 8)
        val n = minOf(raw.size, bytes.size); raw.copyInto(bytes, bytes.size - n, raw.size - n)
        return InetAddress.getByAddress(bytes).hostAddress + "/" + prefix
    }
    companion object {
        fun parse(text: String): Cidr {
            val parts = text.trim().split('/'); require(parts.size in 1..2 && RuntimeConfiguration.isIpLiteral(parts[0]))
            val bytes = InetAddress.getByName(parts[0]).address; val bits = bytes.size * 8
            val prefix = if (parts.size == 1) bits else parts[1].toInt(); require(prefix in 0..bits)
            return Cidr(BigInteger(1, bytes).shiftRight(bits - prefix).shiftLeft(bits - prefix), prefix, bits)
        }
        fun routes(included: List<String>, excluded: List<String>): List<Cidr> {
            require(included.size <= 64 && excluded.size <= 64)
            var routes = (included.ifEmpty { listOf("0.0.0.0/0", "::/0") }).map(::parse).distinct()
            for (exclude in excluded.map(::parse)) { routes = routes.flatMap { it.subtract(exclude) }; require(routes.size <= 4096) }
            return routes
        }
    }
}

object SettingsValidation {
    fun valid(value: ClientSettings): Boolean = runCatching {
        require(value.mtu in 1280..1500 && value.dns.isNotEmpty() && value.dns.all(RuntimeConfiguration::isIpLiteral))
        require(value.probeMethod in listOf("tcp", "proxy") && value.probeTimeout in 1..15 && value.serverSort in listOf("original", "name", "latency"))
        listOf(value.probeUrl, value.ipCheckUrl, value.speedTestUrl).forEach(SubscriptionHttp::validateUrl)
        require(value.xray.valid() && value.onDemandRules.size <= 20 && value.onDemandRules.all(AutoConnectRule::valid))
        Cidr.routes(value.includedRoutes, value.excludedRoutes)
    }.isSuccess
}
