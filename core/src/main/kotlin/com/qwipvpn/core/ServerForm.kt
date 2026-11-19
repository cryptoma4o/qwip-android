package com.qwipvpn.core

import kotlinx.serialization.json.*
import java.util.Locale

internal fun JsonObject.text(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
internal fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: buildJsonObject {}
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.int(key: String, fallback: Int = 0) = text(key).toIntOrNull() ?: fallback
internal fun JsonObject.bool(key: String, fallback: Boolean = false) = text(key).toBooleanStrictOrNull() ?: fallback

data class ServerSummary(val protocol: String, val transport: String, val security: String,
    val address: String?, val port: Int?, val proxyCount: Int, val composite: Boolean) {
    val label get() = listOfNotNull(protocol, transport, security.takeUnless { it == "None" }, "JSON".takeIf { composite }).joinToString(" | ")
    companion object {
        val auxiliary = setOf("freedom", "blackhole", "dns", "loopback")
        fun read(profile: Profile): ServerSummary? = runCatching {
            val root = Json.parseToJsonElement(profile.configuration).jsonObject
            val proxies = root.array("outbounds").map { it.jsonObject }.filter { it.text("protocol") !in auxiliary }
            val outbound = proxies.first(); val settings = endpoint(outbound); val stream = outbound.obj("streamSettings")
            val proto = outbound.text("protocol").let { mapOf("vmess" to "VMess", "trojan" to "Trojan", "shadowsocks" to "Shadowsocks", "wireguard" to "WireGuard")[it] ?: it.uppercase(Locale.ROOT) }
            val net = stream.text("network", "raw").let { mapOf("raw" to "TCP", "tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC", "xhttp" to "XHTTP", "splithttp" to "XHTTP", "httpupgrade" to "HTTPUpgrade", "kcp" to "mKCP")[it] ?: it }
            val security = stream.text("security", "none").let { mapOf("none" to "None", "tls" to "TLS", "reality" to "Reality")[it] ?: it }
            ServerSummary(proto, net, security, settings.text("address").takeIf(String::isNotBlank), settings.int("port").takeIf { it in 1..65535 }, proxies.size,
                proxies.size > 1 || root.obj("routing").array("rules").isNotEmpty() || root.obj("routing").array("balancers").isNotEmpty())
        }.getOrNull()
        fun endpoint(outbound: JsonObject): JsonObject {
            val settings = outbound.obj("settings")
            return (settings.array("vnext").firstOrNull() ?: settings.array("servers").firstOrNull()) as? JsonObject ?: settings
        }
        fun flag(name: String): String? {
            val points = name.codePoints().toArray()
            return (0 until (points.size - 1).coerceAtLeast(0)).firstOrNull { points[it] in 0x1F1E6..0x1F1FF && points[it+1] in 0x1F1E6..0x1F1FF }
                ?.let { String(points, it, 2) }
        }
        fun withoutFlags(name: String): String = name.codePoints().filter { it !in 0x1F1E6..0x1F1FF }.toArray().let { String(it, 0, it.size).trim() }
    }
}

/** Editable fields for one proxy. Original JSON is edited structurally, never projected through share links. */
data class ServerForm(
    val name: String = "", val protocol: String = "vless", val address: String = "", val port: String = "443",
    val id: String = "", val password: String = "", val user: String = "", val method: String = "aes-256-gcm",
    val vmessSecurity: String = "auto", val flow: String = "", val network: String = "raw",
    val host: String = "", val path: String = "", val serviceName: String = "", val multiMode: Boolean = false,
    val xhttpMode: String = "auto", val seed: String = "", val rawHttpHeader: Boolean = false,
    val security: String = "none", val serverName: String = "", val alpn: String = "", val fingerprint: String = "",
    val publicKey: String = "", val shortId: String = "", val spiderX: String = "",
) {
    val usesId get() = protocol in listOf("vless", "vmess")
    val supportsTransport get() = protocol in listOf("vless", "vmess", "trojan")
    fun problems(): Set<String> = buildSet {
        if (name.isBlank() || name.length > 160) add("name")
        if (address.isBlank() || address.length > 253 || address.any { it.isWhitespace() || it in "/@?#" }) add("address")
        if ((port.toIntOrNull() ?: 0) !in 1..65535) add("port")
        if (protocol !in protocols || network !in networks || security !in securities) add("protocol")
        if (usesId && (id.isBlank() || id.length > 64)) add("credential")
        if (protocol in listOf("trojan", "shadowsocks") && password.isEmpty()) add("credential")
        if (protocol in listOf("socks", "http") && user.isEmpty() != password.isEmpty()) add("credential")
        if (protocol == "shadowsocks" && method !in shadowsocksMethods) add("method")
        if (supportsTransport && security == "reality") {
            if (publicKey.isBlank()) add("publicKey")
            if (serverName.isBlank()) add("serverName")
            if (shortId.length > 16 || shortId.length % 2 != 0 || shortId.any { it !in "0123456789abcdefABCDEF" }) add("shortId")
        }
        if (alpn.split(',').map(String::trim).filter(String::isNotEmpty).any { it !in listOf("h2", "h3", "http/1.1") }) add("alpn")
    }
    fun outbound(tag: String = "proxy"): JsonObject = buildJsonObject {
        put("protocol", protocol); put("tag", tag)
        putJsonObject("settings") {
            put("address", address.trim()); put("port", port.toInt())
            when (protocol) {
                "vless" -> { put("id", id.trim()); put("encryption", "none"); if (flow.isNotEmpty()) put("flow", flow) }
                "vmess" -> { put("id", id.trim()); if (vmessSecurity != "auto") put("security", vmessSecurity) }
                "trojan" -> put("password", password)
                "shadowsocks" -> { put("method", method); put("password", password) }
                else -> if (user.isNotEmpty()) { put("user", user); put("pass", password) }
            }
        }
        if (supportsTransport) putJsonObject("streamSettings") {
            put("network", network); put("security", security)
            fun JsonObjectBuilder.optional(key: String, value: String) { if (value.isNotEmpty()) put(key, value) }
            when (network) {
                "raw" -> if (rawHttpHeader) putJsonObject("rawSettings") { putJsonObject("header") {
                    put("type", "http"); putJsonObject("request") {
                        if (path.isNotEmpty()) put("path", JsonArray(listOf(JsonPrimitive(path))))
                        if (host.isNotEmpty()) putJsonObject("headers") { put("Host", JsonArray(listOf(JsonPrimitive(host)))) }
                    }
                } }
                "ws", "xhttp", "httpupgrade" -> putJsonObject(network + "Settings") {
                    optional("path", path); optional("host", host); if (network == "xhttp") optional("mode", xhttpMode)
                }
                "grpc" -> putJsonObject("grpcSettings") { optional("serviceName", serviceName); if (multiMode) put("multiMode", true) }
                "kcp" -> putJsonObject("kcpSettings") { optional("seed", seed) }
            }
            if (security == "tls") putJsonObject("tlsSettings") {
                optional("serverName", serverName); optional("fingerprint", fingerprint)
                val protocols = alpn.split(',').map(String::trim).filter(String::isNotEmpty)
                if (protocols.isNotEmpty()) put("alpn", JsonArray(protocols.map(::JsonPrimitive)))
            }
            if (security == "reality") putJsonObject("realitySettings") {
                put("password", publicKey.trim()); put("serverName", serverName.trim()); optional("fingerprint", fingerprint)
                optional("shortId", shortId); optional("spiderX", spiderX)
            }
        }
    }
    fun configuration(): String {
        require(problems().isEmpty())
        return RawJson.obj("remarks" to RawJson.quote(name.trim()), "outbounds" to RawJson.array(listOf(outbound().toString(),
            """{"protocol":"freedom","tag":"direct"}""", """{"protocol":"blackhole","tag":"block"}""")))
    }
    fun updating(original: String): String {
        require(problems().isEmpty() && from(Profile(title=name, configuration=original, originKey="")) != null)
        val root = RawJson.members(original); val outbounds = RawJson.elements(root.getValue("outbounds")).toMutableList()
        val index = outbounds.indexOfFirst { Json.parseToJsonElement(it).jsonObject.text("protocol") !in ServerSummary.auxiliary }
        val old = RawJson.members(outbounds[index]); val oldObject = Json.parseToJsonElement(outbounds[index]).jsonObject
        val fresh = outbound(oldObject.text("tag", "proxy"))
        val sameProtocol = oldObject.text("protocol") == protocol
        val oldSettings = old["settings"] ?: "{}"
        val nestedKey = if (sameProtocol) listOf("vnext", "servers").firstOrNull { RawJson.members(oldSettings).containsKey(it) } else null
        var settings: String
        if (nestedKey != null) {
            val items = RawJson.elements(RawJson.members(oldSettings).getValue(nestedKey)).toMutableList()
            var server = items.first(); val fields = fresh.obj("settings").toMutableMap()
            for (key in listOf("address", "port")) fields.remove(key)?.let { server = RawJson.setting(server, key, it.toString()) }
            val users = RawJson.members(server)["users"]?.let(RawJson::elements)
            if (users != null) {
                val updated = patchObject(users.first(), JsonObject(fields), setOf("id", "encryption", "flow", "security", "password", "user", "pass", "method"))
                server = RawJson.setting(server, "users", RawJson.array(listOf(updated)))
            } else server = patchObject(server, JsonObject(fields), setOf("id", "encryption", "flow", "security", "password", "user", "pass", "method"))
            items[0] = server; settings = RawJson.setting(oldSettings, nestedKey, RawJson.array(items))
        } else settings = patchObject(oldSettings, fresh.obj("settings"), setOf("address", "port", "id", "encryption", "flow", "security", "password", "user", "pass", "method", "vnext", "servers"))
        var target = RawJson.setting(outbounds[index], "protocol", RawJson.quote(protocol))
        target = RawJson.setting(target, "settings", settings)
        if (supportsTransport) {
            val stream = fresh.obj("streamSettings")
            target = RawJson.setting(target, "streamSettings", patchObject(old["streamSettings"] ?: "{}", stream,
                setOf("network", "security", "rawSettings", "tcpSettings", "wsSettings", "grpcSettings", "xhttpSettings", "splithttpSettings", "httpupgradeSettings", "kcpSettings", "tlsSettings", "realitySettings")))
        } else if (!sameProtocol && old["streamSettings"] != null) {
            val cleaned = patchObject(old.getValue("streamSettings"), buildJsonObject {},
                setOf("network", "security", "rawSettings", "tcpSettings", "wsSettings", "grpcSettings", "xhttpSettings", "splithttpSettings", "httpupgradeSettings", "kcpSettings", "tlsSettings", "realitySettings"))
            if (RawJson.members(cleaned).isEmpty()) {
                val fields = RawJson.members(target); fields.remove("streamSettings")
                target = RawJson.obj(*fields.map { it.key to it.value }.toTypedArray())
            } else target = RawJson.setting(target,"streamSettings",cleaned)
        }
        outbounds[index] = target
        return RawJson.setting(RawJson.setting(original, "outbounds", RawJson.array(outbounds)), "remarks", RawJson.quote(name.trim()))
    }
    companion object {
        val protocols = listOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")
        val networks = listOf("raw", "ws", "grpc", "xhttp", "httpupgrade", "kcp")
        val securities = listOf("none", "tls", "reality")
        val shadowsocksMethods = listOf("aes-256-gcm", "aes-128-gcm", "chacha20-poly1305", "xchacha20-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")
        fun from(profile: Profile): ServerForm? = runCatching {
            val root = Json.parseToJsonElement(profile.configuration).jsonObject
            val proxies = root.array("outbounds").map { it.jsonObject }.filter { it.text("protocol") !in ServerSummary.auxiliary }
            require(proxies.size == 1); val out = proxies.single(); require(out.text("protocol") in protocols)
            val rawSettings = out.obj("settings"); require(rawSettings.array("vnext").size <= 1 && rawSettings.array("servers").size <= 1)
            val server = ServerSummary.endpoint(out); require(server.array("users").size <= 1)
            val user = server.array("users").firstOrNull() as? JsonObject ?: server
            val stream = out.obj("streamSettings"); val net = stream.text("network", "raw").let { if (it == "tcp") "raw" else if (it == "splithttp") "xhttp" else it }; require(net in networks)
            val security = stream.text("security", "none"); require(security in securities)
            val tls = stream.obj("tlsSettings"); val reality = stream.obj("realitySettings"); val transport = stream.obj(net + "Settings")
            val header = (stream["rawSettings"] ?: stream["tcpSettings"])?.jsonObject?.obj("header") ?: buildJsonObject {}
            val rawRequest = header.obj("request")
            val host = if (net == "raw") rawRequest.obj("headers").array("Host").firstOrNull()?.jsonPrimitive?.content.orEmpty()
                else transport.text("host", (transport.obj("headers")["Host"] as? JsonPrimitive)?.content ?: "")
            val path = if (net == "raw") rawRequest.array("path").firstOrNull()?.jsonPrimitive?.content.orEmpty() else transport.text("path")
            ServerForm(profile.title, out.text("protocol"), server.text("address"), server.text("port", "443"), user.text("id"), user.text("password", user.text("pass")), user.text("user"), user.text("method", "aes-256-gcm"),
                user.text("security", "auto"), user.text("flow"), net, host, path, transport.text("serviceName"), transport.bool("multiMode"), transport.text("mode", "auto"), transport.text("seed"), header.text("type") == "http", security,
                if (security == "reality") reality.text("serverName") else tls.text("serverName"), tls.array("alpn").joinToString(",") { it.jsonPrimitive.content },
                if (security == "reality") reality.text("fingerprint") else tls.text("fingerprint"), reality.text("password", reality.text("publicKey")), reality.text("shortId"), reality.text("spiderX"))
        }.getOrNull()
    }
}

/** Replaces managed fields while retaining every untouched raw number and opaque subtree. */
internal fun patchObject(original: String, fresh: JsonObject, managed: Set<String>): String {
    val fields = RawJson.members(original)
    managed.filterNot(fresh::containsKey).forEach(fields::remove)
    for ((key, value) in fresh) {
        fields[key] = if (value is JsonObject && fields[key]?.trim()?.startsWith('{') == true) patchObject(fields.getValue(key), value, emptySet()) else value.toString()
    }
    return RawJson.obj(*fields.map { it.key to it.value }.toTypedArray())
}
