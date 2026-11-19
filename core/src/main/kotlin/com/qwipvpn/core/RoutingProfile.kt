package com.qwipvpn.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

@Serializable data class RoutingDns(val kind: String = "DoH", val address: String = "https://cloudflare-dns.com/dns-query", val bootstrap: String = "1.1.1.1") {
    fun valid(): Boolean = RuntimeConfiguration.isIpLiteral(bootstrap) && when (kind) {
        "DoU" -> true
        "DoH" -> runCatching { SubscriptionHttp.validateUrl(address) }.isSuccess
        "DoT" -> runCatching { URI(address).let { it.scheme == "tls" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.port in listOf(-1,853) && it.path in listOf("", "/") && it.query == null && it.fragment == null } }.getOrDefault(false)
        else -> false
    }
    val server get() = if (kind == "DoU") bootstrap else address
}

@Serializable data class RoutingProfile(
    val id: String = UUID.randomUUID().toString(), val name: String = "Мои правила", val sourceId: String? = null,
    val enabled: Boolean = true, val applyToProviderJson: Boolean = false, val globalProxy: Boolean = true,
    val strategy: String = "IPIfNonMatch", val remoteDns: RoutingDns = RoutingDns(), val domesticDns: RoutingDns = RoutingDns(),
    val directDomains: List<String> = emptyList(), val directIps: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(), val proxyIps: List<String> = emptyList(),
    val blockDomains: List<String> = emptyList(), val blockIps: List<String> = emptyList(),
    val hosts: Map<String, List<String>> = emptyMap(), val order: List<String> = listOf("block", "direct", "proxy"),
    val fakeDns: Boolean = false, val geoIpUrl: String? = null, val geoSiteUrl: String? = null,
) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 160 && order.size == 3 && order.toSet() == setOf("block", "direct", "proxy"))
        require(strategy in listOf("AsIs", "IPIfNonMatch", "IPOnDemand") && remoteDns.valid() && domesticDns.valid())
        listOfNotNull(geoIpUrl, geoSiteUrl).forEach(SubscriptionHttp::validateUrl)
        for (list in listOf(directDomains, proxyDomains, blockDomains, directIps, proxyIps, blockIps)) require(list.size <= 4096 && list.all { it.isNotBlank() && it.length <= 2048 && '\n' !in it && '\r' !in it })
        for (ip in directIps + proxyIps + blockIps) if (!ip.startsWith("geoip:") && !ip.startsWith("ext:")) Cidr.parse(ip)
        require(hosts.size <= 1024 && hosts.all { (name, ips) -> name.isNotBlank() && ips.isNotEmpty() && ips.all(RuntimeConfiguration::isIpLiteral) })
    }
    fun applies(profile: Profile) = enabled && (profile.origin != "json" || applyToProviderJson)
    fun apply(profile: Profile): String {
        validate(); if (!applies(profile)) return profile.configuration
        val fields = RawJson.members(profile.configuration); val outbounds = RawJson.elements(fields.getValue("outbounds")).toMutableList()
        val objects = outbounds.map { Json.parseToJsonElement(it).jsonObject }; val tags = objects.map { it.text("tag") }.toMutableSet()
        fun unused(base: String): String { var value = base; while (value in tags) value += "-1"; tags += value; return value }
        val proxyIndex = objects.indexOfFirst { it.text("protocol") !in ServerSummary.auxiliary }; require(proxyIndex >= 0)
        val proxy = objects[proxyIndex].text("tag").ifBlank { unused("qwip-proxy").also { outbounds[proxyIndex] = RawJson.setting(outbounds[proxyIndex], "tag", RawJson.quote(it)) } }
        val direct = unused("qwip-rule-direct"); val block = unused("qwip-rule-block")
        outbounds += RawJson.obj("tag" to RawJson.quote(direct), "protocol" to RawJson.quote("freedom"))
        outbounds += RawJson.obj("tag" to RawJson.quote(block), "protocol" to RawJson.quote("blackhole"))
        val rules = mutableListOf<String>()
        for (action in order) {
            val (domains, ips, tag) = when (action) {
                "direct" -> Triple(directDomains, directIps, direct)
                "block" -> Triple(blockDomains, blockIps, block)
                else -> Triple(proxyDomains, proxyIps, proxy)
            }
            for ((key, values) in listOf("domain" to domains, "ip" to ips)) if (values.isNotEmpty()) rules += buildJsonObject {
                put("type", "field"); put("outboundTag", tag); put(key, JsonArray(values.map { JsonPrimitive(if (key == "domain" && ':' !in it) "domain:$it" else it) }))
            }.toString()
        }
        var routing = fields["routing"] ?: "{}"; val previous = RawJson.members(routing)["rules"]?.let(RawJson::elements).orEmpty()
        routing = RawJson.setting(RawJson.setting(routing, "domainStrategy", RawJson.quote(strategy)), "rules", RawJson.array(rules + previous))
        val defaultIndex = if (globalProxy) proxyIndex else outbounds.lastIndex - 1
        val first = outbounds.removeAt(defaultIndex); outbounds.add(0, first)
        var config = RawJson.setting(RawJson.setting(profile.configuration, "routing", routing), "outbounds", RawJson.array(outbounds))
        val dns = buildJsonObject {
            put("queryStrategy", "UseIP"); putJsonObject("hosts") {
                // Resolve encrypted DNS endpoints without depending on that same resolver.
                for (resolver in listOf(remoteDns, domesticDns)) if (resolver.kind != "DoU") {
                    URI(resolver.address).host?.takeUnless(RuntimeConfiguration::isIpLiteral)?.let { put(it, JsonArray(listOf(JsonPrimitive(resolver.bootstrap)))) }
                }
                hosts.forEach { (host, ips) -> put(host, JsonArray(ips.map(::JsonPrimitive))) }
            }
            putJsonArray("servers") {
                if (fakeDns) add(JsonPrimitive("fakedns"))
                if (directDomains.isNotEmpty()) add(buildJsonObject { put("address", domesticDns.server); put("domains", JsonArray(directDomains.map(::JsonPrimitive))); put("skipFallback", true) })
                add(JsonPrimitive(remoteDns.server))
            }
        }
        config = RawJson.setting(config, "dns", patchObject(fields["dns"] ?: "{}", dns, setOf("servers", "hosts", "queryStrategy")))
        if (fakeDns) config = RawJson.setting(config, "fakedns", """[{"ipPool":"198.18.0.0/15","poolSize":65535}]""")
        return config
    }
    companion object {
        fun localNetwork(name: String = "Обход локальной сети") = RoutingProfile(name = name, directIps = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "fc00::/7", "fe80::/10"))
        fun country(code: String, ipTags: Set<String>, siteTags: Set<String>, name: String): RoutingProfile {
            val upper = code.uppercase(java.util.Locale.ROOT); require(upper in ipTags.map { it.uppercase(java.util.Locale.ROOT) }.toSet())
            val local = localNetwork(name); val tags = siteTags.map { it.uppercase(java.util.Locale.ROOT) }.toSet()
            return local.copy(directIps = local.directIps + "geoip:${upper.lowercase(java.util.Locale.ROOT)}", directDomains = listOf(upper, "CATEGORY-$upper", "GEOLOCATION-$upper").filter(tags::contains).map { "geosite:${it.lowercase(java.util.Locale.ROOT)}" })
        }
    }
}

data class RoutingCommand(val profile: RoutingProfile? = null, val activate: Boolean = false, val disable: Boolean = false)
object RoutingLinks {
    fun export(profile: RoutingProfile): String {
        profile.validate()
        val objectJson = buildJsonObject {
            put("name", profile.name); put("globalproxy", profile.globalProxy); put("domainstrategy", profile.strategy); put("fakedns", profile.fakeDns)
            for ((key, values) in listOf("directsites" to profile.directDomains, "directip" to profile.directIps, "proxysites" to profile.proxyDomains, "proxyip" to profile.proxyIps, "blocksites" to profile.blockDomains, "blockip" to profile.blockIps, "routeorder" to profile.order)) put(key, JsonArray(values.map(::JsonPrimitive)))
            for ((prefix, dns) in listOf("remote" to profile.remoteDns, "domestic" to profile.domesticDns)) { put(prefix+"dnstype", dns.kind); put(prefix+"dnsdomain", dns.address); put(prefix+"dnsip", dns.bootstrap) }
            putJsonObject("dnshosts") { profile.hosts.forEach { (key, ips) -> put(key, JsonArray(ips.map(::JsonPrimitive))) } }
            profile.geoIpUrl?.let { put("geoipurl", it) }; profile.geoSiteUrl?.let { put("geositeurl", it) }
        }.toString().toByteArray()
        require(objectJson.size <= 128 * 1024)
        return "qwip://routing/onadd/" + URLEncoder.encode(Base64.getEncoder().encodeToString(objectJson), "UTF-8")
    }
    fun parse(text: String): RoutingCommand {
        require(text.toByteArray().size <= 192 * 1024); val uri = URI(text)
        require(uri.scheme in listOf("happ", "notvpn", "qwip") && uri.host == "routing")
        val parts = uri.rawPath.trim('/').split('/', limit = 2)
        if (parts == listOf("off")) return RoutingCommand(disable = true)
        require(parts.size == 2 && parts[0] in listOf("add", "onadd"))
        val encoded = URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { Base64.getUrlDecoder().decode(encoded) }; require(bytes.size <= 128 * 1024)
        val raw = Json.parseToJsonElement(SubscriptionImporter.decodeUtf8(bytes)).jsonObject
        val fields = JsonObject(raw.mapKeys { it.key.lowercase(java.util.Locale.ROOT) })
        fun list(key: String) = fields.array(key).map { it.jsonPrimitive.content }
        fun dns(prefix: String) = RoutingDns(fields.text(prefix+"dnstype", "DoH"), fields.text(prefix+"dnsdomain", "https://cloudflare-dns.com/dns-query"), fields.text(prefix+"dnsip", "1.1.1.1"))
        val hosts = fields.obj("dnshosts").mapValues { (_, value) -> if (value is JsonArray) value.map { it.jsonPrimitive.content } else listOf(value.jsonPrimitive.content) }
        val profile = RoutingProfile(name = fields.text("name", "Правила подписки"), globalProxy = fields.bool("globalproxy", true), strategy = fields.text("domainstrategy", "IPIfNonMatch"), fakeDns = fields.bool("fakedns"), remoteDns = dns("remote"), domesticDns = dns("domestic"),
            directDomains = list("directsites"), directIps = list("directip"), proxyDomains = list("proxysites"), proxyIps = list("proxyip"), blockDomains = list("blocksites"), blockIps = list("blockip"),
            hosts = hosts, order = list("routeorder").ifEmpty { listOf("block", "direct", "proxy") }, geoIpUrl = fields.text("geoipurl").takeIf(String::isNotBlank), geoSiteUrl = fields.text("geositeurl").takeIf(String::isNotBlank))
        profile.validate(); return RoutingCommand(profile, activate = parts[0] == "onadd")
    }
}
