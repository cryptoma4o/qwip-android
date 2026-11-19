package com.qwipvpn.core

import kotlinx.serialization.json.*

/** Only transport/DNS ingress and explicit user routing are adapted, without mutating storage. */
object RuntimeConfiguration {
    const val RESOLVER = "198.18.0.1"
    fun build(profile: Profile, settings: ClientSettings, fd: Int, assetPath: String, routingProfile: RoutingProfile? = null, metrics: MetricsAccess? = null): String {
        require(fd >= 0 && settings.mtu in 1280..1500)
        require(settings.dns.isNotEmpty() && settings.dns.all(::isIpLiteral))
        val initial = routingProfile?.apply(profile) ?: profile.configuration
        val fields = RawJson.members(initial)
        val outbounds = fields["outbounds"]?.let(RawJson::elements)?.toMutableList() ?: error("No outbounds")
        require(outbounds.isNotEmpty())
        val tuned = settings.xray.overlay(profile, outbounds).toList()
        outbounds.clear(); outbounds.addAll(tuned)
        val inboundList = fields["inbounds"]?.let(RawJson::elements)?.toMutableList() ?: mutableListOf()
        val inObjects = inboundList.map { Json.parseToJsonElement(it).jsonObject }
        val tunIndices = inObjects.indices.filter { inObjects[it]["protocol"]?.jsonPrimitive?.content == "tun" }
        require(tunIndices.size <= 1)
        val index = tunIndices.firstOrNull() ?: inObjects.indices.firstOrNull { inObjects[it]["protocol"]?.jsonPrimitive?.content in listOf("socks", "http") }
        var tag = index?.let { inObjects[it]["tag"]?.jsonPrimitive?.content } ?: "qwip-packets"
        if (index == null) while (inObjects.any { it["tag"]?.jsonPrimitive?.content == tag }) tag += "-1"
        var sniff = index?.let { RawJson.members(inboundList[it])["sniffing"] }
            ?: if (fields.containsKey("fakedns")) """{"enabled":true,"destOverride":["http","tls","quic","fakedns"],"routeOnly":true}"""
            else """{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":true}"""
        if (!settings.xray.sniffing) sniff = RawJson.setting(sniff, "enabled", "false")
        val tun = RawJson.obj("tag" to RawJson.quote(tag), "protocol" to "\"tun\"",
            "settings" to """{"name":"tun0","mtu":${settings.mtu}}""", "sniffing" to sniff)
        if (index != null) inboundList[index] = tun else inboundList += tun
        var dns = if (settings.useProfileDns) fields["dns"] ?: "{}" else "{}"
        val servers = RawJson.members(dns)["servers"]?.let(RawJson::elements)
        if (servers.isNullOrEmpty()) dns = RawJson.setting(dns, "servers", Json.encodeToString(settings.dns))
        else require(servers.none { server ->
            val node = Json.parseToJsonElement(server)
            (if (node is JsonObject) node["address"]?.jsonPrimitive?.content else node.jsonPrimitive.content) == "localhost"
        }) { "Profile DNS requires an explicit resolver" }
        val tags = outbounds.mapNotNull { Json.parseToJsonElement(it).jsonObject["tag"]?.jsonPrimitive?.content }.toMutableSet()
        fun unused(base: String): String { var name = base; while (name in tags) name += "-1"; tags += name; return name }
        val metricsTag=metrics?.let{unused("qwip-private-metrics")}
        val metricsInbound=metrics?.let{
            var name="qwip-metrics-in";while(inObjects.any{obj->obj["tag"]?.jsonPrimitive?.content==name}||name==tag)name+="-1"
            inboundList+=buildJsonObject{
                put("tag",name);put("listen","127.0.0.1");put("port",it.port);put("protocol","http")
                putJsonObject("settings"){put("allowTransparent",false);putJsonArray("accounts"){add(buildJsonObject{put("user",it.username);put("pass",it.password)})}}
            }.toString();name
        }
        val dnsTag = unused("qwip-resolver")
        outbounds += RawJson.obj("tag" to RawJson.quote(dnsTag), "protocol" to "\"dns\"", "settings" to "{}")
        var routing = fields["routing"] ?: "{}"
        val rules = RawJson.members(routing)["rules"]?.let(RawJson::elements)?.toMutableList() ?: mutableListOf()
        val custom = mutableListOf<String>()
        if (settings.bypassLan || settings.directDomains.isNotEmpty()) {
            val direct = unused("qwip-direct")
            outbounds += RawJson.obj("tag" to RawJson.quote(direct), "protocol" to "\"freedom\"", "settings" to "{}")
            if (settings.bypassLan) custom += """{"type":"field","ip":["geoip:private"],"outboundTag":${RawJson.quote(direct)}}"""
            if (settings.directDomains.isNotEmpty()) custom += domainRule(settings.directDomains, direct)
        }
        if (settings.blockedDomains.isNotEmpty()) {
            val block = unused("qwip-block")
            outbounds += RawJson.obj("tag" to RawJson.quote(block), "protocol" to "\"blackhole\"", "settings" to "{}")
            custom.add(0, domainRule(settings.blockedDomains, block))
        }
        custom.add(0, """{"type":"field","inboundTag":[${RawJson.quote(tag)}],"ip":["$RESOLVER/32"],"port":"53","outboundTag":${RawJson.quote(dnsTag)}}""")
        if(metricsInbound!=null&&metricsTag!=null)custom.add(0,RawJson.obj("type" to RawJson.quote("field"),"inboundTag" to RawJson.array(listOf(RawJson.quote(metricsInbound))),"outboundTag" to RawJson.quote(metricsTag)))
        rules.addAll(0, custom)
        routing = RawJson.setting(routing, "rules", RawJson.array(rules))
        var env = fields["env"] ?: "{}"
        for ((key, value) in listOf("xray.tun.fd" to fd.toString(), "XRAY_TUN_FD" to fd.toString(),
            "xray.location.asset" to assetPath, "XRAY_LOCATION_ASSET" to assetPath)) {
            env = RawJson.setting(env, key, RawJson.quote(value))
        }
        var result = initial
        for ((key, value) in listOf("inbounds" to RawJson.array(inboundList), "outbounds" to RawJson.array(outbounds),
            "dns" to dns, "routing" to routing, "env" to env,
            "log" to """{"access":"none","error":"none","loglevel":"none"}""")) result = RawJson.setting(result, key, value)
        if (metrics != null) {
            // The metrics server is internal, behind an authenticated, loopback-only HTTP inbound.
            // A bare metrics.listen would expose /debug/pprof to other Android apps.
            val originalMetrics = RawJson.members(result)["metrics"] ?: "{}"
            result = RawJson.setting(result,"metrics",patchObject(originalMetrics,buildJsonObject{put("tag",metricsTag)},setOf("tag","listen")))
            val policy = RawJson.members(result)["policy"] ?: "{}"
            var system = RawJson.members(policy)["system"] ?: "{}"
            system = RawJson.setting(RawJson.setting(system, "statsInboundUplink", "true"), "statsInboundDownlink", "true")
            result = RawJson.setting(result, "policy", RawJson.setting(policy, "system", system))
            if (!RawJson.members(result).containsKey("stats")) result = RawJson.setting(result, "stats", "{}")
        }
        return result
    }
    private fun domainRule(domains: List<String>, tag: String): String {
        require(domains.size <= 500)
        val normalized = domains.map { domain ->
            require(domain.isNotBlank() && domain.length < 254 && domain.none { it.isWhitespace() || it == '/' || it == ':' })
            "domain:" + domain.lowercase()
        }
        return RawJson.obj("type" to "\"field\"", "domain" to Json.encodeToString(normalized), "outboundTag" to RawJson.quote(tag))
    }
    fun isIpLiteral(value: String): Boolean {
        if (value.contains(':')) return value.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(value).address.size == 16 }.getOrDefault(false)
        return value.split('.').let { it.size == 4 && it.all { p -> p.isNotEmpty() && p.all(Char::isDigit) && (p.toIntOrNull() ?: -1) in 0..255 } }
    }
}
