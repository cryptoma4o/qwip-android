@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.qwipvpn.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qwipvpn.core.*
import com.qwipvpn.app.BuildConfig
import com.qwipvpn.app.QWIPApplication
import com.qwipvpn.app.support.PreparedSupportReport
import kotlinx.coroutines.CancellationException

@Composable
fun SecretSupportLogs(store: ClientStore,state: TunnelState,engineVersion: String) {
    val strings = LocalResources.current
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var open by remember { mutableStateOf(false) }
    Text(strings.getString(R.string.version,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE),modifier=Modifier
        .testTag("about-version").clickable(role=Role.Button) {
            val now=SystemClock.elapsedRealtime()
            taps=if(now-lastTap>2000)1 else taps+1
            lastTap=now
            if(taps>=7) { taps=0; open=true }
        }.fillMaxWidth().heightIn(min=48.dp).padding(vertical=12.dp))
    if(open)SupportLogsSheet(store,state,engineVersion) { open=false; taps=0 }
}

@Composable
private fun SupportLogsSheet(store: ClientStore,state: TunnelState,engineVersion: String,onDismiss: () -> Unit) {
    val strings = LocalResources.current
    val context=LocalContext.current
    val diagnostics=remember { (context.applicationContext as QWIPApplication).diagnostics }
    var prepared by remember { mutableStateOf<PreparedSupportReport?>(null) }
    var failed by remember { mutableStateOf(false) }
    var shareFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        prepared=null; failed=false
        try { prepared=diagnostics.prepare(store,state,engineVersion) }
        catch(error: CancellationException) { throw error }
        catch(_: Exception) { failed=true }
    }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=24.dp).padding(bottom=24.dp)
            .testTag("support-logs-sheet"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(strings.getString(R.string.support_logs),style=MaterialTheme.typography.headlineSmall)
            Text(strings.getString(R.string.support_logs_hint))
            Text(strings.getString(R.string.share_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(failed) {
                Text(strings.getString(R.string.prepare_report_failed),color=MaterialTheme.colorScheme.error)
                TextButton(onClick={attempt++}) { Text(strings.getString(R.string.retry)) }
            } else if(prepared==null)LinearProgressIndicator(Modifier.fillMaxWidth())
            prepared?.let { report ->
                TextButton(onClick={preview=!preview},modifier=Modifier.testTag("support-logs-preview")) { Text(if(preview)strings.getString(R.string.hide_report) else strings.getString(R.string.view_report)) }
                if(preview)SelectionContainer {
                    Text(report.text,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,
                        modifier=Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState()).testTag("support-logs-text"))
                }
            }
            if(shareFailed)Text(strings.getString(R.string.share_failed),color=MaterialTheme.colorScheme.error)
            Button(onClick={
                prepared?.let { report ->
                    shareFailed=false
                    try { context.startActivity(report.chooser) } catch(_: Exception) { shareFailed=true }
                }
            },enabled=prepared!=null,modifier=Modifier.fillMaxWidth().testTag("support-logs-send")) { Text(strings.getString(R.string.send_logs)) }
        }
    }
}
