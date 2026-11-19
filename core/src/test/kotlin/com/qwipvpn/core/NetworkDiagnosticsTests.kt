package com.qwipvpn.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import kotlin.test.*

class NetworkDiagnosticsTests {
    private val targets=DiagnosticCatalog.targets
    private fun round(outcome: (DiagnosticTarget) -> DiagnosticOutcome)=DiagnosticRound(targets.map { DiagnosticMeasurement(it,outcome(it)) })
    private fun restricted()=round { if(it.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TIMEOUT }
    private fun target(path: String)=DiagnosticTarget("synthetic","Synthetic",URI("https://diagnostic.example.invalid/$path"),DiagnosticGroup.EXTERNAL_CONTROL)

    @Test fun requiresTwoCompleteMatchingRounds() {
        assertEquals(DiagnosticVerdict.INCONCLUSIVE,DiagnosticClassifier.evaluate(listOf(restricted())))
        assertTrue(DiagnosticClassifier.needsConfirmation(restricted()))
        assertEquals(DiagnosticVerdict.POSSIBLE_ALLOWLIST,DiagnosticClassifier.evaluate(listOf(restricted(),restricted())))
    }
    @Test fun aRecoveredControlSiteInvalidatesThePattern() {
        val recovered=round { if(it.group==DiagnosticGroup.ALLOWED||it.id=="github")DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TIMEOUT }
        assertEquals(DiagnosticVerdict.PARTIAL_CONNECTIVITY,DiagnosticClassifier.evaluate(listOf(restricted(),recovered)))
    }
    @Test fun foreignRouteFailureIsNotAnAllowlist() {
        val failed=round { if(it.group==DiagnosticGroup.EXTERNAL_CONTROL)DiagnosticOutcome.TIMEOUT else DiagnosticOutcome.REACHABLE }
        assertEquals(DiagnosticVerdict.PARTIAL_CONNECTIVITY,DiagnosticClassifier.evaluate(listOf(failed,failed)))
        assertFalse(DiagnosticClassifier.needsConfirmation(failed))
    }
    @Test fun distinguishesOutageDNSAndTLS() {
        assertEquals(DiagnosticVerdict.INTERNET_UNAVAILABLE,DiagnosticClassifier.evaluate(listOf(round { DiagnosticOutcome.TIMEOUT })))
        assertEquals(DiagnosticVerdict.DNS_PROBLEM,DiagnosticClassifier.evaluate(listOf(round { DiagnosticOutcome.DNS_FAILURE })))
        assertEquals(DiagnosticVerdict.INCONCLUSIVE,DiagnosticClassifier.evaluate(listOf(round { DiagnosticOutcome.TLS_FAILURE })))
    }
    @Test fun certificateErrorsCannotProveAnAllowlist() {
        val tls=round { if(it.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TLS_FAILURE }
        assertNotEquals(DiagnosticVerdict.POSSIBLE_ALLOWLIST,DiagnosticClassifier.evaluate(listOf(tls,tls)))
    }
    @Test fun incompleteAndDuplicateResultsAreInconclusive() {
        assertEquals(DiagnosticVerdict.INCONCLUSIVE,DiagnosticClassifier.evaluate(listOf(DiagnosticRound(restricted().measurements.dropLast(1)))))
        val duplicate=restricted().measurements.toMutableList().apply { this[lastIndex]=first() }
        assertEquals(DiagnosticVerdict.INCONCLUSIVE,DiagnosticClassifier.evaluate(listOf(DiagnosticRound(duplicate))))
    }
    @Test fun unavailableWorkerIsNotNetworkRestrictionEvidence() {
        val unknown=round { if(it.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.NOT_MEASURED }
        assertNotEquals(DiagnosticVerdict.POSSIBLE_ALLOWLIST,DiagnosticClassifier.evaluate(listOf(unknown,unknown)))
    }
    @Test fun normalNetworkRunsNineProbesOnce()=runBlocking {
        val calls=AtomicInteger(); val active=AtomicInteger(); val peak=AtomicInteger()
        val probe=DiagnosticProber { value,_ ->
            calls.incrementAndGet(); val count=active.incrementAndGet(); peak.updateAndGet { maxOf(it,count) }
            delay(2); active.decrementAndGet(); DiagnosticMeasurement(value,DiagnosticOutcome.REACHABLE,httpStatus=403)
        }
        val result=NetworkDiagnostics(probe,confirmationDelayMillis=0).run({true})
        assertEquals(DiagnosticVerdict.NO_INDICATION,result.verdict)
        assertEquals(1,result.rounds.size); assertEquals(9,calls.get()); assertTrue(peak.get()<=4)
    }
    @Test fun restrictedNetworkRunsTwoSeries()=runBlocking {
        val calls=AtomicInteger()
        val probe=DiagnosticProber { value,_ -> calls.incrementAndGet(); DiagnosticMeasurement(value,if(value.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TIMEOUT) }
        val result=NetworkDiagnostics(probe,confirmationDelayMillis=0).run({true})
        assertEquals(DiagnosticVerdict.POSSIBLE_ALLOWLIST,result.verdict)
        assertEquals(2,result.rounds.size); assertEquals(18,calls.get())
    }
    @Test fun aChangedNetworkStopsBeforeAnotherBatch()=runBlocking {
        var current=true; val calls=AtomicInteger()
        val probe=DiagnosticProber { value,_ -> calls.incrementAndGet(); DiagnosticMeasurement(value,DiagnosticOutcome.TIMEOUT) }
        assertFailsWith<CancellationException> {
            NetworkDiagnostics(probe,confirmationDelayMillis=0).run({current}) { if(it.measurements.isNotEmpty())current=false }
        }
        assertTrue(calls.get()<=4)
    }
    @Test fun aVPNOrUnavailableNetworkPreventsRequests()=runBlocking {
        val calls=AtomicInteger()
        val probe=DiagnosticProber { value,_ -> calls.incrementAndGet(); DiagnosticMeasurement(value,DiagnosticOutcome.REACHABLE) }
        assertFailsWith<CancellationException> { NetworkDiagnostics(probe).run({false}) }
        assertEquals(0,calls.get())
    }
    @Test fun HTTPErrorResponsesStillProveReachability()=runBlocking {
        for(code in listOf(200,301,403,404,423,429,503)) {
            val connection=FakeConnection(code)
            val value=DiagnosticHttpProbe { connection }.measure(target("$code"),1000)
            assertEquals(DiagnosticOutcome.REACHABLE,value.outcome)
            assertEquals(code,value.httpStatus)
            assertEquals("HEAD",connection.requestMethod)
            assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
            assertNull(connection.getRequestProperty("Authorization"))
        }
    }
    @Test fun DNSAndCertificateErrorsRemainSeparate()=runBlocking {
        val dns=DiagnosticHttpProbe { throw UnknownHostException() }.measure(target("dns"),1000)
        val tls=DiagnosticHttpProbe { throw SSLHandshakeException("synthetic") }.measure(target("tls"),1000)
        assertEquals(DiagnosticOutcome.DNS_FAILURE,dns.outcome)
        assertEquals(DiagnosticOutcome.TLS_FAILURE,tls.outcome)
    }
    @Test fun deadlineAndCancellationDisconnectStalledRequests()=runBlocking {
        val first=StalledConnection()
        val result=DiagnosticHttpProbe { first }.measure(target("timeout"),50)
        assertEquals(DiagnosticOutcome.TIMEOUT,result.outcome)
        withTimeout(1000) { while(!first.disconnected)delay(1) }
        val second=StalledConnection()
        val job=async { DiagnosticHttpProbe { second }.measure(target("cancel"),4000) }
        withTimeout(1000) { while(!second.started)delay(1) }
        job.cancelAndJoin()
        assertTrue(second.disconnected)
    }
    @Test fun catalogContainsSecureIndependentControlNames()=runBlocking {
        assertEquals(9,targets.size); assertEquals(9,targets.map { it.url.host }.toSet().size)
        assertTrue(targets.all { it.url.scheme=="https"&&it.url.userInfo==null })
        assertEquals(DiagnosticGroup.EXTERNAL_CONTROL,targets.first { it.id=="test-ipv6" }.group)
        assertEquals(DiagnosticGroup.EXTERNAL_CONTROL,targets.first { it.id=="github" }.group)
        val invalid=target("invalid").copy(url=URI("http://diagnostic.example.invalid/"))
        assertEquals(DiagnosticOutcome.NOT_MEASURED,DiagnosticHttpProbe { error("Must not open an insecure URL") }.measure(invalid,1000).outcome)
    }

    private open class FakeConnection(private val status: Int): HttpURLConnection(URL("https://diagnostic.example.invalid/")) {
        @Volatile var disconnected=false
        override fun connect() {}
        override fun disconnect() { disconnected=true }
        override fun usingProxy()=false
        override fun getResponseCode()=status
    }
    private class StalledConnection: FakeConnection(200) {
        @Volatile var started=false
        private val gate=CountDownLatch(1)
        override fun getResponseCode(): Int { started=true; gate.await(); return 200 }
        override fun disconnect() { super.disconnect(); gate.countDown() }
    }
}
