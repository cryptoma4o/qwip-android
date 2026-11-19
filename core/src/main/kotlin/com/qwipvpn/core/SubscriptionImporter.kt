package com.qwipvpn.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/** JSON is never passed through a share-link projection. */
class SubscriptionImporter(private val convertLink: (String) -> String) {
    fun parse(input: String, sourceId: String? = null, checkCanceled: () -> Unit = {}): List<Profile> {
        checkCanceled()
        require(input.toByteArray().size in 1..MAX_BYTES)
        var text = input.trim().removePrefix("\uFEFF").trim()
        if (!text.startsWith("{") && !text.startsWith("[") && !text.contains("://")) {
            val compact = text.filterNot(Char::isWhitespace)
            val bytes = runCatching { Base64.getDecoder().decode(compact) }.getOrElse { Base64.getUrlDecoder().decode(compact) }
            text = decodeUtf8(bytes).trim()
            require(text.toByteArray().size <= MAX_BYTES)
        }
        val configs = when {
            text.startsWith("{") -> listOf(text)
            text.startsWith("[") -> RawJson.elements(text).also { require(it.size <= MAX_PROFILES) }
            else -> {
                val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
                require(lines.size in 1..MAX_PROFILES)
                val profiles = lines.map { link ->
                    checkCanceled()
                    val uri = URI(link)
                    require(uri.scheme?.lowercase() in SCHEMES)
                    val converted = convertLink(link)
                    val name = uri.rawFragment?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
                    profile(converted, sourceId, name, sha256(link.substringBefore('#'))).copy(origin = "link")
                }
                require(profiles.map { it.originKey }.distinct().size == profiles.size) { "Duplicate locations" }
                return profiles
            }
        }
        require(configs.size in 1..MAX_PROFILES)
        val profiles = configs.map { checkCanceled(); profile(it, sourceId, null, null) }
        require(profiles.map { it.originKey }.distinct().size == profiles.size) { "Duplicate locations" }
        return profiles
    }
    private fun profile(config: String, sourceId: String?, name: String?, origin: String?): Profile {
        val raw = RawJson.members(config)
        val outbounds = raw["outbounds"]?.let(RawJson::elements) ?: error("Missing outbounds")
        require(outbounds.isNotEmpty())
        val metadata = Json.parseToJsonElement(config).jsonObject
        val first = Json.parseToJsonElement(outbounds.first()).jsonObject
        require(first["protocol"]?.jsonPrimitive?.contentOrNull != null)
        val title = sequenceOf(name, metadata["remarks"]?.jsonPrimitive?.contentOrNull,
            metadata["remark"]?.jsonPrimitive?.contentOrNull, first["remark"]?.jsonPrimitive?.contentOrNull,
            first["tag"]?.jsonPrimitive?.contentOrNull).firstOrNull { !it.isNullOrBlank() } ?: "Локация"
        val safeTitle = title.filterNot { it.isISOControl() }.take(100)
        // URI keys exclude renamed fragments. Full JSON profiles match their title + primary endpoint;
        // opaque routing/DNS edits on refresh keep favorites and selected identity.
        val endpoint = first["settings"]?.toString() ?: outbounds.first()
        return Profile(title = safeTitle, configuration = config, sourceId = sourceId,
            originKey = origin ?: sha256(safeTitle + "\n" + endpoint))
    }
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_PROFILES = 500
        val SCHEMES = setOf("vless", "vmess", "trojan", "ss", "socks", "socks5", "http", "https", "wireguard")
        fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
