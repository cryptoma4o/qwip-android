package com.qwipvpn.core

import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.URI
import kotlin.test.*

class SupportReportTests {
    private val now=1_800_000_000_000L
    private fun context(engine: String="26.10.21")=SupportReportContext("0.1.3",4,35,engine,TunnelPhase.ERROR,4,1,2,true,
        SupportNetwork(SupportNetworkKind.CELLULAR))
    @Test fun keepsOnlyABoundedRecentTimeline() {
        val events=(0..600).map { SupportEvent(now-it,SupportProcess.TUNNEL,SupportEventKind.CONNECT_STAGE) }
        val journal=SupportJournal(events+SupportEvent(now-SupportJournal.RETENTION_MILLIS-1,SupportProcess.APP,SupportEventKind.PROCESS_STARTED)+
            SupportEvent(now+300_001,SupportProcess.APP,SupportEventKind.APP_READY)).retained(now)
        assertEquals(200,journal.events.size)
        assertTrue(journal.events.all { it.at<=now && it.at>=now-600 })
    }
    @Test fun rejectsFreeFormVersionFieldsInTheExport() {
        val secret="https://synthetic.invalid/private-subscription-token?key=synthetic-key"
        val report=SupportReport.render(context(secret).copy(appVersion=secret),emptyList(),now)
        assertFalse(report.contains(secret)); assertFalse(report.contains("synthetic"))
        assertTrue(report.contains("unknown")); assertTrue(report.contains("VPN: ERROR"))
    }
    @Test fun doesNotPersistAnArbitraryProbeTargetEvenWithAKnownIdentifier() {
        val target=DiagnosticCatalog.targets.first().copy(url=URI("https://synthetic.invalid/private-key"))
        val measurement=DiagnosticMeasurement(target,DiagnosticOutcome.REACHABLE)
        val diagnosis=SupportDiagnosis.from(DiagnosticReport(DiagnosticVerdict.INCONCLUSIVE,listOf(DiagnosticRound(listOf(measurement))),now),SupportNetwork())
        val encoded=Json.encodeToString(diagnosis)
        assertFalse(encoded.contains("synthetic")); assertFalse(encoded.contains("private-key")); assertTrue(diagnosis.rounds.single().isEmpty())
    }
    @Test fun includesBothRoundsAndSafeHttpsStatusEvidence() {
        val measurements=DiagnosticCatalog.targets.map { DiagnosticMeasurement(it,if(it.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TIMEOUT,4000,
            if(it.group==DiagnosticGroup.ALLOWED)403 else null) }
        val diagnosis=SupportDiagnosis.from(DiagnosticReport(DiagnosticVerdict.POSSIBLE_ALLOWLIST,List(2) { DiagnosticRound(measurements) },now),SupportNetwork(SupportNetworkKind.CELLULAR))
        val restored=Json.decodeFromString<SupportJournal>(Json.encodeToString(SupportJournal(diagnosis=diagnosis)))
        val report=SupportReport.render(context(),listOf(restored),now)
        assertTrue(report.contains("Серия 2")); assertTrue(report.contains("POSSIBLE_ALLOWLIST"))
        assertTrue(report.contains("ya.ru: REACHABLE; 4000 ms; HTTP 403"))
        assertTrue(report.contains("www.wikipedia.org: TIMEOUT; 4000 ms"))
        assertTrue(report.contains("26.10.21"))
    }
    @Test fun oldAndMismatchedCatalogResultsAreNotMisrepresentedAsCurrent() {
        val current=SupportDiagnosis(now,SupportNetwork(),DiagnosticVerdict.NO_INDICATION,DiagnosticCatalog.VERSION,emptyList())
        val old=current.copy(at=now-SupportJournal.RETENTION_MILLIS-1)
        val mismatch=current.copy(catalogVersion="old-catalog")
        assertNull(SupportJournal(diagnosis=old).retained(now).diagnosis)
        assertNull(SupportJournal(diagnosis=mismatch).retained(now).diagnosis)
    }
    @Test fun exportStaysBoundedAndSortedAcrossTwoProcesses() {
        val journals=SupportProcess.entries.map { process -> SupportJournal(List(300) { index ->
            SupportEvent(now-index,process,SupportEventKind.CONNECT_FAILED,phase=TunnelPhase.ERROR,stage=SupportStage.CORE_START,
                issue=SupportIssue.OTHER,network=SupportNetwork(SupportNetworkKind.CELLULAR),profileNumber=Int.MAX_VALUE,elapsedMillis=Long.MAX_VALUE)
        }) }
        val report=SupportReport.render(context(),journals,now)
        assertEquals(400,report.lineSequence().count { it.contains(" CONNECT_FAILED") })
        assertTrue(report.toByteArray().size<=128*1024)
        assertFalse(report.contains("profile_no=")); assertFalse(report.contains(Long.MAX_VALUE.toString()))
        assertTrue(report.indexOf("APP CONNECT_FAILED")>0); assertTrue(report.indexOf("TUNNEL CONNECT_FAILED")>0)
    }
    @Test fun aCorruptJournalCanBeReportedWithoutInsertingItsRawContent() {
        val report=SupportReport.render(context(),emptyList(),now,unavailableJournals=1)
        assertTrue(report.contains("Не удалось прочитать журналов: 1"))
        assertTrue(report.contains("Событий пока нет"))
    }
}
