package com.qwipvpn.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

enum class DiagnosticGroup { ALLOWED, DOMESTIC_CONTROL, EXTERNAL_CONTROL }
data class DiagnosticTarget(val id: String, val title: String, val url: URI, val group: DiagnosticGroup)
object DiagnosticCatalog {
    const val VERSION = "2026-10-29.2"
    val targets = listOf(
        DiagnosticTarget("yandex","Яндекс",URI("https://ya.ru/"),DiagnosticGroup.ALLOWED),
        DiagnosticTarget("gosuslugi","Госуслуги",URI("https://www.gosuslugi.ru/"),DiagnosticGroup.ALLOWED),
        DiagnosticTarget("beeline","Билайн",URI("https://moskva.beeline.ru/"),DiagnosticGroup.ALLOWED),
        DiagnosticTarget("nic","RU-CENTER",URI("https://www.nic.ru/"),DiagnosticGroup.DOMESTIC_CONTROL),
        DiagnosticTarget("selectel","Selectel",URI("https://selectel.ru/"),DiagnosticGroup.DOMESTIC_CONTROL),
        DiagnosticTarget("example","Example",URI("https://example.com/"),DiagnosticGroup.EXTERNAL_CONTROL),
        DiagnosticTarget("wikipedia","Wikipedia",URI("https://www.wikipedia.org/"),DiagnosticGroup.EXTERNAL_CONTROL),
        DiagnosticTarget("test-ipv6","Test IPv6",URI("https://test-ipv6.com/"),DiagnosticGroup.EXTERNAL_CONTROL),
        DiagnosticTarget("github","GitHub",URI("https://github.com/"),DiagnosticGroup.EXTERNAL_CONTROL)
    )
}
enum class DiagnosticOutcome { REACHABLE, DNS_FAILURE, CONNECTION_FAILURE, TIMEOUT, TLS_FAILURE, NOT_MEASURED, CANCELLED;
    val restrictionEvidence get() = this in listOf(DNS_FAILURE,CONNECTION_FAILURE,TIMEOUT)
}
data class DiagnosticMeasurement(val target: DiagnosticTarget,val outcome: DiagnosticOutcome,val milliseconds: Long = 0,val httpStatus: Int? = null)
data class DiagnosticRound(val measurements: List<DiagnosticMeasurement>)
enum class DiagnosticVerdict { NO_INDICATION, POSSIBLE_ALLOWLIST, PARTIAL_CONNECTIVITY, INTERNET_UNAVAILABLE, DNS_PROBLEM, INCONCLUSIVE }
data class DiagnosticReport(val verdict: DiagnosticVerdict,val rounds: List<DiagnosticRound>,val measured: Long = System.currentTimeMillis()) {
    val measurements get() = rounds.lastOrNull()?.measurements.orEmpty()
}
data class DiagnosticProgress(val round: Int,val measurements: List<DiagnosticMeasurement>,val total: Int)

object DiagnosticClassifier {
    private fun complete(round: DiagnosticRound,targets: List<DiagnosticTarget>): Boolean = targets.isNotEmpty() &&
        targets.distinctBy { it.id }.size == targets.size && round.measurements.size == targets.size &&
        round.measurements.map { it.target.id }.toSet() == targets.map { it.id }.toSet() &&
        round.measurements.all { it.target in targets }
    private fun pattern(round: DiagnosticRound,targets: List<DiagnosticTarget>): Boolean {
        if (!complete(round,targets)) return false
        val allowed=round.measurements.filter { it.target.group==DiagnosticGroup.ALLOWED }
        val domestic=round.measurements.filter { it.target.group==DiagnosticGroup.DOMESTIC_CONTROL }
        val external=round.measurements.filter { it.target.group==DiagnosticGroup.EXTERNAL_CONTROL }
        return allowed.size>=3 && allowed.count { it.outcome==DiagnosticOutcome.REACHABLE }>=2 && domestic.size>=2 && external.size>=2 &&
            (domestic+external).all { it.outcome.restrictionEvidence }
    }
    fun evaluate(rounds: List<DiagnosticRound>,targets: List<DiagnosticTarget> = DiagnosticCatalog.targets): DiagnosticVerdict {
        val latest=rounds.lastOrNull() ?: return DiagnosticVerdict.INCONCLUSIVE
        if (!complete(latest,targets)) return DiagnosticVerdict.INCONCLUSIVE
        val values=latest.measurements
        if (values.all { it.outcome==DiagnosticOutcome.REACHABLE }) return DiagnosticVerdict.NO_INDICATION
        if (values.all { it.outcome==DiagnosticOutcome.DNS_FAILURE }) return DiagnosticVerdict.DNS_PROBLEM
        if (rounds.size>=2 && rounds.takeLast(2).all { pattern(it,targets) }) return DiagnosticVerdict.POSSIBLE_ALLOWLIST
        if (pattern(latest,targets)) return DiagnosticVerdict.INCONCLUSIVE
        if (values.any { it.outcome==DiagnosticOutcome.REACHABLE }) return DiagnosticVerdict.PARTIAL_CONNECTIVITY
        if (values.any { it.outcome in listOf(DiagnosticOutcome.TLS_FAILURE,DiagnosticOutcome.NOT_MEASURED,DiagnosticOutcome.CANCELLED) }) return DiagnosticVerdict.INCONCLUSIVE
        return DiagnosticVerdict.INTERNET_UNAVAILABLE
    }
    fun needsConfirmation(round: DiagnosticRound,targets: List<DiagnosticTarget> = DiagnosticCatalog.targets) =
        complete(round,targets) && (pattern(round,targets) || round.measurements.none { it.outcome==DiagnosticOutcome.REACHABLE })
}

fun interface DiagnosticProber { suspend fun measure(target: DiagnosticTarget,timeoutMillis: Long): DiagnosticMeasurement }

class NetworkDiagnostics(private val prober: DiagnosticProber,private val targets: List<DiagnosticTarget> = DiagnosticCatalog.targets,
                         private val confirmationDelayMillis: Long = 1000) {
    suspend fun run(isCurrent: () -> Boolean,onProgress: suspend (DiagnosticProgress) -> Unit = {}): DiagnosticReport {
        suspend fun requireCurrent() { currentCoroutineContext().ensureActive(); if(!isCurrent())throw CancellationException() }
        suspend fun round(number: Int): DiagnosticRound {
            requireCurrent()
            val values=mutableListOf<DiagnosticMeasurement>()
            onProgress(DiagnosticProgress(number,emptyList(),targets.size))
            for(batch in targets.chunked(4)) {
                requireCurrent()
                values += coroutineScope { batch.map { target -> async { prober.measure(target,4000) } }.awaitAll() }
                requireCurrent()
                if(values.any { it.outcome==DiagnosticOutcome.CANCELLED }) throw CancellationException()
                onProgress(DiagnosticProgress(number,targets.mapNotNull { target -> values.firstOrNull { it.target.id==target.id } },targets.size))
            }
            return DiagnosticRound(targets.mapNotNull { target -> values.firstOrNull { it.target.id==target.id } })
        }
        val rounds=mutableListOf(round(1))
        if(DiagnosticClassifier.needsConfirmation(rounds.first(),targets)) {
            delay(confirmationDelayMillis); requireCurrent(); rounds+=round(2)
        }
        requireCurrent()
        return DiagnosticReport(DiagnosticClassifier.evaluate(rounds,targets),rounds)
    }
}

/** Response headers after ordinary platform TLS validation prove reachability, including HTTP 403/404.
 * A fixed, bounded worker pool and a separate deadline also bound DNS stalls. A queued probe that
 * never started is NOT_MEASURED, so worker starvation cannot become evidence of an allowlist. */
class DiagnosticHttpProbe(private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }): DiagnosticProber {
    override suspend fun measure(target: DiagnosticTarget,timeoutMillis: Long): DiagnosticMeasurement = suspendCancellableCoroutine { continuation ->
        val start=System.nanoTime()
        if(target.url.scheme!="https" || target.url.host.isNullOrBlank() || target.url.rawUserInfo!=null || target.url.fragment!=null || timeoutMillis !in 1..10_000) {
            continuation.resume(DiagnosticMeasurement(target,DiagnosticOutcome.NOT_MEASURED)); return@suspendCancellableCoroutine
        }
        val done=AtomicBoolean(false); val started=AtomicBoolean(false)
        val active=AtomicReference<HttpURLConnection?>(); val worker=AtomicReference<Future<*>?>(); val deadline=AtomicReference<ScheduledFuture<*>?>()
        fun finish(outcome: DiagnosticOutcome,status: Int? = null) {
            if(done.compareAndSet(false,true)) {
                deadline.get()?.cancel(false)
                if(continuation.isActive) continuation.resume(DiagnosticMeasurement(target,outcome,(System.nanoTime()-start)/1_000_000,status))
            }
        }
        fun stop() {
            worker.get()?.let { it.cancel(true); if(it is Runnable) pool.remove(it) }
            active.getAndSet(null)?.disconnect()
        }
        continuation.invokeOnCancellation { done.set(true); deadline.get()?.cancel(false); stop() }
        deadline.set(clock.schedule({
            finish(if(started.get())DiagnosticOutcome.TIMEOUT else DiagnosticOutcome.NOT_MEASURED); stop()
        },timeoutMillis,TimeUnit.MILLISECONDS))
        if(done.get()) { deadline.get()?.cancel(false); return@suspendCancellableCoroutine }
        try {
            val future=pool.submit {
                if(done.get())return@submit
                started.set(true)
                var connection: HttpURLConnection?=null
                try {
                    connection=openConnection(target.url); active.set(connection)
                    if(done.get())return@submit
                    connection.instanceFollowRedirects=false; connection.useCaches=false
                    connection.connectTimeout=timeoutMillis.toInt(); connection.readTimeout=timeoutMillis.toInt()
                    connection.requestMethod="HEAD"
                    connection.setRequestProperty("Cache-Control","no-cache, no-store")
                    connection.setRequestProperty("User-Agent","QWIP-Network-Diagnostics/1")
                    val status=connection.responseCode
                    finish(if(status in 100..599)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.NOT_MEASURED,status)
                } catch(_: UnknownHostException) { finish(DiagnosticOutcome.DNS_FAILURE) }
                catch(_: SocketTimeoutException) { finish(DiagnosticOutcome.TIMEOUT) }
                catch(_: SSLException) { finish(DiagnosticOutcome.TLS_FAILURE) }
                catch(_: IOException) { finish(DiagnosticOutcome.CONNECTION_FAILURE) }
                catch(_: Exception) { finish(DiagnosticOutcome.NOT_MEASURED) }
                finally { active.compareAndSet(connection,null); connection?.disconnect() }
            }
            worker.set(future)
            if(done.get())stop()
        } catch(_: RejectedExecutionException) { finish(DiagnosticOutcome.NOT_MEASURED) }
    }
    companion object {
        private val pool=ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4),
            ThreadFactory { task -> Thread(task,"QWIP-network-probe").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
        private val clock=ScheduledThreadPoolExecutor(1) { task -> Thread(task,"QWIP-probe-deadline").apply { isDaemon=true } }.apply { removeOnCancelPolicy=true }
    }
}
