package com.qwipvpn.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

data class ProbeResult(val profileId: String, val state: String, val milliseconds: Int? = null, val measuredAt: Long = System.currentTimeMillis(), val method:String="tcp") {
    fun fresh(now:Long=System.currentTimeMillis())=now-measuredAt in 0 until ServerProbes.CACHE_TTL_MS
}
fun interface ProxyProbe { suspend fun measure(profiles: List<Profile>, timeout: Int, url: String): List<Int?> }

/** Select the same proxy that ServerSummary describes, preserving every outbound dependency. */
data class ProxyProbeConfiguration(val configuration:String,val outboundTag:String) {
    companion object {
        fun from(profile:Profile):ProxyProbeConfiguration {
            val outbounds=RawJson.elements(RawJson.members(profile.configuration).getValue("outbounds")).toMutableList()
            val fields=outbounds.map(RawJson::members)
            fun text(value:String?)=value?.let{Json.parseToJsonElement(it).jsonPrimitive.content}.orEmpty()
            val index=fields.indexOfFirst{text(it["protocol"]) !in ServerSummary.auxiliary}
            require(index>=0)
            val tags=fields.map{text(it["tag"])}.toSet()
            val tag=text(fields[index]["tag"]).ifBlank{
                generateSequence("__qwip_probe"){it+"_"}.first{it !in tags}.also{
                    outbounds[index]=RawJson.setting(outbounds[index],"tag",RawJson.quote(it))
                }
            }
            return ProxyProbeConfiguration(RawJson.obj("outbounds" to RawJson.array(outbounds)),tag)
        }
    }
}

/** Cache keys include the complete config and method. A network change invalidates pending results. */
class ServerProbes(private val proxy: ProxyProbe, private val tcp: suspend (String, Int, Int) -> Int? = {host,port,timeout->tcpLatency(host,port,timeout)}) {
    private val cache = mutableMapOf<String, ProbeResult>()
    @Volatile private var generation = 0
    @Synchronized fun invalidate() { generation++; cache.clear() }
    private fun key(profile: Profile, settings: ClientSettings) = signature(profile,settings)
    suspend fun check(profiles: List<Profile>, settings: ClientSettings, force: Boolean = false, progress: suspend (List<ProbeResult>) -> Unit = {}): List<ProbeResult> = coroutineScope {
        require(settings.probeTimeout in 1..15); SubscriptionHttp.validateUrl(settings.probeUrl)
        val current = generation; val results = mutableListOf<ProbeResult>(); val pending = mutableListOf<Profile>()
        for (profile in profiles) {
            val cached = synchronized(this@ServerProbes) { cache[key(profile, settings)] }
            if (!force && cached != null && cached.fresh()) results += cached else pending += profile
        }
        progress(results.toList())
        for (batch in pending.chunked(5)) {
            ensureActive(); if (generation != current) throw CancellationException()
            val measured = if (settings.probeMethod == "tcp") batch.map { profile -> async {
                val summary = ServerSummary.read(profile)
                if (summary?.address == null || summary.port == null) ProbeResult(profile.id, "unsupported",method=settings.probeMethod)
                else {
                    val first = tcp(summary.address, summary.port, settings.probeTimeout)
                    val delay = if (settings.probeDouble) best(first, tcp(summary.address, summary.port, settings.probeTimeout)) else first
                    ProbeResult(profile.id, if (delay == null) "unavailable" else "reachable", delay,method=settings.probeMethod)
                }
            } }.awaitAll() else {
                val supported = batch.filter { ServerSummary.read(it)?.protocol?.lowercase() in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2") }
                val replies = if (supported.isEmpty()) emptyList() else proxy.measure(supported, settings.probeTimeout, settings.probeUrl)
                require(replies.size == supported.size)
                val again = if (settings.probeDouble && supported.isNotEmpty()) proxy.measure(supported, settings.probeTimeout, settings.probeUrl) else replies
                require(again.size == replies.size)
                val values = supported.mapIndexed { i, p -> p.id to best(replies[i], again[i]) }.toMap()
                batch.map { p -> if (!values.containsKey(p.id)) ProbeResult(p.id, "unsupported",method=settings.probeMethod) else values[p.id].let { ProbeResult(p.id, if (it == null) "unavailable" else "reachable", it,method=settings.probeMethod) } }
            }
            ensureActive(); if (generation != current) throw CancellationException()
            synchronized(this@ServerProbes) { if(generation!=current)throw CancellationException();measured.forEach { result -> cache[key(batch.first { it.id == result.profileId }, settings)] = result }; if (cache.size > 1000) cache.keys.take(cache.size - 1000).forEach(cache::remove) }
            results += measured; progress(results.toList())
        }
        results
    }
    companion object {
        const val CACHE_TTL_MS=300_000L
        fun signature(profile:Profile,settings:ClientSettings)=listOf(profile.id,SubscriptionImporter.sha256(profile.configuration),settings.probeMethod,settings.probeUrl,settings.probeTimeout.toString(),settings.probeDouble.toString()).joinToString("\u0000")
        private val workers = Executors.newFixedThreadPool(5) { Thread(it, "QWIP-Ping").apply { isDaemon = true } }
        private val semaphore = Semaphore(5)
        private fun best(first: Int?, second: Int?) = listOfNotNull(first, second).minOrNull()
        suspend fun tcpLatency(host: String, port: Int, timeout: Int, socketFactory:()->Socket={Socket()},resolve:(String,Int)->InetSocketAddress={h,p->InetSocketAddress(h,p)}): Int? = semaphore.withPermit {
            withTimeoutOrNull(timeout * 1000L + 100) { suspendCancellableCoroutine { continuation ->
                val socket = AtomicReference<Socket?>()
                val future = workers.submit {
                    val result = runCatching {
                        val connection = socketFactory(); socket.set(connection)
                        if (!continuation.isActive) { connection.close(); throw CancellationException() }
                        val address = resolve(host, port)
                        if (!continuation.isActive) throw CancellationException()
                        val start = System.nanoTime()
                        connection.connect(address, timeout * 1000)
                        ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(0)
                    }.getOrNull()
                    runCatching { socket.getAndSet(null)?.close() }
                    if (continuation.isActive) continuation.resume(result)
                }
                continuation.invokeOnCancellation { runCatching { socket.getAndSet(null)?.close() }; future.cancel(true) }
            } }
        }
    }
}

object ServerChooser {
    const val CANDIDATE_LIMIT = 24
    fun fresh(ids: List<String>, results: Map<String, ProbeResult>, now: Long = System.currentTimeMillis()) = ids.all { results[it]?.let { r -> now - r.measuredAt in 0..120_000 } == true }
    fun best(ids: List<String>, results: Map<String, ProbeResult>, current: String?, failures: Map<String, Long> = emptyMap(), now: Long = System.currentTimeMillis()): String? {
        val reachable = ids.filter { id -> results[id]?.let { it.state == "reachable" && it.milliseconds != null && it.milliseconds >= 0 && now-it.measuredAt in 0..120_000 } == true }
        val healthy = reachable.filter { failures[it]?.let { time -> now - time > 600_000 } ?: true }.ifEmpty { reachable }
        val fastest = healthy.minByOrNull { results.getValue(it).milliseconds!! } ?: return null
        return current?.takeIf { it in healthy && results.getValue(it).milliseconds!! <= results.getValue(fastest).milliseconds!! + 40 } ?: fastest
    }
}

data class ProfileGroup(val source: Source?, val profiles: List<Profile>) { val id get() = source?.id ?: "device" }
object ProfileGroups {
    fun groups(store: ClientStore, probes: Map<String, ProbeResult> = emptyMap()): List<ProfileGroup> {
        fun sorted(profiles: List<Profile>) = profiles.sortedWith(compareByDescending<Profile> { it.favorite }.thenComparator { a, b -> when (store.settings.serverSort) {
            "name" -> a.title.compareTo(b.title, ignoreCase = true)
            "latency" -> (probes[a.id]?.milliseconds ?: Int.MAX_VALUE).compareTo(probes[b.id]?.milliseconds ?: Int.MAX_VALUE)
            else -> 0
        } })
        return buildList {
            store.profiles.filter { it.sourceId == null }.takeIf(List<Profile>::isNotEmpty)?.let { add(ProfileGroup(null, sorted(it))) }
            store.sources.forEach { source -> add(ProfileGroup(source, sorted(store.profiles.filter { it.sourceId == source.id }))) }
        }
    }
}
