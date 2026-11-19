package com.qwipvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.qwipvpn.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qwipvpn.core.*
import com.qwipvpn.app.BuildConfig
import com.qwipvpn.app.QWIPApplication
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.text.DateFormat
import java.util.Date

private data class DiagnosticNetwork(val network: Network,val title: String,val vpn: Boolean,val key: String)
private fun captureNetwork(manager: ConnectivityManager,strings: android.content.res.Resources): DiagnosticNetwork? {
    val network=manager.activeNetwork ?: return null
    val caps=manager.getNetworkCapabilities(network) ?: return null
    if(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return null
    val title=when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> strings.getString(R.string.mobile_network)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> strings.getString(R.string.wired_network)
        else -> strings.getString(R.string.current_network)
    }
    val vpn=caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val links=manager.getLinkProperties(network)
    // This identity remains in memory only; IP addresses and DNS settings are never exported.
    val key=listOf(network.networkHandle,title,vpn,links?.interfaceName,links?.dnsServers,links?.linkAddresses).joinToString("|")
    return DiagnosticNetwork(network,title,vpn,key)
}

@Composable
fun NetworkDiagnosticsScreen(tunnel: TunnelState,fixture: DiagnosticProber? = null) {
    val strings = LocalResources.current
    val context=LocalContext.current
    val diagnostics=remember { (context.applicationContext as QWIPApplication).diagnostics }
    val manager=remember { context.getSystemService(ConnectivityManager::class.java) }
    var network by remember { mutableStateOf(captureNetwork(manager,strings)) }
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var progress by remember { mutableStateOf<DiagnosticProgress?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var measuredNetwork by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope=rememberCoroutineScope()
    val owner=LocalLifecycleOwner.current
    val vpnActive=network?.vpn==true || tunnel.phase in listOf(TunnelPhase.CONNECTED,TunnelPhase.CONNECTING,TunnelPhase.DISCONNECTING)
    val available=network!=null || fixture!=null
    var previousKey by remember { mutableStateOf(network?.key to vpnActive) }

    DisposableEffect(manager) {
        val callback=object: ConnectivityManager.NetworkCallback() {
            private fun update() { network=captureNetwork(manager,strings) }
            override fun onAvailable(value: Network) { update() }
            override fun onLost(value: Network) { update() }
            override fun onCapabilitiesChanged(value: Network,caps: NetworkCapabilities) { update() }
            override fun onLinkPropertiesChanged(value: Network,links: LinkProperties) { update() }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    LaunchedEffect(network?.key,vpnActive) {
        val current=network?.key to vpnActive
        if(current!=previousKey) {
            job?.cancel(); job=null; running=false; report=null; progress=null
            message=strings.getString(R.string.network_changed)
        }
        previousKey=current
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP)job?.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); job?.cancel() }
    }

    fun start() {
        if(running || !available || vpnActive)return
        val captured=network
        val key=captured?.key
        val prober=fixture ?: DiagnosticHttpProbe { uri ->
            require(captured!=null)
            captured.network.openConnection(uri.toURL()) as HttpURLConnection
        }
        report=null; progress=null; message=null; running=true; measuredNetwork=captured?.title ?: strings.getString(R.string.test_network)
        val supportNetwork=diagnostics.network()
        diagnostics.record(SupportEventKind.NETWORK_TEST_STARTED,network=supportNetwork)
        job=scope.launch {
            try {
                val result=NetworkDiagnostics(prober,confirmationDelayMillis=if(fixture!=null)0 else 1000).run(isCurrent={
                    !vpnActive && (fixture!=null || captureNetwork(manager,strings)?.key==key)
                }) { progress=it }
                ensureActive()
                if(fixture!=null || captureNetwork(manager,strings)?.key==key) { report=result; diagnostics.diagnosis(result,supportNetwork) }
            } catch(_: CancellationException) { diagnostics.record(SupportEventKind.NETWORK_TEST_CANCELLED); message=strings.getString(R.string.network_test_cancelled) }
            catch(_: Exception) { diagnostics.record(SupportEventKind.NETWORK_TEST_FAILED); message=strings.getString(R.string.network_test_failed) }
            finally { running=false; job=null }
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("network-diagnostics"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {
            Text(strings.getString(R.string.network_test_title),style=MaterialTheme.typography.titleLarge)
            Text(strings.getString(R.string.network_test_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            when {
                vpnActive -> {
                    Text(strings.getString(R.string.disable_vpn_first),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("network-diagnostics-vpn-warning"))
                    Text(strings.getString(R.string.vpn_test_warning))
                }
                !available -> Text(strings.getString(R.string.no_network_connection))
                else -> {
                    Text(strings.getString(R.string.tested_network,network?.title ?: strings.getString(R.string.test_network)))
                    Text(strings.getString(R.string.test_mobile_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Button(onClick=::start,enabled=available&&!vpnActive&&!running,modifier=Modifier.fillMaxWidth().testTag("network-diagnostics-start")) { Text(strings.getString(R.string.check_network)) }
            if(running) {
                progress?.let { value ->
                    LinearProgressIndicator(progress={value.measurements.size.toFloat()/value.total.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                    Text(strings.getString(R.string.test_progress,strings.getString(if(value.round==1)R.string.checking else R.string.checking_again),value.measurements.size,value.total))
                }
                TextButton(onClick={job?.cancel()},modifier=Modifier.testTag("network-diagnostics-cancel")) { Text(strings.getString(R.string.cancel_test)) }
            }
            message?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        report?.let { result -> item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(result.verdict.title(strings),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("network-diagnostics-result"))
                    Text(result.verdict.explanation(strings))
                    Text("$measuredNetwork · ${DateFormat.getTimeInstance().format(Date(result.measured))}",style=MaterialTheme.typography.bodySmall)
                    if(result.rounds.size>1)Text(strings.getString(R.string.confirmed_twice),style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={
                        val text=buildString {
                            appendLine("QWIP ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android")
                            appendLine(strings.getString(R.string.network_measured,measuredNetwork,DateFormat.getDateTimeInstance().format(Date(result.measured))))
                            appendLine(result.verdict.title(strings)); appendLine(strings.getString(R.string.round_count,result.rounds.size))
                            result.measurements.forEach { value -> appendLine("${value.target.title(strings)}: ${strings.getString(R.string.elapsed_ms,value.outcome.title(strings)+(value.httpStatus?.let { " · HTTP $it" } ?: ""),value.milliseconds)}") }
                            appendLine(strings.getString(R.string.catalog_estimate,DiagnosticCatalog.VERSION))
                        }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("QWIP network diagnosis",text))
                    },modifier=Modifier.testTag("network-diagnostics-copy")) { Text(strings.getString(R.string.copy_result)) }
                }
            }
        } }
        val values=report?.measurements ?: progress?.measurements.orEmpty()
        if(values.isNotEmpty()) {
            DiagnosticGroup.entries.forEach { group ->
                item { Text(group.title(strings),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                DiagnosticCatalog.targets.filter { it.group==group }.forEach { target -> item(key=target.id) {
                    val result=values.firstOrNull { it.target.id==target.id }
                    ListItem(headlineContent={Text(target.title(strings))},supportingContent={Column {
                        Text(result?.let { strings.getString(R.string.elapsed_ms,it.outcome.title(strings)+(it.httpStatus?.let { code -> " · HTTP $code" } ?: ""),it.milliseconds) } ?: strings.getString(R.string.waiting))
                        Text(target.url.host)
                    }})
                } }
            }
        }
        item {
            Text(strings.getString(R.string.network_test_privacy),style=MaterialTheme.typography.bodySmall)
            Text(strings.getString(R.string.network_test_limitations),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(strings.getString(R.string.catalog_version,DiagnosticCatalog.VERSION),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun DiagnosticGroup.title(strings: android.content.res.Resources)=when(this) {
    DiagnosticGroup.ALLOWED -> strings.getString(R.string.allowlist_services)
    DiagnosticGroup.DOMESTIC_CONTROL -> strings.getString(R.string.domestic_services)
    DiagnosticGroup.EXTERNAL_CONTROL -> strings.getString(R.string.external_services)
}
private fun DiagnosticOutcome.title(strings: android.content.res.Resources)=when(this) {
    DiagnosticOutcome.REACHABLE -> strings.getString(R.string.https_reachable)
    DiagnosticOutcome.DNS_FAILURE -> strings.getString(R.string.dns_failure)
    DiagnosticOutcome.CONNECTION_FAILURE -> strings.getString(R.string.connection_failure)
    DiagnosticOutcome.TIMEOUT -> strings.getString(R.string.timeout)
    DiagnosticOutcome.TLS_FAILURE -> strings.getString(R.string.tls_failure)
    DiagnosticOutcome.NOT_MEASURED -> strings.getString(R.string.insufficient_data)
    DiagnosticOutcome.CANCELLED -> strings.getString(R.string.cancelled)
}
private fun DiagnosticVerdict.title(strings: android.content.res.Resources)=when(this) {
    DiagnosticVerdict.NO_INDICATION -> strings.getString(R.string.no_allowlist)
    DiagnosticVerdict.POSSIBLE_ALLOWLIST -> strings.getString(R.string.possible_allowlist)
    DiagnosticVerdict.PARTIAL_CONNECTIVITY -> strings.getString(R.string.partial_connectivity)
    DiagnosticVerdict.INTERNET_UNAVAILABLE -> strings.getString(R.string.internet_unavailable)
    DiagnosticVerdict.DNS_PROBLEM -> strings.getString(R.string.dns_problem)
    DiagnosticVerdict.INCONCLUSIVE -> strings.getString(R.string.insufficient_data)
}
private fun DiagnosticVerdict.explanation(strings: android.content.res.Resources)=when(this) {
    DiagnosticVerdict.NO_INDICATION -> strings.getString(R.string.no_allowlist_explanation)
    DiagnosticVerdict.POSSIBLE_ALLOWLIST -> strings.getString(R.string.possible_allowlist_explanation)
    DiagnosticVerdict.PARTIAL_CONNECTIVITY -> strings.getString(R.string.partial_connectivity_explanation)
    DiagnosticVerdict.INTERNET_UNAVAILABLE -> strings.getString(R.string.internet_unavailable_explanation)
    DiagnosticVerdict.DNS_PROBLEM -> strings.getString(R.string.dns_problem_explanation)
    DiagnosticVerdict.INCONCLUSIVE -> strings.getString(R.string.inconclusive_explanation)
}

private fun DiagnosticTarget.title(strings: android.content.res.Resources)=when(id) {
    "yandex" -> strings.getString(R.string.yandex_name)
    "gosuslugi" -> strings.getString(R.string.gosuslugi_name)
    "beeline" -> strings.getString(R.string.beeline_name)
    else -> title
}
