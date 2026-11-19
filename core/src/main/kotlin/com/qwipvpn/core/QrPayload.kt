package com.qwipvpn.core

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Decode to an import draft only. A QR code never opens a browser or makes a request. */
object QrPayload {
    private val links = setOf("vless","vmess","trojan","ss","socks","socks5","wireguard")
    fun normalize(raw: String): String = normalize(raw,0)
    private fun normalize(raw: String,depth: Int): String {
        require(depth <= 2 && raw.toByteArray().size in 1..65_536)
        val value=raw.trim().removePrefix("\uFEFF").trim()
        if (value.startsWith('{') || value.startsWith('[')) { RawJson.validate(value);return value }
        val lines=value.lines().map(String::trim).filter(String::isNotEmpty)
        if(lines.size>1 && lines.all { runCatching { URI(it).scheme?.lowercase() in links }.getOrDefault(false) })return value
        val uri=runCatching { URI(value) }.getOrNull()
        val scheme=uri?.scheme?.lowercase()
        if (scheme == "https") return SubscriptionHttp.importUrl(value)
        if (scheme in links) return value
        if(scheme in setOf("qwip","happ","notvpn")&&uri?.host=="routing"){RoutingLinks.parse(value);return value}
        if (scheme in setOf("qwip","happ","notvpn") && uri?.host in setOf("add","import")) {
            val hasPath=!uri!!.rawPath.isNullOrEmpty() && uri.rawPath!="/"
            val query=if(!hasPath) uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=')=="url" }?.substringAfter('=',"") else null
            val target=if(hasPath) value.substring(value.indexOf('/',value.indexOf("://")+3)+1) else query ?: error("QR import URL missing")
            require(target.isNotBlank())
            val alreadyUri=hasPath && runCatching { URI(target).scheme!=null }.getOrDefault(false)
            return normalize(if(alreadyUri)target else URLDecoder.decode(target.replace("+","%2B"),"UTF-8"),depth+1)
        }
        require(scheme==null)
        val compact=value.filterNot(Char::isWhitespace)
        val decoded=runCatching { Base64.getDecoder().decode(compact) }.getOrElse { Base64.getUrlDecoder().decode(compact) }
        val text=SubscriptionImporter.decodeUtf8(decoded)
        require(text != value)
        return normalize(text,depth+1)
    }
}
