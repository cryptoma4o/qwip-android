@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable fun RoutingProfilesScreen(model:ClientViewModel,onPage:(String)->Unit){
    val s=LocalResources.current;val context=LocalContext.current;val store by model.store.collectAsStateWithLifecycle()
    var editing by remember{mutableStateOf<RoutingProfile?>(null)};var country by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf<RoutingProfile?>(null)}
    val selected=store.profiles.firstOrNull{it.id==store.selected}
    val chosen=store.routingProfiles.firstOrNull{it.id==store.activeRoutingId}
    val sourceRouting=store.sources.firstOrNull{it.id==selected?.sourceId}?.routingId
    val effective=store.routingProfiles.firstOrNull{it.id==(sourceRouting?:store.activeRoutingId)}
    LazyColumn(Modifier.fillMaxSize().testTag("routing-profiles"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(s.getString(R.string.routing_selection,chosen?.name?:s.getString(R.string.none_selected)),modifier=Modifier.testTag("routing-selection"))
                if(chosen!=null)OutlinedButton(onClick={model.action{model.repository.update{it.copy(activeRoutingId=null)}}},modifier=Modifier.fillMaxWidth().testTag("routing-disable")){Text(s.getString(R.string.disable_routing))}
                if(sourceRouting!=null)Text(s.getString(R.string.source_routing_override,effective?.name?:s.getString(R.string.none_selected)),style=MaterialTheme.typography.bodySmall)
                if(selected!=null&&effective!=null&&!effective.applies(selected))Text(s.getString(if(!effective.enabled)R.string.routing_profile_disabled else R.string.routing_provider_opt_in),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(store.routingProfiles,key={it.id}){profile->
            Card(shape=QWIPDesign.card,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){
                ListItem(headlineContent={Text(profile.name)},leadingContent={RadioButton(store.activeRoutingId==profile.id,{model.action{model.repository.update{it.copy(activeRoutingId=profile.id)}}})},
                    trailingContent={Row{IconButton(onClick={model.action{val link=RoutingLinks.export(profile);SecureSharing.shareText(context,link,s.getString(R.string.routing_export))}}){Icon(QWIPIcons.SaveAlt,s.getString(R.string.routing_export))};IconButton(onClick={deleting=profile}){Icon(QWIPIcons.DeleteOutline,s.getString(R.string.delete))}}},
                    modifier=Modifier.clickable{editing=profile},colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent))
            }
        }
        item{Button(onClick={editing=RoutingProfile(name=s.getString(R.string.routing_profiles))},modifier=Modifier.fillMaxWidth().testTag("routing-new")){Text(s.getString(R.string.new_routing))}}
        item{Section(s.getString(R.string.routing_profiles)){
            SettingRow(s.getString(R.string.preset_lan),null,QWIPIcons.Route){editing=RoutingProfile.localNetwork(s.getString(R.string.preset_lan))}
            SettingRow(s.getString(R.string.preset_country),null,QWIPIcons.Public){country=true}
            SettingRow(s.getString(R.string.geo_tags),null,QWIPIcons.Public){onPage("geo-tags")}
            SettingRow(s.getString(R.string.simple_rules),null,QWIPIcons.Settings){onPage("routing")}
        }}
        item{Text(s.getString(R.string.settings_next_connection),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    editing?.let{profile->RoutingEditorSheet(model,profile){editing=null}}
    deleting?.let{profile->ClearDialog(s.getString(R.string.delete_question,profile.name),{deleting=null}){model.action{model.repository.removeRouting(profile.id)};deleting=null}}
    if(country)CountryPresetSheet(model,onDismiss={country=false}){profile->country=false;editing=profile}
}
@Composable private fun RoutingEditorSheet(model:ClientViewModel,initial:RoutingProfile,onDismiss:()->Unit){
    val s=LocalResources.current;val context=LocalContext.current;val busy by model.busy.collectAsStateWithLifecycle();var draft by remember(initial.id){mutableStateOf(initial)}
    var hosts by remember(initial.id){mutableStateOf(initial.hosts.entries.joinToString("\n"){it.key+"="+it.value.joinToString(",")})}
    var invalid by remember{mutableStateOf(false)}
    fun result():RoutingProfile{
        val entries=lines(hosts).associate{line->val parts=line.split('=',limit=2);require(parts.size==2);parts[0].trim() to parts[1].split(',').map(String::trim)}
        return draft.copy(hosts=entries).also{it.validate()}
    }
    FormSheet(s.getString(R.string.routing_profiles),{if(!busy)onDismiss()}){
        FormText(s.getString(R.string.server_name),draft.name,{draft=draft.copy(name=it)},tag="routing-name")
        ToggleRow(s.getString(R.string.apply_rules),draft.enabled,{draft=draft.copy(enabled=it)})
        ToggleRow(s.getString(R.string.global_proxy),draft.globalProxy,{draft=draft.copy(globalProxy=it)})
        ToggleRow(s.getString(R.string.apply_provider_json),draft.applyToProviderJson,{draft=draft.copy(applyToProviderJson=it)})
        Text(s.getString(R.string.provider_overlay_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ChoicePicker(s.getString(R.string.domain_strategy),draft.strategy,listOf("AsIs","IPIfNonMatch","IPOnDemand").map{it to it}){draft=draft.copy(strategy=it)}
        FormText(s.getString(R.string.direct_domains),draft.directDomains.joinToString("\n"),{draft=draft.copy(directDomains=lines(it))},lines=2,tag="routing-direct-domains")
        FormText(s.getString(R.string.direct_ips),draft.directIps.joinToString("\n"),{draft=draft.copy(directIps=lines(it))},lines=2)
        FormText(s.getString(R.string.proxy_domains),draft.proxyDomains.joinToString("\n"),{draft=draft.copy(proxyDomains=lines(it))},lines=2)
        FormText(s.getString(R.string.proxy_ips),draft.proxyIps.joinToString("\n"),{draft=draft.copy(proxyIps=lines(it))},lines=2)
        FormText(s.getString(R.string.blocked_domains),draft.blockDomains.joinToString("\n"),{draft=draft.copy(blockDomains=lines(it))},lines=2)
        FormText(s.getString(R.string.blocked_ips),draft.blockIps.joinToString("\n"),{draft=draft.copy(blockIps=lines(it))},lines=2)
        ChoicePicker(s.getString(R.string.rule_order),draft.order.joinToString(","),listOf("block,direct,proxy","direct,block,proxy","proxy,block,direct","block,proxy,direct","direct,proxy,block","proxy,direct,block").map{it to it.replace(","," → ")}){draft=draft.copy(order=it.split(','))}
        DnsEditor(s.getString(R.string.remote_dns),draft.remoteDns){draft=draft.copy(remoteDns=it)}
        DnsEditor(s.getString(R.string.domestic_dns),draft.domesticDns){draft=draft.copy(domesticDns=it)}
        FormText(s.getString(R.string.dns_hosts),hosts,{hosts=it},lines=3)
        ToggleRow(s.getString(R.string.fake_dns),draft.fakeDns,{draft=draft.copy(fakeDns=it)})
        FormText(s.getString(R.string.geoip_url),draft.geoIpUrl.orEmpty(),{draft=draft.copy(geoIpUrl=it.takeIf(String::isNotBlank))})
        FormText(s.getString(R.string.geosite_url),draft.geoSiteUrl.orEmpty(),{draft=draft.copy(geoSiteUrl=it.takeIf(String::isNotBlank))})
        if(invalid)Text(s.getString(R.string.invalid_fields),color=MaterialTheme.colorScheme.error)
        Button(onClick={val profile=runCatching{result()}.getOrNull();if(profile==null)invalid=true else model.saveRouting(profile,onDismiss)},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("routing-save")){Text(s.getString(R.string.save))}
        OutlinedButton(onClick={val profile=runCatching{result()}.getOrNull();if(profile==null)invalid=true else model.action{SecureSharing.copy(context,RoutingLinks.export(profile))}},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.routing_export))}
    }
}
@Composable private fun DnsEditor(title:String,dns:RoutingDns,onChange:(RoutingDns)->Unit){
    val s=LocalResources.current
    Text(title,style=MaterialTheme.typography.titleMedium)
    ChoicePicker(s.getString(R.string.dns_type),dns.kind,listOf("DoU","DoH","DoT").map{it to it}){onChange(dns.copy(kind=it))}
    if(dns.kind!="DoU")FormText(s.getString(R.string.server_address),dns.address,{onChange(dns.copy(address=it))})
    FormText(s.getString(R.string.bootstrap_ip),dns.bootstrap,{onChange(dns.copy(bootstrap=it))},error=!RuntimeConfiguration.isIpLiteral(dns.bootstrap))
}
@Composable fun GeoTagsScreen(model:ClientViewModel){
    val s=LocalResources.current;val store by model.store.collectAsStateWithLifecycle();var kind by remember{mutableStateOf("geoip")};var search by remember{mutableStateOf("")}
    val tags by produceState<List<Pair<String,Int>>?>(null,store.geoGeneration,kind){value=null;value=runCatching{model.app.geoStore.tags(store.geoGeneration,kind)}.getOrDefault(emptyList())}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ChoicePicker(s.getString(R.string.geo_tags),kind,listOf("geoip" to "GeoIP","geosite" to "GeoSite")+store.geoSources.map{it.name to it.name+".dat"}){kind=it}}
        item{FormText(s.getString(R.string.search),search,{search=it},tag="geo-search")}
        if(tags==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(tags.orEmpty().filter{it.first.contains(search,true)},key={it.first}){(tag,count)->ListItem(headlineContent={Text((if(kind in setOf("geoip","geosite"))"$kind:"else"ext:$kind.dat:")+tag.lowercase(Locale.ROOT))},supportingContent={Text(count.toString())})}
    }
}
@Composable private fun CountryPresetSheet(model:ClientViewModel,onDismiss:()->Unit,onSelected:(RoutingProfile)->Unit){
    val s=LocalResources.current;val store by model.store.collectAsStateWithLifecycle();var search by remember{mutableStateOf("")};var tags by remember{mutableStateOf<Pair<List<Pair<String,Int>>,List<Pair<String,Int>>>?>(null)}
    LaunchedEffect(store.geoGeneration){tags=runCatching{model.app.geoStore.tags(store.geoGeneration,"geoip") to model.app.geoStore.tags(store.geoGeneration,"geosite")}.getOrNull()}
    val locale=s.configuration.locales[0]
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(s.getString(R.string.preset_country),style=MaterialTheme.typography.headlineSmall);FormText(s.getString(R.string.search),search,{search=it})
            if(tags==null)LinearProgressIndicator(Modifier.fillMaxWidth())
            val entries=tags?.first.orEmpty().filter{it.first.length==2&&it.first.all(Char::isLetter)}.map{it.first to Locale.Builder().setRegion(it.first).build().getDisplayCountry(locale)}.filter{it.second.contains(search,true)||it.first.contains(search,true)}.sortedBy{it.second}
            LazyColumn{items(entries,key={it.first}){(code,name)->ListItem(headlineContent={Text(name)},modifier=Modifier.clickable{val values=tags?:return@clickable;onSelected(RoutingProfile.country(code,values.first.map{it.first}.toSet(),values.second.map{it.first}.toSet(),s.getString(R.string.preset_country)+": "+name))})}}
        }
    }
}
