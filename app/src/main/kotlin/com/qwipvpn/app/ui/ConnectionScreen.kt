@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qwipvpn.app.R
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qwipvpn.core.*
import kotlinx.coroutines.delay

@Composable fun LocationSheet(store: ClientStore,busy: Boolean,onDismiss: () -> Unit,onSelect: (String) -> Unit,
    onFavorite: (String) -> Unit,onRefresh: () -> Unit,onAdd: () -> Unit,
    probes: Map<String,ProbeResult> = emptyMap(),probing:Boolean=false,onProbe:()->Unit={}) {
    val strings = LocalResources.current
    var search by rememberSaveable { mutableStateOf("") }
    val profiles = remember(store,search,probes) { ProfileGroups.groups(store,probes).flatMap{it.profiles}.filter { it.title.contains(search,true) } }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(horizontal=20.dp)) {
            Text(strings.getString(R.string.choose_location),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(search,{search=it},singleLine=true,label={Text(strings.getString(R.string.search))},leadingIcon={Icon(QWIPIcons.Search,null)},modifier=Modifier.fillMaxWidth().testTag("location-search"))
            if (store.sources.isNotEmpty()) TextButton(onClick=onRefresh,enabled=!busy) { Icon(QWIPIcons.Refresh,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(strings.getString(R.string.refresh_locations)) }
            TextButton(onClick=onProbe,enabled=!probing){Text(strings.getString(if(probing)R.string.checking else R.string.check_servers))}
            PullToRefreshBox(isRefreshing=busy,onRefresh={if (store.sources.isNotEmpty()) onRefresh()},modifier=Modifier.weight(1f,false)) {
                LazyColumn(contentPadding=PaddingValues(bottom=30.dp)) {
                    if (profiles.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(if (store.profiles.isEmpty()) strings.getString(R.string.no_locations) else strings.getString(R.string.nothing_found))
                            if (store.profiles.isEmpty()) TextButton(onClick=onAdd) { Text(strings.getString(R.string.add_config)) }
                        }
                    }
                    items(profiles,key={it.id}) { profile ->
                        ListItem(headlineContent={Text(profile.title)},leadingContent={Icon(QWIPIcons.Public,null,tint=MaterialTheme.colorScheme.primary)},
                            supportingContent={ServerSummary.read(profile)?.let{Text(it.label)}},trailingContent={Row(verticalAlignment=Alignment.CenterVertically) {
                                LatencyBadge(probes[profile.id])
                                if (profile.id == store.selected) Icon(QWIPIcons.Check,strings.getString(R.string.location_selected),tint=MaterialTheme.colorScheme.primary)
                                IconButton(onClick={onFavorite(profile.id)},enabled=!busy) { Icon(if (profile.favorite) QWIPIcons.Star else QWIPIcons.StarOutline,if (profile.favorite) strings.getString(R.string.remove_favorite) else strings.getString(R.string.add_favorite)) }
                            } },modifier=Modifier.clickable(enabled=!busy) { onSelect(profile.id) }.testTag("location-${profile.id}"))
                    }
                }
            }
        }
    }
}

@Composable fun ImportSheet(text: String,onText: (String) -> Unit,busy: Boolean,error: String?,onCancel: () -> Unit,onDismiss: () -> Unit,onImport: (String) -> Unit,onFile: () -> Unit,onScan: () -> Unit,onQrImage: () -> Unit,onManual:(()->Unit)?=null) {
    val strings = LocalResources.current
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=24.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(strings.getString(R.string.add_config),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            Text(strings.getString(R.string.import_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text,{if (it.length <= SubscriptionImporter.MAX_BYTES) onText(it)},label={Text(strings.getString(R.string.link_or_config))},minLines=3,maxLines=6,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("import-text"))
            if (error != null) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("import-error"))
            Button(onClick={onImport(text)},enabled=text.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("import-submit")) { Text(if (busy) strings.getString(R.string.adding) else strings.getString(R.string.add)) }
            if (busy) TextButton(onClick=onCancel,modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.cancel_import)) }
            OutlinedButton(onClick=onScan,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("scan-qr")) { Icon(QWIPIcons.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.scan_qr_code)) }
            OutlinedButton(onClick=onQrImage,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("qr-image")) { Icon(QWIPIcons.PhotoLibrary,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.qr_from_image)) }
            OutlinedButton(onClick=onFile,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(QWIPIcons.UploadFile,null); Spacer(Modifier.width(8.dp)); Text(strings.getString(R.string.choose_file)) }
            onManual?.let{OutlinedButton(onClick=it,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("manual-server")){Icon(QWIPIcons.Add,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.new_server))}}
        }
    }
}
