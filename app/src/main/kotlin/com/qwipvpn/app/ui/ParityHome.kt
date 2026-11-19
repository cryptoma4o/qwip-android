package com.qwipvpn.app.ui

import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qwipvpn.core.*
import com.qwipvpn.app.R
import kotlinx.coroutines.delay

object QWIPDesign {
    val green=Color(0xFF0B7D40)
    val card=RoundedCornerShape(22.dp)
    val screenPadding=20.dp
}

@Composable fun LatencyBadge(result:ProbeResult?,modifier:Modifier=Modifier){
    val strings=LocalResources.current
    val fresh by produceState(result?.fresh()==true,result){
        if(result!=null)do{
            value=result.fresh()
            if(value)delay((ServerProbes.CACHE_TTL_MS-(System.currentTimeMillis()-result.measuredAt)).coerceAtLeast(1))
        }while(value)
    }
    val milliseconds=result?.milliseconds
    val text=when{result==null->"—";!fresh->strings.getString(R.string.ping_stale);milliseconds!=null->{
        val resource=if(result.method=="proxy")if(milliseconds==0)R.string.ping_proxy_under_one else R.string.ping_proxy_ms else if(milliseconds==0)R.string.ping_tcp_under_one else R.string.ping_tcp_ms
        strings.getString(resource,milliseconds)
    };result.state=="unsupported"->strings.getString(R.string.ping_unsupported);else->strings.getString(R.string.ping_no_answer)}
    val amber=if(MaterialTheme.colorScheme.background.luminance()<.5f)Color(0xFFFFC15C)else Color(0xFF8A5100)
    val tint=when{result==null||!fresh->MaterialTheme.colorScheme.onSurfaceVariant;milliseconds==null->MaterialTheme.colorScheme.error;milliseconds<150->MaterialTheme.colorScheme.primary;milliseconds<400->amber;else->MaterialTheme.colorScheme.error}
    Text(text,color=tint,style=MaterialTheme.typography.labelSmall,fontFamily=FontFamily.Monospace,modifier=modifier.background(tint.copy(alpha=.10f),RoundedCornerShape(50)).padding(horizontal=8.dp,vertical=4.dp))
}

@Composable fun ConnectionScreen(store:ClientStore,state:TunnelState,ready:Boolean,busy:Boolean,
    onConnect:()->Unit,onStop:()->Unit,onLocations:()->Unit,onAdd:()->Unit,onReconnect:()->Unit={},
    probes:Map<String,ProbeResult> = emptyMap(),probing:Boolean=false,onProbe:(List<String>?)->Unit={},
    onSelect:(String)->Unit={},onFavorite:(String)->Unit={},onCollapse:(String)->Unit={},onCollapseAll:(Set<String>)->Unit={},
    onSource:(Source)->Unit={},onRefreshSource:(Source)->Unit={},onProfileActions:(Profile)->Unit={}){
    val strings=LocalResources.current
    val selected=store.profiles.firstOrNull{it.id==store.selected}
    val connected=state.phase==TunnelPhase.CONNECTED
    val pending=state.phase in listOf(TunnelPhase.CONNECTING,TunnelPhase.DISCONNECTING)
    val status=strings.getString(when(state.phase){TunnelPhase.CONNECTED->R.string.connected;TunnelPhase.CONNECTING->R.string.connecting;TunnelPhase.DISCONNECTING->R.string.disconnecting;TunnelPhase.ERROR->R.string.connection_error;else->R.string.disconnected})
    val action=strings.getString(if(connected)R.string.disconnect else if(pending)R.string.cancel_connection else R.string.connect)
    val network=networkAvailable()
    val groups=remember(store,probes){ProfileGroups.groups(store,probes)}
    var elapsed by remember{mutableLongStateOf(0)}
    LaunchedEffect(connected,state.startedAt){while(connected){elapsed=((SystemClock.elapsedRealtime()-state.startedAt)/1000).coerceAtLeast(0);delay(1000)}}
    val fontScale=LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()){
        val compact=maxHeight<640.dp||fontScale>1.3f
        Box(Modifier.fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=.13f),Color.Transparent))))
        LazyColumn(Modifier.fillMaxSize().testTag("home"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
            item{
                Column(Modifier.fillMaxWidth().padding(vertical=if(compact)4.dp else 12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Icon(QWIPIcons.Shield,null,Modifier.size(26.dp),if(connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,modifier=Modifier.testTag("connection-status").semantics{liveRegion=LiveRegionMode.Polite})
                    if(connected)Text(strings.getString(R.string.connected_location,ServerSummary.withoutFlags(state.title)),style=MaterialTheme.typography.bodyMedium,
                        textAlign=TextAlign.Center,modifier=Modifier.testTag("active-server"))
                    if(connected)Text("%02d:%02d:%02d".format(elapsed/3600,elapsed/60%60,elapsed%60),fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(strings.getString(if(ready)R.string.connect_one_tap else R.string.preparing_rules),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
                    Box(Modifier.size(if(compact)110.dp else 134.dp).background(MaterialTheme.colorScheme.surfaceContainer,CircleShape),contentAlignment=Alignment.Center){
                        Button(onClick={if(connected||pending)onStop()else if(selected==null)onAdd()else onConnect()},shape=CircleShape,contentPadding=PaddingValues(0.dp),
                            enabled=(ready||pending||connected)&&state.phase!=TunnelPhase.DISCONNECTING&&(!busy||pending||connected),
                            colors=ButtonDefaults.buttonColors(containerColor=if(connected)MaterialTheme.colorScheme.primary else Color(0xFF8E8E93),contentColor=if(connected)MaterialTheme.colorScheme.onPrimary else Color.White),
                            modifier=Modifier.size(if(compact)86.dp else 100.dp).testTag("vpn-power").semantics{contentDescription=action}){
                            if(pending)CircularProgressIndicator(Modifier.size(38.dp),color=Color.White,strokeWidth=3.dp)else Icon(QWIPIcons.PowerSettingsNew,null,Modifier.size(45.dp))
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(if(network)QWIPIcons.Wifi else QWIPIcons.WifiOff,null,Modifier.size(16.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(strings.getString(if(network)R.string.network_available else R.string.no_internet),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(connected)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                        TrafficFigure("↓",state.receiveRate,strings.getString(R.string.downloaded));TrafficFigure("↑",state.sendRate,strings.getString(R.string.uploaded))
                    }
                }
            }
            state.error?.let{error->item{Text(error,color=MaterialTheme.colorScheme.error,textAlign=TextAlign.Center)}}
            item{
                Card(onClick={if(selected==null)onAdd()else onLocations()},shape=QWIPDesign.card,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),modifier=Modifier.fillMaxWidth().testTag("location-card")){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(strings.getString(if(selected==null)R.string.first_connection else if(connected&&selected.id!=state.profileId)R.string.selected_for_next else R.string.location_selection_label),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            val flag=selected?.let{ServerSummary.flag(it.title)}
                            if(flag!=null)Text(flag,style=MaterialTheme.typography.headlineSmall)else Icon(if(selected==null)QWIPIcons.AddCircleOutline else QWIPIcons.Public,null,tint=MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)){
                                Text(selected?.title?.let(ServerSummary::withoutFlags)?:strings.getString(R.string.add_own_config),fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("selected-server"))
                                selected?.let{profile->remember(profile.configuration){ServerSummary.read(profile)}?.let{Text(it.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                            }
                            Icon(QWIPIcons.KeyboardArrowRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(connected&&selected!=null&&selected.id!=state.profileId){
                            Text(strings.getString(R.string.next_connection),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick=onReconnect,enabled=!busy&&ready,modifier=Modifier.fillMaxWidth().testTag("vpn-reconnect")){Text(strings.getString(R.string.reconnect))}
                        }
                        if(selected!=null)Box(Modifier.testTag("selected-ping").sizeIn(minWidth=48.dp,minHeight=48.dp)
                            .clickable(enabled=!probing,role=Role.Button,onClickLabel=strings.getString(R.string.check_server)){onProbe(listOf(selected.id))},contentAlignment=Alignment.Center){LatencyBadge(probes[selected.id])}
                    }
                }
            }
            if(groups.isEmpty())item{
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,QWIPDesign.card).padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(strings.getString(R.string.no_locations),fontWeight=FontWeight.SemiBold)
                    Text(strings.getString(R.string.add_existing_hint),textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick=onAdd){Text(strings.getString(R.string.add_config))}
                }
            }
            if(groups.isNotEmpty())item{
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={onProbe(null)},enabled=!probing,modifier=Modifier.testTag("probe-all")){Text(strings.getString(if(probing)R.string.checking else R.string.check_servers))}
                    Spacer(Modifier.weight(1f))
                    if(groups.size>1)TextButton(onClick={onCollapseAll(groups.map{it.id}.toSet())}){Text(strings.getString(if(groups.all{it.id in store.collapsedGroups})R.string.expand_all else R.string.collapse_all))}
                }
            }
            for(group in groups)item(key=group.id){
                Card(shape=QWIPDesign.card,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),modifier=Modifier.fillMaxWidth()){
                    Column{
                        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
                            val collapsed=group.id in store.collapsedGroups
                            val groupTitle=group.source?.title?:strings.getString(R.string.on_device)
                            val collapseLabel=strings.getString(if(collapsed)R.string.expand_group else R.string.collapse_group,groupTitle)
                            Column(Modifier.weight(1f).clickable(role=Role.Button,onClickLabel=collapseLabel){onCollapse(group.id)}.padding(vertical=16.dp).testTag("group-${group.id}")){
                                Text(group.source?.title?:strings.getString(R.string.on_device),fontWeight=FontWeight.SemiBold)
                                Text(strings.getString(R.string.location_count,group.profiles.size),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            group.source?.let{source->
                                IconButton(onClick={onRefreshSource(source)},enabled=!busy){Icon(QWIPIcons.Refresh,strings.getString(R.string.refresh_locations),Modifier.size(20.dp))}
                                IconButton(onClick={onSource(source)}){Icon(QWIPIcons.Info,strings.getString(R.string.subscription_settings),Modifier.size(20.dp))}
                            }
                            TextButton(onClick={onCollapse(group.id)},modifier=Modifier.semantics{contentDescription=collapseLabel}){Text(if(collapsed)"+" else "−")}
                        }
                        group.source?.usage?.let{usage->
                            val used=ByteTotals.add(usage.uploaded,usage.downloaded)
                            Column(Modifier.padding(horizontal=16.dp).padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(strings.getString(R.string.usage_gb,used/1_073_741_824.0),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                usage.total?.takeIf{it>0}?.let{total->LinearProgressIndicator(progress={(used.toDouble()/total).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp))}
                                usage.expires?.takeIf{it in 1..Long.MAX_VALUE/1000}?.let{expires->Text(strings.getString(R.string.access_until,java.text.DateFormat.getDateInstance().format(java.util.Date(expires*1000))),style=MaterialTheme.typography.labelSmall,color=if(expires*1000<System.currentTimeMillis())MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                        if(group.id !in store.collapsedGroups)group.profiles.forEach{profile->
                            val summary=remember(profile.configuration){ServerSummary.read(profile)}
                            ListItem(headlineContent={Text(ServerSummary.withoutFlags(profile.title),maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={summary?.let{Text(it.label)}},
                                leadingContent={val flag=ServerSummary.flag(profile.title);if(flag!=null)Text(flag)else Icon(QWIPIcons.Public,null,tint=MaterialTheme.colorScheme.primary)},
                                trailingContent={Column(horizontalAlignment=Alignment.End){LatencyBadge(probes[profile.id]);Row{
                                    if(profile.id==store.selected)Icon(QWIPIcons.Check,strings.getString(R.string.location_selected),Modifier.size(18.dp),MaterialTheme.colorScheme.primary)
                                    IconButton(onClick={onProfileActions(profile)}){Icon(QWIPIcons.Settings,strings.getString(R.string.edit_server),Modifier.size(18.dp))}
                                }}},colors=ListItemDefaults.colors(containerColor=if(profile.id==store.selected)MaterialTheme.colorScheme.primary.copy(alpha=.06f)else Color.Transparent),
                                modifier=Modifier.clickable(enabled=!busy){onSelect(profile.id)}.testTag("home-profile-${profile.id}"))
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun TrafficFigure(symbol:String,rate:Long,title:String){
    val context=androidx.compose.ui.platform.LocalContext.current
    Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$symbol ${android.text.format.Formatter.formatShortFileSize(context,rate)}/s",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.titleSmall);Text(title,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
