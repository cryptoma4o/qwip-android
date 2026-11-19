package com.qwipvpn.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit, bounded requests. No configured URL is put into diagnostics or stored in a result. */
class TransferChecks(private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    suspend fun speed(url: String, maximumBytes: Int = 50_000_000, maximumMillis: Long = 8_000, progress: (Double, Long) -> Unit = { _, _ -> }): SpeedMeasurement = request { active, live ->
        require(maximumBytes in 1024..50_000_000 && maximumMillis in 500..15_000)
        val start = System.nanoTime(); val connection = connect(url, active, live); val meter = SpeedMeter()
        connection.readTimeout = 2000
        try {
            require(connection.responseCode == 200); val latency = ((System.nanoTime() - start) / 1_000_000).toInt()
            connection.inputStream.use { input ->
                val bytes = ByteArray(32_768)
                while (meter.total < maximumBytes && (System.nanoTime() - start) / 1_000_000 < maximumMillis) {
                    if (!live()) throw CancellationException()
                    val n = input.read(bytes, 0, minOf(bytes.size.toLong(), maximumBytes - meter.total).toInt()); if (n < 0) break
                    meter.record(n, (System.nanoTime() - start) / 1_000_000_000.0)
                    meter.megabits?.let { progress(it, meter.total) }
                }
            }
            SpeedMeasurement(megabits = meter.megabits ?: error("No measurement"), latency = latency, bytes = meter.total, seconds = meter.elapsed)
        } finally { active.compareAndSet(connection, null); connection.disconnect() }
    }
    suspend fun externalIp(url: String): String = request { active, live ->
        val connection = connect(url, active, live)
        try {
            require(connection.responseCode == 200); val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(1024)
                while (true) { if (!live()) throw CancellationException(); val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= 4096); out.write(chunk, 0, n) }
            }
            SubscriptionImporter.decodeUtf8(out.toByteArray()).trim().also { require(it.length <= 45 && RuntimeConfiguration.isIpLiteral(it)) }
        } finally { active.compareAndSet(connection, null); connection.disconnect() }
    }
    private fun connect(text: String, active: AtomicReference<HttpURLConnection?>, live: () -> Boolean): HttpURLConnection {
        var uri = SubscriptionHttp.validateUrl(text)
        for (redirect in 0..5) {
            if (!live()) throw CancellationException()
            val connection = open(uri); active.set(connection)
            try {
                if (!live()) throw CancellationException()
                connection.instanceFollowRedirects = false; connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("User-Agent", "QWIP/0.1.0 Android")
                if (connection.responseCode in listOf(301,302,303,307,308)) {
                    require(redirect < 5); uri = SubscriptionHttp.validateUrl(uri.resolve(connection.getHeaderField("Location") ?: error("Redirect missing")).toString())
                    connection.disconnect(); active.compareAndSet(connection, null); continue
                }
                return connection
            } catch (e: Exception) { connection.disconnect(); active.compareAndSet(connection, null); throw e }
        }
        error("Redirect limit")
    }
    private suspend fun <T> request(work: (AtomicReference<HttpURLConnection?>, () -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = workers.submit {
            try { val result = work(active) { continuation.isActive }; if (continuation.isActive) continuation.resume(result) }
            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
        continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
    }
    companion object { private val workers = Executors.newFixedThreadPool(2) { Thread(it, "QWIP-Transfer").apply { isDaemon = true } } }
}
