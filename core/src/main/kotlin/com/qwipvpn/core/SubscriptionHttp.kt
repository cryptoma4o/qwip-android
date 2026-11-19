package com.qwipvpn.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpDocument(val body: String?, val etag: String?, val modified: String?, val title: String?, val usage: SubscriptionUsage?, val updateHours: Int? = null)

/** HTTPS-only, bounded after decompression; cancellation disconnects the active socket. */
class SubscriptionHttp(private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    suspend fun fetch(source: Source, headers: Map<String, String> = emptyMap()): HttpDocument = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = pool.submit {
            try {
                val deadline = System.nanoTime() + 45_000_000_000L
                var url = requestUrl(source)
                var response: HttpDocument? = null
                for (redirect in 0..8) {
                    if (!continuation.isActive) throw CancellationException()
                    val connection = openConnection(url)
                    active.set(connection)
                    try {
                        if (!continuation.isActive) throw CancellationException()
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                        connection.setRequestProperty("User-Agent", "QWIP/0.1.0 Android")
                        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                        connection.setRequestProperty("Accept-Encoding", "gzip")
                        // Validators belong only to the original resource, never a redirect target.
                        if (redirect == 0) {
                            require(headers.keys.all { it in setOf("X-HWID", "X-Device-OS", "X-Ver-OS", "X-Device-Model", "X-Age-Public-Key") })
                            headers.forEach { (key, value) -> require(value.length <= (if(key=="X-Age-Public-Key")4096 else 1024) && value.none(Char::isISOControl)); connection.setRequestProperty(key, value) }
                            source.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                            source.modified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                        }
                        val code = connection.responseCode
                        if (code in listOf(301,302,303,307,308)) {
                            require(redirect < 8)
                            url = validateUrl(url.resolve(connection.getHeaderField("Location") ?: error("Redirect missing")).toString())
                            continue
                        }
                        require(code == 200 || code == 304 && redirect == 0)
                        require(connection.contentLengthLong <= SubscriptionImporter.MAX_BYTES || connection.contentLengthLong < 0)
                        val body = if (code == 304) null else {
                            val raw = connection.inputStream
                            val stream = if (connection.contentEncoding?.equals("gzip", true) == true) GZIPInputStream(raw) else raw
                            stream.use {
                                val bytes = ByteArrayOutputStream()
                                val chunk = ByteArray(16 * 1024)
                                while (true) {
                                    if (!continuation.isActive) throw CancellationException()
                                    require(System.nanoTime() < deadline)
                                    val count = it.read(chunk)
                                    if (count < 0) break
                                    require(bytes.size() + count <= SubscriptionImporter.MAX_BYTES)
                                    bytes.write(chunk,0,count)
                                }
                                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                            }
                        }
                        // A redirected resource's validators must not be persisted against the original URL.
                        response = HttpDocument(body,
                            if (redirect == 0) connection.getHeaderField("ETag") else null,
                            if (redirect == 0) connection.getHeaderField("Last-Modified") else null,
                            connection.getHeaderField("profile-title")?.take(200)?.let(::decodeTitle),
                            parseUsage(connection.getHeaderField("subscription-userinfo")),
                            connection.getHeaderField("profile-update-interval")?.toIntOrNull()?.takeIf { it in 1..720 })
                        break
                    } finally { active.compareAndSet(connection, null); connection.disconnect() }
                }
                if (continuation.isActive) continuation.resume(response ?: error("No response"))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
    }
    companion object {
        private val pool = Executors.newFixedThreadPool(2) { task -> Thread(task,"QWIP-HTTP").apply { isDaemon = true } }
        fun validateUrl(text: String): URI = URI(text.trim()).also {
            require(it.scheme?.lowercase() == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.fragment == null)
            require(text.length <= 8192)
        }
        /** Resolve official handoffs locally, retaining the exact URL for other providers. */
        fun importUrl(text: String): String {
            val uri = URI(text)
            if (uri.host.equals("www.qwipvpn.com", ignoreCase = true) && uri.rawPath == "/open") {
                val path = uri.rawFragment.orEmpty()
                require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
                    uri.port == -1 && uri.rawQuery == null && Regex("[ds]/[0-9a-f]{64}").matches(path))
                return "https://qwipvpn.com/$path?format=xray"
            }
            validateUrl(text)
            return text
        }
        fun requestUrl(source:Source):URI {
            val uri=validateUrl(source.url)
            val query=uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).toMutableList()
            var path=uri.rawPath.orEmpty()
            val host = uri.host.lowercase()
            val qwipFeed = host == "qwipvpn.com" && (path.startsWith("/d/") || path.startsWith("/s/"))
            val legacyFeed = host in setOf("notvpn.vip", "www.notvpn.vip") && path.startsWith("/s/")
            if (qwipFeed || legacyFeed) {
                query.removeAll{part->runCatching{java.net.URLDecoder.decode(part.substringBefore('='),"UTF-8")}.getOrNull() in setOf("format","client")};query+="format=xray"
            }else if(source.format=="xray-json"&&!path.endsWith("/json"))path=path.trimEnd('/')+"/json"
            return URI(uri.scheme+"://"+uri.rawAuthority+path+if(query.isEmpty())""else"?"+query.joinToString("&"))
        }
        fun parseUsage(text: String?): SubscriptionUsage? {
            if (text == null) return null
            val values = text.split(';').mapNotNull { part ->
                val p = part.trim().split('=',limit=2)
                if (p.size != 2) null else p[1].toLongOrNull()?.takeIf { it >= 0 }?.let { p[0].lowercase() to it }
            }.toMap()
            return SubscriptionUsage(values["upload"] ?: 0,values["download"] ?: 0,values["total"]?.takeIf { it > 0 },values["expire"]?.takeIf { it > 0 })
        }
        private fun decodeTitle(value: String): String {
            val text = if (value.startsWith("base64:")) runCatching { String(java.util.Base64.getDecoder().decode(value.removePrefix("base64:")),Charsets.UTF_8) }.getOrDefault("Подписка") else value
            return text.filterNot(Char::isISOControl).take(100).ifBlank { "Подписка" }
        }
    }
}
