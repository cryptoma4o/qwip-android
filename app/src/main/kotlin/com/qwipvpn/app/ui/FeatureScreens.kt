@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qwipvpn.core.*
import com.qwipvpn.app.ClientViewModel
import com.qwipvpn.app.R
import com.qwipvpn.app.data.AgeIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable fun FeatureScreen(page:String,model:ClientViewModel,onPage:(String)->Unit){
    when(page){
        "subscriptions"->SubscriptionsScreen(model)
        "statistics"->StatisticsScreen(model)
        "history"->HistoryScreen(model)
        "probes"->ProbeSettingsScreen(model)
        "speed"->SpeedScreen(model)
        "advanced"->AdvancedScreen(model)
        "routing-profiles"->RoutingProfilesScreen(model,onPage)
        "geo-tags"->GeoTagsScreen(model)
        "auto-rules"->AutoRulesScreen(model)
        "apps"->AppsScreen(model)
        "age"->AgeScreen(model)
        "widgets"->WidgetsScreen(model)
    }
}
@Composable private fun SubscriptionsScreen(model:ClientViewModel){
    val s=LocalResources.current;val store by model.store.collectAsStateWithLifecycle();val busy by model.busy.collectAsStateWithLifecycle()
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)model.settings(model.store.value.settings.copy(expiryReminders=true))}
    var source by remember{mutableStateOf<Source?>(null)};var deleting by remember{mutableStateOf<Source?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Button(onClick=model::openImport,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.add_config))}}
        item{OutlinedButton(onClick=model::refresh,enabled=!busy&&store.sources.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.refresh_all))}}
        items(store.sources,key={it.id}){item->
            Card(onClick={source=item},shape=QWIPDesign.card,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),modifier=Modifier.fillMaxWidth()){
                ListItem(headlineContent={Text(item.title)},supportingContent={Text(s.getString(R.string.location_count,store.profiles.count{it.sourceId==item.id}))},
                    trailingContent={IconButton(onClick={deleting=item}){Icon(QWIPIcons.DeleteOutline,s.getString(R.string.delete_subscription))}},colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent))
            }
        }
        item{Section(s.getString(R.string.subscriptions)){
            ToggleRow(s.getString(R.string.send_device_id),store.settings.sendDeviceId,{model.settings(store.settings.copy(sendDeviceId=it))},"send-device-id")
            Text(s.getString(R.string.send_device_id_hint),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(16.dp))
            if(store.settings.sendDeviceId)TextButton(onClick={model.action{withContext(Dispatchers.IO){model.app.identities.resetDeviceId()}}}){Text(s.getString(R.string.reset_device_id))}
            ToggleRow(s.getString(R.string.expiry_reminders),store.settings.expiryReminders,{enabled->if(enabled&&android.os.Build.VERSION.SDK_INT>=33&&model.app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)else model.settings(store.settings.copy(expiryReminders=enabled))})
        }}
    }
    source?.let{value->SourceDetailsSheet(model,store.sources.firstOrNull{it.id==value.id}?:value){source=null}}
    deleting?.let{value->AlertDialog(onDismissRequest={deleting=null},title={Text(s.getString(R.string.delete_subscription))},text={Text(s.getString(R.string.delete_subscription_warning))},confirmButton={TextButton(onClick={model.action{model.repository.removeSource(value.id)};deleting=null}){Text(s.getString(R.string.delete))}},dismissButton={TextButton(onClick={deleting=null}){Text(s.getString(R.string.cancel))}})}
}
@Composable private fun StatisticsScreen(model:ClientViewModel){
    val s=LocalResources.current;val context=LocalContext.current;val store by model.store.collectAsStateWithLifecycle();val telemetry by model.telemetry.collectAsStateWithLifecycle();val state by model.connection.collectAsStateWithLifecycle()
    var clear by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("statistics-page"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{ToggleRow(s.getString(R.string.statistics_enabled),store.settings.trafficStatistics,{model.settings(store.settings.copy(trafficStatistics=it))},"statistics-toggle")}
        item{Section(s.getString(R.string.current_session)){BytesRow(ByteTotals(state.sent,state.received))}}
        item{Section(s.getString(R.string.today)){BytesRow(telemetry.days.firstOrNull{it.id==java.time.LocalDate.now().toString()}?.totals?:ByteTotals())}}
        item{Section(s.getString(R.string.total_traffic)){BytesRow(telemetry.total)}}
        if(telemetry.servers.isNotEmpty())item{Text(s.getString(R.string.per_server),style=MaterialTheme.typography.titleMedium)}
        items(telemetry.servers,key={it.id}){bucket->ListItem(headlineContent={Text(bucket.title)},supportingContent={Text("↑ "+android.text.format.Formatter.formatShortFileSize(context,bucket.totals.sent)+"   ↓ "+android.text.format.Formatter.formatShortFileSize(context,bucket.totals.received))})}
        items(telemetry.days.take(30),key={"day-"+it.id}){bucket->ListItem(headlineContent={Text(bucket.id)},supportingContent={Text(android.text.format.Formatter.formatShortFileSize(context,ByteTotals.add(bucket.totals.sent,bucket.totals.received)))})}
        item{TextButton(onClick={clear=true},modifier=Modifier.testTag("statistics-clear")){Text(s.getString(R.string.clear_statistics))}}
    }
    if(clear)ClearDialog(s.getString(R.string.clear_statistics),{clear=false}){model.action{withContext(Dispatchers.IO){model.app.telemetry.clearStatistics()}};clear=false}
}
@Composable internal fun BytesRow(totals:ByteTotals){
    val s=LocalResources.current;val context=LocalContext.current
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(s.getString(R.string.uploaded)+": "+android.text.format.Formatter.formatShortFileSize(context,totals.sent))
        Text(s.getString(R.string.downloaded)+": "+android.text.format.Formatter.formatShortFileSize(context,totals.received))
    }
}
@Composable private fun HistoryScreen(model:ClientViewModel){
    val s=LocalResources.current;val context=LocalContext.current;val store by model.store.collectAsStateWithLifecycle();val telemetry by model.telemetry.collectAsStateWithLifecycle();var clear by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("history-page"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ToggleRow(s.getString(R.string.history_enabled),store.settings.connectionHistory,{model.settings(store.settings.copy(connectionHistory=it))},"history-toggle")}
        if(telemetry.history.isEmpty())item{Text(s.getString(R.string.history_empty),modifier=Modifier.testTag("history-empty"))}
        items(telemetry.history,key={it.id}){record->
            val phase=when{record.outcome=="failed"->R.string.outcome_failed;record.outcome=="interrupted"->R.string.outcome_interrupted;record.outcome=="active"&&telemetry.surface.settled().phase!=TunnelPhase.CONNECTED->R.string.outcome_interrupted;record.outcome=="active"->R.string.connected;record.reason=="user"->R.string.reason_user;record.reason=="network"->R.string.reason_network;record.reason=="error"->R.string.reason_error;else->R.string.reason_system}
            val duration=((record.ended?:System.currentTimeMillis())-record.started).coerceAtLeast(0)/1000
            Card(shape=QWIPDesign.card,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(record.serverName,style=MaterialTheme.typography.titleMedium);Text(s.getString(phase),color=if(record.outcome=="failed")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.started)),style=MaterialTheme.typography.labelSmall)
                    Text("%02d:%02d:%02d".format(duration/3600,duration/60%60,duration%60)+" · "+android.text.format.Formatter.formatShortFileSize(context,ByteTotals.add(record.totals.sent,record.totals.received)),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        item{TextButton(onClick={clear=true},modifier=Modifier.testTag("history-clear")){Text(s.getString(R.string.clear_history))}}
    }
    if(clear)ClearDialog(s.getString(R.string.clear_history),{clear=false}){model.action{withContext(Dispatchers.IO){model.app.telemetry.clearHistory()}};clear=false}
}
@Composable private fun ProbeSettingsScreen(model:ClientViewModel){
    val s=LocalResources.current;val store by model.store.collectAsStateWithLifecycle();var settings by remember(store.settings){mutableStateOf(store.settings)};var timeout by remember{mutableStateOf(settings.probeTimeout.toString())}
    val probing by model.probing.collectAsStateWithLifecycle();val ip by model.externalIp.collectAsStateWithLifecycle();val checkingIp by model.checkingIp.collectAsStateWithLifecycle()
    SettingsForm{
        ChoicePicker(s.getString(R.string.probe_method),settings.probeMethod,listOf("tcp" to "TCP","proxy" to s.getString(R.string.probe_proxy))){settings=settings.copy(probeMethod=it)}
        Text(s.getString(R.string.probe_methods_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FormText(s.getString(R.string.probe_timeout),timeout,{timeout=it;settings=settings.copy(probeTimeout=it.toIntOrNull()?:0)})
        FormText(s.getString(R.string.probe_url),settings.probeUrl,{settings=settings.copy(probeUrl=it)})
        ToggleRow(s.getString(R.string.probe_double),settings.probeDouble,{settings=settings.copy(probeDouble=it)},"probe-double")
        if(settings.probeDouble)Text(s.getString(R.string.probe_double_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow(s.getString(R.string.probe_on_open),settings.probeOnOpen,{settings=settings.copy(probeOnOpen=it)},"probe-on-open")
        ChoicePicker(s.getString(R.string.server_sort),settings.serverSort,listOf("original" to s.getString(R.string.original_order),"name" to s.getString(R.string.sort_name),"latency" to s.getString(R.string.sort_latency))){settings=settings.copy(serverSort=it)}
        ToggleRow(s.getString(R.string.smart_selection),settings.smartSelection,{settings=settings.copy(smartSelection=it)},"smart-selection")
        Text(s.getString(R.string.smart_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FormText(s.getString(R.string.ip_service_url),settings.ipCheckUrl,{settings=settings.copy(ipCheckUrl=it)})
        Button(onClick={model.settings(settings)},enabled=SettingsValidation.valid(settings),modifier=Modifier.fillMaxWidth().testTag("probe-save")){Text(s.getString(R.string.save))}
        OutlinedButton(onClick={model.probeServers()},enabled=!probing,modifier=Modifier.fillMaxWidth()){Text(s.getString(if(probing)R.string.checking else R.string.check_servers))}
        OutlinedButton(onClick=model::checkIp,enabled=!checkingIp,modifier=Modifier.fillMaxWidth().testTag("ip-check")){Text(s.getString(R.string.check_ip))}
        ip?.let{Text(s.getString(R.string.ip_address)+": "+it,modifier=Modifier.testTag("external-ip"))}
    }
}
@Composable private fun SpeedScreen(model:ClientViewModel){
    val s=LocalResources.current;val context=LocalContext.current;val store by model.store.collectAsStateWithLifecycle();val telemetry by model.telemetry.collectAsStateWithLifecycle();val running by model.speedRunning.collectAsStateWithLifecycle();val rate by model.speedProgress.collectAsStateWithLifecycle()
    var url by remember(store.settings.speedTestUrl){mutableStateOf(store.settings.speedTestUrl)};var clear by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Text(s.getString(R.string.speed_test_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{FormText(s.getString(R.string.speed_test_url),url,{url=it},enabled=!running)}
        item{OutlinedButton(onClick={model.settings(store.settings.copy(speedTestUrl=url))},enabled=runCatching{SubscriptionHttp.validateUrl(url)}.isSuccess&&!running,modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.save))}}
        item{Text(s.getString(R.string.mbps,rate),style=MaterialTheme.typography.displaySmall,modifier=Modifier.testTag("speed-rate"))}
        item{Button(onClick=model::testSpeed,modifier=Modifier.fillMaxWidth().testTag("speed-start")){Text(s.getString(if(running)R.string.cancel_test else R.string.speed_test))}}
        if(running)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(telemetry.speedTests,key={it.id}){measurement->ListItem(headlineContent={Text(s.getString(R.string.mbps,measurement.megabits))},supportingContent={Column{Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(measurement.date)));measurement.serverName?.let{Text(it)};Text(s.getString(R.string.ping_ms,measurement.latency)+" · "+android.text.format.Formatter.formatShortFileSize(context,measurement.bytes))}})}
        item{TextButton(onClick={clear=true}){Text(s.getString(R.string.clear_speed_history))}}
    }
    if(clear)ClearDialog(s.getString(R.string.clear_speed_history),{clear=false}){model.action{withContext(Dispatchers.IO){model.app.telemetry.clearSpeed()}};clear=false}
}
@Composable private fun AdvancedScreen(model:ClientViewModel){
    val s=LocalResources.current;val store by model.store.collectAsStateWithLifecycle();var xray by remember(store.settings.xray){mutableStateOf(store.settings.xray)};var reset by remember{mutableStateOf<String?>(null)}
    SettingsForm{
        ToggleRow(s.getString(R.string.sniffing),xray.sniffing,{xray=xray.copy(sniffing=it)})
        ToggleRow(s.getString(R.string.mux),xray.muxEnabled,{xray=xray.copy(muxEnabled=it)},"xray-mux")
        if(xray.muxEnabled){FormText(s.getString(R.string.mux_concurrency),xray.muxConcurrency.toString(),{xray=xray.copy(muxConcurrency=it.toIntOrNull()?:0)});FormText(s.getString(R.string.xudp_concurrency),xray.xudpConcurrency.toString(),{xray=xray.copy(xudpConcurrency=it.toIntOrNull()?:0)})}
        ToggleRow(s.getString(R.string.fragment),xray.fragmentEnabled,{xray=xray.copy(fragmentEnabled=it)},"xray-fragment")
        if(xray.fragmentEnabled){FormText(s.getString(R.string.fragment_packets),xray.fragmentPackets,{xray=xray.copy(fragmentPackets=it)});FormText(s.getString(R.string.fragment_length),xray.fragmentLength,{xray=xray.copy(fragmentLength=it)});FormText(s.getString(R.string.fragment_interval),xray.fragmentInterval,{xray=xray.copy(fragmentInterval=it)})}
        ToggleRow(s.getString(R.string.udp_noise),xray.noisesEnabled,{xray=xray.copy(noisesEnabled=it)})
        if(xray.noisesEnabled){
            xray.noises.forEachIndexed{index,noise->
                ChoicePicker(s.getString(R.string.noise_type),noise.type,listOf("rand","str","base64").map{it to it}){kind->xray=xray.copy(noises=xray.noises.mapIndexed{i,n->if(i==index)n.copy(type=kind)else n})}
                FormText(s.getString(R.string.noise_packet),noise.packet,{value->xray=xray.copy(noises=xray.noises.mapIndexed{i,n->if(i==index)n.copy(packet=value)else n})})
                FormText(s.getString(R.string.noise_delay),noise.delay,{value->xray=xray.copy(noises=xray.noises.mapIndexed{i,n->if(i==index)n.copy(delay=value)else n})})
                if(xray.noises.size>1)TextButton(onClick={xray=xray.copy(noises=xray.noises.filterIndexed{i,_->i!=index})}){Text(s.getString(R.string.delete))}
            }
            if(xray.noises.size<4)TextButton(onClick={xray=xray.copy(noises=xray.noises+XrayNoise())}){Text(s.getString(R.string.add))}
        }
        ToggleRow(s.getString(R.string.apply_provider_json),xray.applyToProviderJson,{xray=xray.copy(applyToProviderJson=it)})
        Text(s.getString(R.string.provider_overlay_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick={model.settings(store.settings.copy(xray=xray))},enabled=xray.valid(),modifier=Modifier.fillMaxWidth().testTag("advanced-save")){Text(s.getString(R.string.save))}
        listOf("settings" to R.string.reset_settings,"routing" to R.string.reset_routing,"everything" to R.string.reset_everything).forEach{(mode,title)->TextButton(onClick={reset=mode}){Text(s.getString(title),color=MaterialTheme.colorScheme.error)}}
    }
    reset?.let{mode->AlertDialog(onDismissRequest={reset=null},title={Text(s.getString(R.string.reset_question))},text={Text(s.getString(if(mode=="everything")R.string.reset_everything_hint else R.string.settings_next_connection))},confirmButton={TextButton(onClick={model.reset(mode);reset=null}){Text(s.getString(R.string.confirm))}},dismissButton={TextButton(onClick={reset=null}){Text(s.getString(R.string.cancel))}})}
}
@Composable private fun AutoRulesScreen(model:ClientViewModel){
    val s=LocalResources.current;val context=LocalContext.current;val store by model.store.collectAsStateWithLifecycle();var editing by remember{mutableStateOf<AutoConnectRule?>(null)}
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(s.getString(R.string.auto_connect_hint),style=MaterialTheme.typography.bodyMedium)}
        items(store.settings.onDemandRules,key={it.id}){rule->ListItem(headlineContent={Text(s.getString(when(rule.action){"disconnect"->R.string.disconnect_action;"ignore"->R.string.ignore_action;else->R.string.connect_action}))},supportingContent={Text(s.getString(when(rule.network){"wifi"->R.string.wifi_network;"cellular"->R.string.cellular_network;else->R.string.any_network}))},trailingContent={IconButton(onClick={model.settings(store.settings.copy(onDemandRules=store.settings.onDemandRules.filterNot{it.id==rule.id}))}){Icon(QWIPIcons.DeleteOutline,s.getString(R.string.delete))}},modifier=Modifier.clickable{editing=rule})}
        if(store.settings.onDemandRules.size<20)item{Button(onClick={editing=AutoConnectRule()},modifier=Modifier.fillMaxWidth().testTag("auto-rule-add")){Text(s.getString(R.string.new_auto_rule))}}
    }
    editing?.let{initial->
        var draft by remember(initial.id){mutableStateOf(initial)};var servers by remember(initial.id){mutableStateOf(initial.dnsServers.joinToString("\n"))};var domains by remember(initial.id){mutableStateOf(initial.dnsDomains.joinToString("\n"))}
        FormSheet(s.getString(R.string.auto_rules),{editing=null}){
            ChoicePicker(s.getString(R.string.rule_action),draft.action,listOf("connect" to s.getString(R.string.connect_action),"disconnect" to s.getString(R.string.disconnect_action),"ignore" to s.getString(R.string.ignore_action))){draft=draft.copy(action=it)}
            ChoicePicker(s.getString(R.string.current_network),draft.network,listOf("any" to s.getString(R.string.any_network),"wifi" to s.getString(R.string.wifi_network),"cellular" to s.getString(R.string.cellular_network))){draft=draft.copy(network=it,ssids=if(it=="cellular")emptyList()else draft.ssids)}
            if(draft.network!="cellular"){
                FormText(s.getString(R.string.wifi_names),draft.ssids.joinToString("\n"),{draft=draft.copy(ssids=lines(it))},lines=2)
                if(draft.ssids.isNotEmpty()){
                    Text(s.getString(R.string.wifi_permission_hint),style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION))else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+context.packageName)))}){Text(s.getString(R.string.wifi_permission))}
                }
            }
            FormText(s.getString(R.string.dns_match),servers,{servers=it;draft=draft.copy(dnsServers=lines(it))},lines=2)
            FormText(s.getString(R.string.domains_match),domains,{domains=it;draft=draft.copy(dnsDomains=lines(it))},lines=2)
            Button(onClick={val current=store.settings.onDemandRules;model.settings(store.settings.copy(onDemandRules=if(current.any{it.id==draft.id})current.map{if(it.id==draft.id)draft else it}else current+draft));editing=null},enabled=draft.valid(),modifier=Modifier.fillMaxWidth().testTag("auto-rule-save")){Text(s.getString(R.string.save))}
        }
    }
}
@Composable private fun AgeScreen(model:ClientViewModel){
    val s=LocalResources.current;val context=LocalContext.current
    var identity by remember{mutableStateOf<AgeIdentity?>(null)};var secret by remember{mutableStateOf("")};var public by remember{mutableStateOf("")};var hybrid by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){identity=withContext(Dispatchers.IO){model.app.identities.age()};public=identity?.publicKey.orEmpty()}
    fun generate(){model.action{val next=model.app.identities.generateAge(hybrid);identity=next;public=next.publicKey;secret=""}}
    SettingsForm{
        Text(s.getString(R.string.age_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
        identity?.publicKey?.takeIf(String::isNotBlank)?.let{Text(it);OutlinedButton(onClick={SecureSharing.copy(context,it)}){Text(s.getString(R.string.copy_result))}}
        ToggleRow(s.getString(R.string.age_hybrid),hybrid,{hybrid=it})
        Button(onClick={if(identity==null)generate()else confirm=true},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.age_create))}
        FormText(s.getString(R.string.age_secret_key),secret,{secret=it},secret=true)
        FormText(s.getString(R.string.age_public_key),public,{public=it})
        OutlinedButton(onClick={model.action{withContext(Dispatchers.IO){model.app.identities.saveAge(AgeIdentity(secret,public))};identity=AgeIdentity(secret,public);secret=""}},enabled=secret.startsWith("AGE-SECRET-KEY-")&&(public.isBlank()||public.startsWith("age1")),modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.save))}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(s.getString(R.string.age_create))},text={Text(s.getString(R.string.age_replace_warning))},confirmButton={TextButton(onClick={confirm=false;generate()}){Text(s.getString(R.string.confirm))}},dismissButton={TextButton(onClick={confirm=false}){Text(s.getString(R.string.cancel))}})
}
@Composable internal fun ClearDialog(title:String,onDismiss:()->Unit,onClear:()->Unit){val s=LocalResources.current;AlertDialog(onDismissRequest=onDismiss,title={Text(title)},confirmButton={TextButton(onClick=onClear){Text(s.getString(R.string.confirm))}},dismissButton={TextButton(onClick=onDismiss){Text(s.getString(R.string.cancel))}})}
internal fun lines(value:String)=value.lines().map(String::trim).filter(String::isNotEmpty)

private data class InstalledApp(val packageName:String,val label:String)
@Composable private fun AppsScreen(model:ClientViewModel){
    val s=LocalResources.current;val context=LocalContext.current;val store by model.store.collectAsStateWithLifecycle()
    val settings=store.settings;var query by remember{mutableStateOf("")}
    val installed by produceState(emptyList<InstalledApp>()){
        value=withContext(Dispatchers.IO){
            val manager=context.packageManager
            manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                .map{InstalledApp(it.activityInfo.packageName,it.loadLabel(manager).toString())}
                .filter{it.packageName!=context.packageName}.distinctBy{it.packageName}.sortedBy{it.label.lowercase()}
        }
    }
    val selected=AppSplit.clean(settings.appPackages)
    val known=installed.map{it.packageName}.toSet()
    val apps=installed+selected.filter{it !in known}.map{InstalledApp(it,it)}
    val visible=apps.filter{query.isBlank()||it.label.contains(query,true)||it.packageName.contains(query,true)}
    LazyColumn(Modifier.fillMaxSize().testTag("apps-page"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(s.getString(R.string.apps_hint),style=MaterialTheme.typography.bodyMedium)}
        item{ChoicePicker(s.getString(R.string.apps_mode),settings.appMode,listOf(AppSplit.ALL to s.getString(R.string.apps_all),AppSplit.EXCEPT to s.getString(R.string.apps_except),AppSplit.ONLY to s.getString(R.string.apps_only))){model.settings(settings.copy(appMode=it))}}
        if(settings.appMode==AppSplit.ONLY&&selected.isEmpty())item{Text(s.getString(R.string.apps_need_selection),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("apps-warning"))}
        if(settings.appMode!=AppSplit.ALL){
            item{OutlinedTextField(query,{query=it},label={Text(s.getString(R.string.apps_search))},singleLine=true,modifier=Modifier.fillMaxWidth())}
            items(visible,key={it.packageName}){app->
                val checked=app.packageName in selected
                ListItem(headlineContent={Text(app.label)},supportingContent={if(app.label!=app.packageName)Text(app.packageName)},
                    trailingContent={Checkbox(checked,{model.settings(settings.copy(appPackages=AppSplit.toggle(settings.appPackages,app.packageName,it)))})},
                    colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),
                    modifier=Modifier.clickable{model.settings(settings.copy(appPackages=AppSplit.toggle(settings.appPackages,app.packageName,!checked)))})
            }
        }
        item{Text(s.getString(R.string.apps_restart),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
