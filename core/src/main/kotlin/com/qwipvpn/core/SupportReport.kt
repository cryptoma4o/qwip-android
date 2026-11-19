package com.qwipvpn.core

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable enum class SupportProcess { APP, TUNNEL }
@Serializable enum class SupportNetworkKind { NONE, CELLULAR, WIFI, ETHERNET, VPN, OTHER }
@Serializable data class SupportNetwork(val kind: SupportNetworkKind = SupportNetworkKind.NONE,
    val validated: Boolean = false,val captivePortal: Boolean = false,val ipv4Route: Boolean = false,val ipv6Route: Boolean = false)
@Serializable enum class SupportEventKind {
    PROCESS_STARTED, NETWORK_CHANGED, APP_READY, APP_PREPARATION_FAILED,
    CONNECT_REQUESTED, CONNECT_PREPARATION_FAILED, DISCONNECT_REQUESTED, SERVICE_UNAVAILABLE,
    SERVICE_CREATED, SERVICE_DESTROYED, VPN_REVOKED, VPN_CONSENT_DENIED, TUNNEL_STATE, CONNECT_STAGE, CONNECT_FAILED,
    CONNECT_TIMEOUT, STOP_TIMEOUT, CORE_STOP_FAILED, NETWORK_TEST_STARTED, NETWORK_TEST_CANCELLED,
    NETWORK_TEST_FAILED, NETWORK_TEST_FINISHED
}
@Serializable enum class SupportStage { FOREGROUND, CONSENT, REQUEST, ASSETS, DNS, TUN, CONFIGURATION, CORE_START, CORE_RUNNING }
@Serializable enum class SupportIssue { NONE, TIMEOUT, DNS, TLS, IO, PERMISSION, CANCELLED, OTHER }

/** No free-form messages, profile identifiers, URLs, configurations or exception text. */
@Serializable data class SupportEvent(val at: Long,val process: SupportProcess,val kind: SupportEventKind,
    val phase: TunnelPhase? = null,val stage: SupportStage? = null,val issue: SupportIssue = SupportIssue.NONE,
    val network: SupportNetwork? = null,val profileNumber: Int? = null,val elapsedMillis: Long? = null)
@Serializable data class SupportProbe(val catalogIndex: Int,val outcome: DiagnosticOutcome,val milliseconds: Long,val httpStatus: Int? = null)
@Serializable data class SupportDiagnosis(val at: Long,val network: SupportNetwork,val verdict: DiagnosticVerdict,
    val catalogVersion: String,val rounds: List<List<SupportProbe>>) {
    companion object {
        fun from(report: DiagnosticReport,network: SupportNetwork) = SupportDiagnosis(report.measured,network,report.verdict,
            DiagnosticCatalog.VERSION,report.rounds.takeLast(2).map { round ->
                round.measurements.take(DiagnosticCatalog.targets.size).mapNotNull { measurement ->
                    val index=DiagnosticCatalog.targets.indexOf(measurement.target)
                    if(index<0)null else SupportProbe(index,measurement.outcome,measurement.milliseconds.coerceIn(0,10_000),
                        measurement.httpStatus?.takeIf { it in 100..599 })
                }
            })
    }
}
@Serializable data class SupportJournal(val events: List<SupportEvent> = emptyList(),val diagnosis: SupportDiagnosis? = null) {
    fun retained(now: Long): SupportJournal = copy(
        events=events.filter { it.at in (now-RETENTION_MILLIS)..(now+300_000) }.takeLast(MAX_EVENTS),
        diagnosis=diagnosis?.takeIf { it.at in (now-RETENTION_MILLIS)..(now+300_000) && it.catalogVersion==DiagnosticCatalog.VERSION }
    )
    companion object { const val MAX_EVENTS=200; const val RETENTION_MILLIS=48L*60*60*1000 }
}

data class SupportReportContext(val appVersion: String,val build: Int,val androidApi: Int,val engineVersion: String,
    val phase: TunnelPhase,val profileCount: Int,val sourceCount: Int,val selectedNumber: Int?,val geoReady: Boolean,val network: SupportNetwork)

enum class SupportReportLabel(val defaultText: String) {
    TITLE("QWIP — ДИАГНОСТИЧЕСКИЕ ЛОГИ"),
    CREATED("Создано (UTC)"),
    APP("Приложение"),
    ENGINE("Ядро"),
    PROFILES("Профилей"),
    SOURCES("подписок"),
    SELECTED("выбран профиль №"),
    NETWORK("Сеть сейчас"),
    IPV6_NOTICE("Наличие маршрута IPv6 не подтверждает доступ в Интернет по IPv6."),
    CONTENTS("Состав: состояния, этапы и категории ошибок, контрольные HTTPS-проверки."),
    PRIVACY("Без ключей, подписок, адресов серверов, названий профилей и истории посещений."),
    SHARING("Отправка выполняется пользователем через меню Android."),
    UNAVAILABLE("Не удалось прочитать журналов"),
    LATEST_CHECK("ПОСЛЕДНЯЯ ПРОВЕРКА СЕТИ"),
    NO_CHECK("Нет завершённой проверки за последние 48 часов. Запустите «Диагностика сети» с отключённым VPN."),
    CATALOG("Каталог"),
    RESULT("результат"),
    ESTIMATE("оценка, не подтверждение оператора"),
    ROUND("Серия"),
    EVENTS("ЖУРНАЛ СОБЫТИЙ (UTC, последние 48 часов)"),
    NO_EVENTS("Событий пока нет."),
    READY("ready"),
    NOT_READY("not ready"),
    NONE("none"),
    UNKNOWN("unknown")
}

object SupportReport {
    private fun version(text: String): String = text.takeIf { it=="Xray" || it.matches(Regex("(?:Xray )?v?\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}(?:[.-][A-Za-z0-9]{1,16})?")) } ?: "unknown"
    private fun network(value: SupportNetwork) = "${value.kind}; validated=${value.validated}; captive=${value.captivePortal}; IPv4_route=${value.ipv4Route}; IPv6_route=${value.ipv6Route}"
    fun render(context: SupportReportContext,journals: List<SupportJournal>,now: Long = System.currentTimeMillis(),unavailableJournals: Int = 0,labels: Map<SupportReportLabel,String> = emptyMap()): String = buildString {
        fun label(key: SupportReportLabel)=labels[key] ?: key.defaultText
        appendLine(label(SupportReportLabel.TITLE))
        appendLine("${label(SupportReportLabel.CREATED)}: ${Instant.ofEpochMilli(now)}")
        appendLine("${label(SupportReportLabel.APP)}: ${version(context.appVersion)} (${context.build}); Android API ${context.androidApi}")
        appendLine("${label(SupportReportLabel.ENGINE)}: ${version(context.engineVersion)}; VPN: ${context.phase}")
        appendLine("${label(SupportReportLabel.PROFILES)}: ${context.profileCount.coerceIn(0,100_000)}; ${label(SupportReportLabel.SOURCES)}: ${context.sourceCount.coerceIn(0,100_000)}; ${label(SupportReportLabel.SELECTED)} ${context.selectedNumber?.takeIf { it in 1..100_000 } ?: label(SupportReportLabel.NONE)}")
        appendLine("GeoData: ${label(if(context.geoReady)SupportReportLabel.READY else SupportReportLabel.NOT_READY)}")
        appendLine("${label(SupportReportLabel.NETWORK)}: ${network(context.network)}")
        appendLine(label(SupportReportLabel.IPV6_NOTICE))
        appendLine(label(SupportReportLabel.CONTENTS))
        appendLine(label(SupportReportLabel.PRIVACY))
        appendLine(label(SupportReportLabel.SHARING))
        val retained=journals.take(2).map { it.retained(now) }
        if(unavailableJournals>0)appendLine("${label(SupportReportLabel.UNAVAILABLE)}: ${unavailableJournals.coerceIn(1,2)}")
        appendLine(); appendLine(label(SupportReportLabel.LATEST_CHECK))
        val diagnosis=retained.mapNotNull { it.diagnosis }.maxByOrNull { it.at }
        if(diagnosis==null)appendLine(label(SupportReportLabel.NO_CHECK))
        else {
            appendLine("UTC: ${Instant.ofEpochMilli(diagnosis.at)}; ${network(diagnosis.network)}")
            appendLine("${label(SupportReportLabel.CATALOG)}: ${DiagnosticCatalog.VERSION}; ${label(SupportReportLabel.RESULT)}: ${diagnosis.verdict} (${label(SupportReportLabel.ESTIMATE)})")
            diagnosis.rounds.takeLast(2).forEachIndexed { round,probes ->
                appendLine("${label(SupportReportLabel.ROUND)} ${round+1}:")
                probes.take(DiagnosticCatalog.targets.size).forEach { probe ->
                    DiagnosticCatalog.targets.getOrNull(probe.catalogIndex)?.let { target ->
                        appendLine("  ${target.url.host}: ${probe.outcome}; ${probe.milliseconds.coerceIn(0,10_000)} ms${probe.httpStatus?.takeIf { it in 100..599 }?.let { "; HTTP $it" } ?: ""}")
                    }
                }
            }
        }
        appendLine(); appendLine(label(SupportReportLabel.EVENTS))
        val events=retained.flatMap { it.events }.sortedBy { it.at }.takeLast(2*SupportJournal.MAX_EVENTS)
        if(events.isEmpty())appendLine(label(SupportReportLabel.NO_EVENTS))
        events.forEach { event ->
            append("${Instant.ofEpochMilli(event.at)} ${event.process} ${event.kind}")
            event.phase?.let { append(" phase=$it") }; event.stage?.let { append(" stage=$it") }
            if(event.issue!=SupportIssue.NONE)append(" issue=${event.issue}")
            event.profileNumber?.takeIf { it in 1..100_000 }?.let { append(" profile_no=$it") }
            event.elapsedMillis?.let { append(" elapsed_ms=${it.coerceIn(0,86_400_000)}") }
            event.network?.let { append(" network=[${network(it)}]") }
            appendLine()
        }
    }
}
