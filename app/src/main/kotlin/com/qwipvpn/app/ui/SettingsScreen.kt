@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.qwipvpn.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwipvpn.core.*
import com.qwipvpn.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun SettingsScreen(page: String,store: ClientStore,state: TunnelState,engineVersion: String,busy: Boolean,
    onPage: (String) -> Unit,onSettings: (ClientSettings) -> Unit,onAdd: () -> Unit,onRefresh: () -> Unit,
    onGeo: (Boolean,List<String>?) -> Unit,onExport: () -> Unit,onRestore: () -> Unit,
    onDeleteProfile: (String) -> Unit,onDeleteSource: (String) -> Unit,onEdit: (String,String) -> Unit,model:com.qwipvpn.app.ClientViewModel?=null,listState:LazyListState=rememberLazyListState()) {
    val strings = LocalResources.current
    val settings = store.settings
    var backupAction by remember { mutableStateOf<String?>(null) }
    if(model!=null&&page in setOf("subscriptions","statistics","history","advanced","routing-profiles","geo-tags","probes","speed","widgets","auto-rules","apps","age")){
        FeatureScreen(page,model,onPage);return
    }
    when (page) {
        "settings" -> LazyColumn(Modifier.fillMaxSize().testTag("settings"),state=listState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item { Section(strings.getString(R.string.configurations_section)) {
                SettingRow(strings.getString(R.string.add_config),null,QWIPIcons.AddCircleOutline,onAdd)
                SettingRow(strings.getString(R.string.subscriptions),null,QWIPIcons.Refresh){onPage("subscriptions")}
                SettingRow(strings.getString(R.string.profiles),strings.getString(R.string.profiles_subtitle),QWIPIcons.FolderOpen) { onPage("profiles") }
            } }
            item { Section(strings.getString(R.string.connection)) {
                SettingRow(strings.getString(R.string.dns_connection),strings.getString(R.string.dns_subtitle),QWIPIcons.Dns) { onPage("dns") }
                SettingRow(strings.getString(R.string.routing),strings.getString(R.string.routing_subtitle),QWIPIcons.Route) { onPage("routing-profiles") }
                SettingRow(strings.getString(R.string.advanced_settings),null,QWIPIcons.Settings){onPage("advanced")}
                SettingRow(strings.getString(R.string.geodata),if (store.geoGeneration != null) strings.getString(R.string.rules_on_device) else strings.getString(R.string.preparation_required),QWIPIcons.Public) { onPage("geo") }
            } }
            item { Section(strings.getString(R.string.checks_section)) {
                SettingRow(strings.getString(R.string.statistics),null,QWIPIcons.MonitorHeart){onPage("statistics")}
                SettingRow(strings.getString(R.string.connection_history),null,QWIPIcons.Restore){onPage("history")}
                SettingRow(strings.getString(R.string.network_diagnostics),strings.getString(R.string.network_diagnostics_subtitle),QWIPIcons.Public) { onPage("network-diagnostics") }
                SettingRow(strings.getString(R.string.latency_check),null,QWIPIcons.Wifi){onPage("probes")}
                SettingRow(strings.getString(R.string.speed_test),null,QWIPIcons.MonitorHeart){onPage("speed")}
                SettingRow(strings.getString(R.string.diagnostics),strings.getString(R.string.diagnostics_subtitle),QWIPIcons.Info){onPage("diagnostics")}
            } }
            item { Section(strings.getString(R.string.design_section)) {
                SettingRow(strings.getString(R.string.appearance),strings.getString(R.string.appearance_subtitle),QWIPIcons.Palette) { onPage("appearance") }
                SettingRow(strings.getString(R.string.widgets),null,QWIPIcons.Palette){onPage("widgets")}
            } }
            item { Section(strings.getString(R.string.data_security_section)) {
                SettingRow(strings.getString(R.string.age_keys),null,QWIPIcons.Shield){onPage("age")}
                SettingRow(strings.getString(R.string.save_backup),null,QWIPIcons.SaveAlt) { backupAction="export" }
                SettingRow(strings.getString(R.string.restore_backup),null,QWIPIcons.Restore) { backupAction="restore" }
                SettingRow(strings.getString(R.string.about),strings.getString(R.string.app_version_subtitle,BuildConfig.VERSION_NAME),QWIPIcons.Info) { onPage("about") }
            } }
            item { Text(strings.getString(R.string.settings_next_connection),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=8.dp)) }
        }
        "profiles" -> ProfilesScreen(store,busy,onAdd,onRefresh,onDeleteProfile,onDeleteSource,onEdit)
        "dns" -> {
            var useProfile by remember(settings) { mutableStateOf(settings.useProfileDns) }
            var dns by remember(settings) { mutableStateOf(settings.dns.joinToString("\n")) }
            var mtu by remember(settings) { mutableIntStateOf(settings.mtu) }
            var auto by remember(settings){mutableStateOf(settings.autoConnect)}
            var refreshOpen by remember(settings){mutableStateOf(settings.refreshOnOpen)}
            var included by remember(settings){mutableStateOf(settings.includedRoutes.joinToString("\n"))}
            var excluded by remember(settings){mutableStateOf(settings.excludedRoutes.joinToString("\n"))}
            val context=LocalContext.current
            val addresses = dns.lines().map(String::trim).filter(String::isNotEmpty)
            SettingsForm {
                Text(strings.getString(R.string.dns_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
                ListItem(headlineContent={Text(strings.getString(R.string.profile_dns))},supportingContent={Text(strings.getString(R.string.profile_dns_hint))},trailingContent={Switch(useProfile,{useProfile=it})})
                OutlinedTextField(dns,{dns=it},label={Text(strings.getString(R.string.fallback_dns))},supportingText={Text(strings.getString(R.string.one_ip_per_line))},minLines=2,modifier=Modifier.fillMaxWidth(),isError=addresses.isEmpty()||addresses.any { !RuntimeConfiguration.isIpLiteral(it) })
                Text(strings.getString(R.string.mtu),style=MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(1280,1400,1500).forEach { value -> FilterChip(selected=mtu==value,onClick={mtu=value},label={Text(value.toString())}) } }
                ToggleRow(strings.getString(R.string.auto_connect),auto,{auto=it},"auto-connect")
                Text(strings.getString(R.string.auto_connect_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(settings.autoConnect)OutlinedButton(onClick={onPage("auto-rules")}){Text(strings.getString(R.string.auto_rules))}
                ToggleRow(strings.getString(R.string.refresh_on_open),refreshOpen,{refreshOpen=it})
                OutlinedButton(onClick={onPage("apps")},modifier=Modifier.testTag("apps-open")){Text(strings.getString(R.string.apps_title)+": "+strings.getString(when(settings.appMode){AppSplit.EXCEPT->R.string.apps_except;AppSplit.ONLY->R.string.apps_only;else->R.string.apps_all}))}
                FormText(strings.getString(R.string.routes_include),included,{included=it},lines=2)
                FormText(strings.getString(R.string.routes_exclude),excluded,{excluded=it},lines=2)
                Text(strings.getString(R.string.routes_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strings.getString(R.string.always_on_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS))}){Text(strings.getString(R.string.vpn_system_settings))}
                Button(onClick={onSettings(settings.copy(useProfileDns=useProfile,dns=addresses,mtu=mtu,autoConnect=auto,refreshOnOpen=refreshOpen,includedRoutes=lines(included),excludedRoutes=lines(excluded)))},enabled=!busy&&addresses.isNotEmpty()&&addresses.all(RuntimeConfiguration::isIpLiteral)&&runCatching{Cidr.routes(lines(included),lines(excluded))}.isSuccess,modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.save)) }
            }
        }
        "routing" -> {
            var lan by remember(settings) { mutableStateOf(settings.bypassLan) }
            var direct by remember(settings) { mutableStateOf(settings.directDomains.joinToString("\n")) }
            var block by remember(settings) { mutableStateOf(settings.blockedDomains.joinToString("\n")) }
            fun domains(text: String) = text.lines().map(String::trim).filter(String::isNotEmpty)
            fun valid(text: String) = domains(text).size<=500 && domains(text).all { d -> d.length<254&&d.none { it.isWhitespace()||it=='/'||it==':' } }
            SettingsForm {
                Text(strings.getString(R.string.routing_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
                ListItem(headlineContent={Text(strings.getString(R.string.bypass_lan))},supportingContent={Text(strings.getString(R.string.bypass_lan_hint))},trailingContent={Switch(lan,{lan=it})})
                OutlinedTextField(direct,{direct=it},label={Text(strings.getString(R.string.direct_domains))},supportingText={Text(strings.getString(R.string.domains_hint))},minLines=3,maxLines=6,isError=!valid(direct),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(block,{block=it},label={Text(strings.getString(R.string.blocked_domains))},minLines=3,maxLines=6,isError=!valid(block),modifier=Modifier.fillMaxWidth())
                Button(onClick={onSettings(settings.copy(bypassLan=lan,directDomains=domains(direct),blockedDomains=domains(block)))},enabled=!busy&&valid(direct)&&valid(block),modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.save)) }
            }
        }
        "geo" -> {
            var ipUrl by remember { mutableStateOf("") }; var siteUrl by remember { mutableStateOf("") }
            val custom = ipUrl.isNotBlank() || siteUrl.isNotBlank()
            val valid = !custom || listOf(ipUrl,siteUrl).all { runCatching { SubscriptionHttp.validateUrl(it) }.isSuccess }
            SettingsForm {
                Text(if (store.geoGeneration != null) strings.getString(R.string.rules_ready) else strings.getString(R.string.rules_not_ready),style=MaterialTheme.typography.titleLarge)
                Text(strings.getString(R.string.geodata_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={onGeo(false,null)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.restore_bundled_rules)) }
                Text(strings.getString(R.string.rule_sources),style=MaterialTheme.typography.titleMedium)
                Text(strings.getString(R.string.rule_sources_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(ipUrl,{ipUrl=it},label={Text(strings.getString(R.string.geoip_url))},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(siteUrl,{siteUrl=it},label={Text(strings.getString(R.string.geosite_url))},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(onClick={onGeo(true,if (custom) listOf(ipUrl,siteUrl) else null)},enabled=!busy&&valid,modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.download_rules)) }
                Text(strings.getString(R.string.rules_update_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(model!=null){
                    var datName by remember{mutableStateOf("")};var datUrl by remember{mutableStateOf("")};var datKind by remember{mutableStateOf("domain")}
                    Text(strings.getString(R.string.custom_dat),style=MaterialTheme.typography.titleMedium)
                    store.geoSources.forEach{source->Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                        Text(source.name+".dat · "+strings.getString(if(source.kind=="ip")R.string.ip_addresses else R.string.domains),Modifier.weight(1f))
                        TextButton(onClick={model.geoExtras(store.geoSources.filterNot{it.name==source.name})},enabled=!busy){Text(strings.getString(R.string.delete))}
                    }}
                    FormText(strings.getString(R.string.dat_name),datName,{datName=it},tag="dat-name")
                    FormText(strings.getString(R.string.dat_url),datUrl,{datUrl=it},tag="dat-url")
                    ChoicePicker(strings.getString(R.string.dat_kind),datKind,listOf("ip" to strings.getString(R.string.ip_addresses),"domain" to strings.getString(R.string.domains))){datKind=it}
                    val extra=GeoSource(datName.trim(),datUrl.trim(),datKind)
                    Button(onClick={model.geoExtras(store.geoSources.filterNot{it.name==extra.name}+extra)},enabled=!busy&&store.geoSources.size<12&&runCatching{extra.validate()}.isSuccess,modifier=Modifier.fillMaxWidth()){Text(strings.getString(R.string.add))}
                    Text(strings.getString(R.string.dat_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "appearance" -> SettingsForm {
            Section(strings.getString(R.string.theme)) { listOf("system" to strings.getString(R.string.theme_system),"light" to strings.getString(R.string.theme_light),"dark" to strings.getString(R.string.theme_dark)).forEach { (key,title) ->
                ListItem(headlineContent={Text(title)},leadingContent={RadioButton(settings.theme==key,{onSettings(settings.copy(theme=key))},enabled=!busy)},modifier=Modifier.clickable(enabled=!busy) { onSettings(settings.copy(theme=key)) })
            } }
        }
        "network-diagnostics" -> NetworkDiagnosticsScreen(state)
        "diagnostics" -> {
            val context = LocalContext.current
            val phase = when (state.phase) { TunnelPhase.CONNECTED -> strings.getString(R.string.connected); TunnelPhase.CONNECTING -> strings.getString(R.string.connection); TunnelPhase.DISCONNECTING -> strings.getString(R.string.disconnecting_short); TunnelPhase.ERROR -> strings.getString(R.string.connection_failure_short); else -> strings.getString(R.string.disconnected) }
            val report = strings.getString(R.string.connection_diagnostic_report,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,
                Build.VERSION.RELEASE,Build.VERSION.SDK_INT,engineVersion,phase,strings.getString(if(store.geoGeneration!=null)R.string.ready else R.string.not_ready))
            SettingsForm {
                Text(strings.getString(R.string.status_value,phase),style=MaterialTheme.typography.titleLarge)
                state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Text(strings.getString(R.string.engine_value,engineVersion))
                Text(strings.getString(R.string.app_version,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE))
                Text(strings.getString(R.string.privacy_diagnostics),color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("QWIP diagnostics",report))},modifier=Modifier.fillMaxWidth()) { Text(strings.getString(R.string.copy_diagnostics)) }
            }
        }
        "about" -> AboutScreen(store,state,engineVersion)
    }
    backupAction?.let { action ->
        AlertDialog(onDismissRequest={backupAction=null},title={Text(if (action=="export") strings.getString(R.string.backup) else strings.getString(R.string.restore_data_question))},
            text={Text(if (action=="export") strings.getString(R.string.backup_warning) else strings.getString(R.string.restore_warning))},
            confirmButton={TextButton(onClick={backupAction=null;if(action=="export")onExport()else onRestore()},enabled=!busy){Text(if(action=="export")strings.getString(R.string.save_file) else strings.getString(R.string.choose_file))}},
            dismissButton={TextButton(onClick={backupAction=null}){Text(strings.getString(R.string.cancel))}})
    }
}

@Composable private fun ProfilesScreen(store: ClientStore,busy: Boolean,onAdd: () -> Unit,onRefresh: () -> Unit,onDeleteProfile: (String) -> Unit,onDeleteSource: (String) -> Unit,onEdit: (String,String) -> Unit) {
    val strings = LocalResources.current
    var delete by remember { mutableStateOf<Pair<String,String>?>(null) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Button(onClick=onAdd,enabled=!busy,modifier=Modifier.fillMaxWidth()) { Icon(QWIPIcons.Add,null); Spacer(Modifier.width(8.dp)); Text(strings.getString(R.string.add_config)) } }
        if (store.sources.isNotEmpty()) {
            item { Text(strings.getString(R.string.subscriptions),style=MaterialTheme.typography.titleMedium); TextButton(onClick=onRefresh,enabled=!busy){Text(strings.getString(R.string.refresh_all)) } }
            items(store.sources,key={"source-"+it.id}) { source ->
                val used = source.usage?.let { (it.uploaded/1024.0/1024.0/1024.0)+(it.downloaded/1024.0/1024.0/1024.0) }
                ListItem(headlineContent={Text(source.title)},supportingContent={Column {
                    Text(strings.getString(R.string.location_count,store.profiles.count { it.sourceId==source.id }))
                    if (used != null) Text(strings.getString(R.string.usage_gb,used))
                    source.usage?.expires?.let { expiry -> if (expiry < Long.MAX_VALUE/1000) Text(strings.getString(R.string.access_until,java.text.DateFormat.getDateInstance().format(java.util.Date(expiry*1000)))) }
                } },trailingContent={IconButton(onClick={delete="source" to source.id},enabled=!busy){Icon(QWIPIcons.DeleteOutline,strings.getString(R.string.delete_subscription))}})
            }
        }
        item { Text(strings.getString(R.string.profiles),style=MaterialTheme.typography.titleMedium) }
        if (store.profiles.isEmpty()) item { Text(strings.getString(R.string.add_existing_hint),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        items(store.profiles,key={it.id}) { profile ->
            ListItem(headlineContent={Text(profile.title)},supportingContent={Text(strings.getString(R.string.tap_to_edit))},
                trailingContent={IconButton(onClick={delete="profile" to profile.id},enabled=!busy){Icon(QWIPIcons.DeleteOutline,strings.getString(R.string.delete_config))}},modifier=Modifier.clickable(enabled=!busy){editing=profile})
        }
    }
    delete?.let { (kind,id) -> AlertDialog(onDismissRequest={delete=null},title={Text(strings.getString(R.string.delete_question,strings.getString(if(kind=="source")R.string.subscription_object else R.string.config_object)))},
        text={Text(if(kind=="source")strings.getString(R.string.delete_subscription_warning) else strings.getString(R.string.delete_config_warning))},
        confirmButton={TextButton(onClick={delete=null;if(kind=="source")onDeleteSource(id)else onDeleteProfile(id)}){Text(strings.getString(R.string.delete))}},dismissButton={TextButton(onClick={delete=null}){Text(strings.getString(R.string.cancel))}}) }
    editing?.let { profile ->
        var config by remember(profile.id) { mutableStateOf(profile.configuration) }
        ModalBottomSheet(onDismissRequest={if(!busy)editing=null}) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(strings.getString(R.string.edit_profile),style=MaterialTheme.typography.headlineSmall)
                Text(strings.getString(R.string.edit_profile_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(config,{if(it.toByteArray().size<=SubscriptionImporter.MAX_BYTES)config=it},label={Text("Xray JSON")},minLines=8,maxLines=14,enabled=!busy,modifier=Modifier.fillMaxWidth())
                Button(onClick={onEdit(profile.id,config);editing=null},enabled=!busy&&config.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(strings.getString(R.string.save))}
            }
        }
    }
}

@Composable private fun AboutScreen(store: ClientStore,state: TunnelState,engineVersion: String) {
    val strings = LocalResources.current
    val context = LocalContext.current
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    var license by remember { mutableStateOf<String?>(null) }
    val notices = mapOf("libXray-LICENSE.txt" to "libXray · MIT","Xray-core-LICENSE.txt" to "Xray-core · MPL 2.0",
        "Go-LICENSE.txt" to "Go · BSD","geoip-LICENSE.txt" to "GeoIP · CC BY-SA 4.0","geosite-LICENSE.txt" to "GeoSite · MIT","GeoDataAttribution.txt" to strings.getString(R.string.database_attribution),"EngineNotices.txt" to strings.getString(R.string.engine_dependencies),"AndroidNotices.txt" to strings.getString(R.string.android_libraries))
    SettingsForm {
        Text("QWIP",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text(strings.getString(R.string.app_description))
        SecretSupportLogs(store,state,engineVersion)
        Text(strings.getString(R.string.copyright))
        Text(strings.getString(R.string.storage_privacy),color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { links.openUri("https://qwipvpn.com/client/privacy") }) {
            Text(strings.getString(R.string.vpn_privacy_policy))
        }
        Text(strings.getString(R.string.open_source_components),style=MaterialTheme.typography.titleMedium)
        notices.forEach { (file,title) -> TextButton(onClick={license=file}) { Text(title) } }
    }
    license?.let { file ->
        val text by produceState(strings.getString(R.string.loading),file) { value = withContext(Dispatchers.IO) { runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault(strings.getString(R.string.license_unavailable)) } }
        ModalBottomSheet(onDismissRequest={license=null}) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=650.dp),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Text(notices[file] ?: strings.getString(R.string.license),style=MaterialTheme.typography.titleLarge) }
                items(text.chunked(4000)) { chunk -> Text(chunk,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable internal fun SettingsForm(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content)
}
@Composable internal fun Section(title: String,content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=12.dp))
        Card(shape=QWIPDesign.card,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) { Column(content=content) }
    }
}
@Composable internal fun SettingRow(title: String,subtitle: String?,icon: androidx.compose.ui.graphics.vector.ImageVector,onClick: () -> Unit) {
    ListItem(headlineContent={Text(title)},supportingContent=subtitle?.let{{Text(it)}},leadingContent={Icon(icon,null,tint=MaterialTheme.colorScheme.primary)},
        trailingContent={Icon(QWIPIcons.KeyboardArrowRight,null)},modifier=Modifier.clickable(onClick=onClick),colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surfaceContainer))
}
