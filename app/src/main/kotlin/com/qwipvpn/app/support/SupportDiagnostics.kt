package com.qwipvpn.app.support

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import androidx.core.content.FileProvider
import com.qwipvpn.core.*
import com.qwipvpn.app.BuildConfig
import com.qwipvpn.app.R
import com.qwipvpn.app.data.SecureVault
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.UUID
import javax.net.ssl.SSLException

/** Each process writes a separate encrypted journal. Only the explicit share action exposes a report. */
class SupportDiagnostics(context: Context) {
    private val context=context.applicationContext
    private val process=if(Application.getProcessName().endsWith(":tunnel"))SupportProcess.TUNNEL else SupportProcess.APP
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private sealed interface Command {
        data class Event(val value: SupportEvent): Command
        data class Diagnosis(val value: SupportDiagnosis): Command
        data class Flush(val done: CompletableDeferred<Unit>): Command
    }
    private val queue=Channel<Command>(64)
    private val json=Json { ignoreUnknownKeys=true }
    private fun vault(owner: SupportProcess)=SecureVault(context,"com.qwipvpn.app.support.${owner.name.lowercase()}.v1")
    private fun name(owner: SupportProcess)="support-${owner.name.lowercase()}"
    private fun read(owner: SupportProcess): SupportJournal {
        val raw=vault(owner).read(name(owner)) ?: return SupportJournal()
        require(raw.length<=128*1024)
        return json.decodeFromString<SupportJournal>(raw).retained(System.currentTimeMillis())
    }
    init {
        scope.launch {
            var journal=runCatching { read(process) }.getOrDefault(SupportJournal())
            for(command in queue) {
                if(command is Command.Flush) { command.done.complete(Unit); continue }
                journal=when(command) {
                    is Command.Event -> journal.copy(events=(journal.events+command.value).takeLast(SupportJournal.MAX_EVENTS))
                    is Command.Diagnosis -> journal.copy(diagnosis=command.value)
                }.retained(System.currentTimeMillis())
                // Diagnostics must never interrupt a VPN operation if storage/Keystore is unavailable.
                runCatching { vault(process).write(name(process),json.encodeToString(journal)) }
            }
        }
    }
    fun record(kind: SupportEventKind,phase: TunnelPhase? = null,stage: SupportStage? = null,
        issue: SupportIssue = SupportIssue.NONE,network: SupportNetwork? = null,profileNumber: Int? = null,elapsedMillis: Long? = null) {
        queue.trySend(Command.Event(SupportEvent(System.currentTimeMillis(),process,kind,phase,stage,issue,network,profileNumber,elapsedMillis)))
    }
    fun diagnosis(report: DiagnosticReport,network: SupportNetwork) {
        queue.trySend(Command.Diagnosis(SupportDiagnosis.from(report,network)))
        record(SupportEventKind.NETWORK_TEST_FINISHED,network=network)
    }
    fun network(): SupportNetwork = runCatching {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val active=manager.activeNetwork ?: return SupportNetwork()
        val caps=manager.getNetworkCapabilities(active) ?: return SupportNetwork()
        val kind=when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> SupportNetworkKind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> SupportNetworkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> SupportNetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> SupportNetworkKind.ETHERNET
            else -> SupportNetworkKind.OTHER
        }
        val routes=manager.getLinkProperties(active)?.routes.orEmpty().filter { it.isDefaultRoute }
        SupportNetwork(kind,caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            routes.any { it.destination.address is Inet4Address },routes.any { it.destination.address is Inet6Address })
    }.getOrDefault(SupportNetwork())
    fun observeNetwork() {
        if(process!=SupportProcess.APP)return
        val manager=context.getSystemService(ConnectivityManager::class.java)
        var previous: SupportNetwork?=null
        val callback=object: ConnectivityManager.NetworkCallback() {
            private fun update() { val value=network(); if(value!=previous) { previous=value; record(SupportEventKind.NETWORK_CHANGED,network=value) } }
            override fun onAvailable(network: Network) { update() }
            override fun onLost(network: Network) { update() }
            override fun onCapabilitiesChanged(network: Network,caps: NetworkCapabilities) { update() }
            override fun onLinkPropertiesChanged(network: Network,links: LinkProperties) { update() }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }
    suspend fun prepare(store: ClientStore,state: TunnelState,engineVersion: String): PreparedSupportReport = withContext(Dispatchers.IO) {
        val done=CompletableDeferred<Unit>()
        withTimeout(5000) { queue.send(Command.Flush(done)); done.await() }
        var unavailable=0
        val journals=SupportProcess.entries.mapNotNull { owner -> runCatching { read(owner) }.getOrElse { unavailable++; null } }
        val report=SupportReport.render(SupportReportContext(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,Build.VERSION.SDK_INT,
            engineVersion,state.phase,store.profiles.size,store.sources.size,store.profiles.indexOfFirst { it.id==store.selected }.takeIf { it>=0 }?.plus(1),
            store.geoGeneration!=null,network()),journals,unavailableJournals=unavailable,labels=SupportReportLabel.entries.associateWith { key -> context.getString(when(key) {
                SupportReportLabel.TITLE -> R.string.report_title
                SupportReportLabel.CREATED -> R.string.report_created
                SupportReportLabel.APP -> R.string.report_app
                SupportReportLabel.ENGINE -> R.string.report_engine
                SupportReportLabel.PROFILES -> R.string.report_profiles
                SupportReportLabel.SOURCES -> R.string.report_sources
                SupportReportLabel.SELECTED -> R.string.report_selected
                SupportReportLabel.NETWORK -> R.string.report_network
                SupportReportLabel.IPV6_NOTICE -> R.string.report_ipv6_notice
                SupportReportLabel.CONTENTS -> R.string.report_contents
                SupportReportLabel.PRIVACY -> R.string.report_privacy
                SupportReportLabel.SHARING -> R.string.report_sharing
                SupportReportLabel.UNAVAILABLE -> R.string.report_unavailable
                SupportReportLabel.LATEST_CHECK -> R.string.report_latest_check
                SupportReportLabel.NO_CHECK -> R.string.report_no_check
                SupportReportLabel.CATALOG -> R.string.report_catalog
                SupportReportLabel.RESULT -> R.string.report_result
                SupportReportLabel.ESTIMATE -> R.string.report_estimate
                SupportReportLabel.ROUND -> R.string.report_round
                SupportReportLabel.EVENTS -> R.string.report_events
                SupportReportLabel.NO_EVENTS -> R.string.report_no_events
                SupportReportLabel.READY -> R.string.report_ready
                SupportReportLabel.NOT_READY -> R.string.report_not_ready
                SupportReportLabel.NONE -> R.string.report_none
                SupportReportLabel.UNKNOWN -> R.string.report_unknown
            }) })
        require(report.toByteArray().size<=128*1024)
        ensureActive()
        val directory=File(context.cacheDir,"support-exports").apply { check(mkdirs()||isDirectory) }
        val previous=directory.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
        previous.forEachIndexed { index,file ->
            if(index>=2 || System.currentTimeMillis()-file.lastModified()>24*60*60*1000L) {
                runCatching { context.revokeUriPermission(FileProvider.getUriForFile(context,"${context.packageName}.support-files",file),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                file.delete()
            }
        }
        val file=File(directory,"QWIP-logs-${UUID.randomUUID()}.txt")
        file.writeText(report)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.support-files",file)
        val send=Intent(Intent.ACTION_SEND).apply {
            type="text/plain"
            putExtra(Intent.EXTRA_SUBJECT,context.getString(R.string.log_subject))
            putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"QWIP logs",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        PreparedSupportReport(report,Intent.createChooser(send,context.getString(R.string.send_logs)))
    }
    companion object {
        fun issue(error: Exception)=when(error) {
            is CancellationException -> SupportIssue.CANCELLED
            is java.net.SocketTimeoutException -> SupportIssue.TIMEOUT
            is java.net.UnknownHostException -> SupportIssue.DNS
            is SSLException -> SupportIssue.TLS
            is SecurityException -> SupportIssue.PERMISSION
            is java.io.IOException -> SupportIssue.IO
            else -> SupportIssue.OTHER
        }
    }
}
data class PreparedSupportReport(val text: String,val chooser: Intent)

class SupportFileProvider : FileProvider()
