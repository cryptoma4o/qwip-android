@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qwipvpn.core.*
import com.qwipvpn.app.ClientViewModel
import com.qwipvpn.app.ServerEditorDraft
import com.qwipvpn.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun FormText(label:String,value:String,onValue:(String)->Unit,secret:Boolean=false,lines:Int=1,error:Boolean=false,tag:String="",enabled:Boolean=true,
    keyboardType:KeyboardType=KeyboardType.Text,supportingText:String?=null,modifier:Modifier=Modifier){
    val s=LocalResources.current
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value,onValue,label={Text(label)},singleLine=lines==1,minLines=lines,maxLines=if(lines==1)1 else 8,
        isError=error,enabled=enabled,visualTransformation=if(secret&&!visible)PasswordVisualTransformation()else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(secret&&keyboardType==KeyboardType.Text)KeyboardType.Password else keyboardType,
            autoCorrectEnabled=if(secret||keyboardType!=KeyboardType.Text)false else null,imeAction=if(lines==1)ImeAction.Next else ImeAction.Default),
        trailingIcon=if(secret){{TextButton(onClick={visible=!visible}){Text(s.getString(if(visible)R.string.hide_secret else R.string.show_secret))}}}else null,
        supportingText=supportingText?.let{{Text(it)}},modifier=modifier.fillMaxWidth().testTag(tag))
}
@Composable internal fun ChoicePicker(title:String,value:String,options:List<Pair<String,String>>,onChange:(String)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(title,style=MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            options.forEach{(key,label)->FilterChip(selected=value==key,onClick={onChange(key)},label={Text(label)})}
        }
    }
}
@Composable internal fun ToggleRow(title:String,value:Boolean,onChange:(Boolean)->Unit,tag:String=""){
    ListItem(headlineContent={Text(title)},trailingContent={Switch(value,onChange,modifier=Modifier.testTag(tag))},colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent))
}
@Composable internal fun FormSheet(title:String,onDismiss:()->Unit,footer:(@Composable ColumnScope.()->Unit)?=null,canDismiss:Boolean=true,content:@Composable ColumnScope.()->Unit){
    val canDismissNow by rememberUpdatedState(canDismiss)
    val dismissNow by rememberUpdatedState(onDismiss)
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={target->
        if(target==SheetValue.Hidden&&!canDismissNow){dismissNow();false}else true
    })
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState){
        Column(Modifier.fillMaxWidth().then(if(footer!=null)Modifier.fillMaxHeight(.94f)else Modifier).imePadding().navigationBarsPadding()){
            Column(Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Text(title,style=MaterialTheme.typography.headlineSmall)
                content()
            }
            if(footer!=null){
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=footer)
            }
        }
    }
}

@Composable fun ServerEditorSheet(model:ClientViewModel,editor:ServerEditorDraft,onDismiss:()->Unit){
    val s=LocalResources.current;val busy by model.busy.collectAsStateWithLifecycle();val failure by model.lastError.collectAsStateWithLifecycle()
    val profile=editor.profile
    val form=editor.form
    fun updateForm(value:ServerForm){model.serverEditor.value=model.serverEditor.value?.copy(form=value)}
    val attempted=editor.attempted
    val problems=form.problems()
    var confirmClose by rememberSaveable{mutableStateOf(false)}
    var focusError by remember{mutableStateOf<String?>(null)}
    val fields=remember{listOf("name","address","port","credential","serverName","publicKey","shortId","alpn").associateWith{FocusRequester() to BringIntoViewRequester()}}
    LaunchedEffect(focusError){focusError?.let{key->
        fields.getValue(key).first.requestFocus()
        fields.getValue(key).second.bringIntoView()
        focusError=null
    }}
    fun dismiss(){if(!busy){if(form!=editor.initial)confirmClose=true else onDismiss()}}
    @Composable fun field(key:String,label:String,value:String,onValue:(String)->Unit,secret:Boolean=false,type:KeyboardType=KeyboardType.Ascii,tag:String=""){
        val error=attempted&&key in problems
        val text=if(error)s.getString(when(key){"name"->R.string.error_server_name;"address"->R.string.error_server_address;"port"->R.string.error_server_port;"credential"->R.string.error_server_credential;"serverName"->R.string.error_server_sni;"publicKey"->R.string.error_reality_key;"shortId"->R.string.error_reality_id;else->R.string.error_server_alpn})else null
        FormText(label,value,onValue,secret=secret,error=error,tag=tag,enabled=!busy,keyboardType=type,supportingText=text,
            modifier=Modifier.focusRequester(fields.getValue(key).first).bringIntoViewRequester(fields.getValue(key).second))
    }
    FormSheet(s.getString(if(profile==null)R.string.new_server else R.string.edit_server),::dismiss,canDismiss=!busy&&form==editor.initial,footer={
        failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(attempted&&problems.isNotEmpty())Text(s.getString(R.string.invalid_fields),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton(onClick=::dismiss,enabled=!busy,modifier=Modifier.testTag("server-cancel")){Text(s.getString(R.string.cancel))}
            Button(onClick={
                model.serverEditor.value=editor.copy(form=form,attempted=true,advanced=editor.advanced||"alpn" in problems)
                if(problems.isEmpty())model.saveServer(form,profile,onDismiss)
                else focusError=fields.keys.firstOrNull{it in problems&&(it!="alpn"||form.supportsTransport&&form.security=="tls")}
            },enabled=!busy,modifier=Modifier.weight(1f).testTag("server-save")){Text(s.getString(if(profile?.sourceId!=null)R.string.save_local_copy else R.string.save))}
        }
    }){
        field("name",s.getString(R.string.server_name),form.name,{updateForm(form.copy(name=it))},type=KeyboardType.Text,tag="server-name")
        ChoicePicker(s.getString(R.string.server_protocol),form.protocol,ServerForm.protocols.map{it to it.uppercase()}){updateForm(form.copy(protocol=it))}
        field("address",s.getString(R.string.server_address),form.address,{updateForm(form.copy(address=it))},type=KeyboardType.Uri,tag="server-address")
        field("port",s.getString(R.string.server_port),form.port,{updateForm(form.copy(port=it))},type=KeyboardType.Number,tag="server-port")
        if(form.usesId)field("credential",s.getString(R.string.server_id),form.id,{updateForm(form.copy(id=it))},secret=true,tag="server-id")
        if(form.protocol in listOf("trojan","shadowsocks","socks","http")){
            if(form.protocol in listOf("socks","http"))FormText(s.getString(R.string.server_user),form.user,{updateForm(form.copy(user=it))})
            field("credential",s.getString(R.string.server_password),form.password,{updateForm(form.copy(password=it))},secret=true,tag="server-password")
        }
        if(form.protocol=="shadowsocks")ChoicePicker(s.getString(R.string.ss_method),form.method,ServerForm.shadowsocksMethods.map{it to it}){updateForm(form.copy(method=it))}
        if(form.supportsTransport){
            ChoicePicker(s.getString(R.string.server_transport),form.network,ServerForm.networks.map{it to if(it=="raw")"TCP" else it.uppercase()}){updateForm(form.copy(network=it))}
            when(form.network){
                "ws","httpupgrade","xhttp"->{FormText(s.getString(R.string.server_host),form.host,{updateForm(form.copy(host=it))});FormText(s.getString(R.string.server_path),form.path,{updateForm(form.copy(path=it))})}
                "grpc"->{FormText(s.getString(R.string.grpc_service),form.serviceName,{updateForm(form.copy(serviceName=it))});ToggleRow(s.getString(R.string.multi_mode),form.multiMode,{updateForm(form.copy(multiMode=it))})}
                "kcp"->FormText(s.getString(R.string.kcp_seed),form.seed,{updateForm(form.copy(seed=it))})
                "raw"->{ToggleRow(s.getString(R.string.raw_http_header),form.rawHttpHeader,{updateForm(form.copy(rawHttpHeader=it))});if(form.rawHttpHeader){FormText(s.getString(R.string.server_host),form.host,{updateForm(form.copy(host=it))});FormText(s.getString(R.string.server_path),form.path,{updateForm(form.copy(path=it))})}}
            }
            if(form.network=="xhttp")ChoicePicker(s.getString(R.string.xhttp_mode),form.xhttpMode,listOf("auto","packet-up","stream-up","stream-one").map{it to it}){updateForm(form.copy(xhttpMode=it))}
            ChoicePicker(s.getString(R.string.server_security),form.security,ServerForm.securities.map{it to it.uppercase()}){updateForm(form.copy(security=it))}
            if(form.security!="none"){
                field("serverName",s.getString(R.string.server_sni),form.serverName,{updateForm(form.copy(serverName=it))},type=KeyboardType.Uri)
                if(form.security=="reality"){
                    field("publicKey",s.getString(R.string.reality_public_key),form.publicKey,{updateForm(form.copy(publicKey=it))})
                    field("shortId",s.getString(R.string.reality_short_id),form.shortId,{updateForm(form.copy(shortId=it))})
                }
            }
        }
        if(form.supportsTransport){
            TextButton(onClick={model.serverEditor.value=editor.copy(form=form,advanced=!editor.advanced)},modifier=Modifier.testTag("server-advanced")){
                Text(s.getString(if(editor.advanced)R.string.hide_advanced_fields else R.string.show_advanced_fields))
            }
            if(editor.advanced){
                if(form.protocol=="vless")FormText(s.getString(R.string.vless_flow),form.flow,{updateForm(form.copy(flow=it))},keyboardType=KeyboardType.Ascii)
                if(form.protocol=="vmess")ChoicePicker(s.getString(R.string.server_security),form.vmessSecurity,listOf("auto","aes-128-gcm","chacha20-poly1305","none").map{it to it}){updateForm(form.copy(vmessSecurity=it))}
                if(form.security!="none")FormText(s.getString(R.string.server_fingerprint),form.fingerprint,{updateForm(form.copy(fingerprint=it))},keyboardType=KeyboardType.Ascii)
                if(form.security=="tls")field("alpn",s.getString(R.string.server_alpn),form.alpn,{updateForm(form.copy(alpn=it))})
                if(form.security=="reality")FormText("SpiderX",form.spiderX,{updateForm(form.copy(spiderX=it))},keyboardType=KeyboardType.Uri)
            }
        }
        Text(s.getString(R.string.form_json_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(confirmClose)AlertDialog(onDismissRequest={confirmClose=false},title={Text(s.getString(R.string.discard_changes_title))},text={Text(s.getString(R.string.discard_changes_message))},
        confirmButton={TextButton(onClick={confirmClose=false;onDismiss()},modifier=Modifier.testTag("discard-server-draft")){Text(s.getString(R.string.discard_changes))}},
        dismissButton={TextButton(onClick={confirmClose=false}){Text(s.getString(R.string.keep_editing))}})
}

@Composable fun SourceDetailsSheet(model:ClientViewModel,source:Source,onDismiss:()->Unit){
    val s=LocalResources.current;val store by model.store.collectAsStateWithLifecycle();val busy by model.busy.collectAsStateWithLifecycle()
    var draft by remember(source.id){mutableStateOf(source)}
    val valid=draft.title.isNotBlank()&&draft.updateHours in 1..720&&runCatching{SubscriptionHttp.validateUrl(draft.url);draft.fallbackUrl?.let(SubscriptionHttp::validateUrl)}.isSuccess
    FormSheet(s.getString(R.string.subscription_settings),onDismiss){
        FormText(s.getString(R.string.server_name),draft.title,{draft=draft.copy(title=it)})
        FormText(s.getString(R.string.source_url),draft.url,{draft=draft.copy(url=it)},tag="source-url")
        ChoicePicker(s.getString(R.string.subscription_format),draft.format,listOf("auto" to s.getString(R.string.widget_text_auto),"xray-json" to "Xray JSON")){draft=draft.copy(format=it)}
        FormText(s.getString(R.string.fallback_url),draft.fallbackUrl.orEmpty(),{draft=draft.copy(fallbackUrl=it.takeIf(String::isNotBlank))})
        ToggleRow(s.getString(R.string.auto_update),draft.autoUpdate,{draft=draft.copy(autoUpdate=it)})
        var hours by remember(source.id){mutableStateOf(draft.updateHours.toString())}
        FormText(s.getString(R.string.update_interval_hours),hours,{hours=it;draft=draft.copy(updateHours=it.toIntOrNull()?:0)})
        ChoicePicker(s.getString(R.string.routing_profiles),draft.routingId.orEmpty(),listOf("" to s.getString(R.string.none_selected))+store.routingProfiles.map{it.id to it.name}){draft=draft.copy(routingId=it.takeIf(String::isNotBlank))}
        Text(s.getString(R.string.last_updated)+": "+if(source.fetchedAt>0)java.text.DateFormat.getDateTimeInstance().format(java.util.Date(source.fetchedAt))else s.getString(R.string.never_checked),style=MaterialTheme.typography.bodySmall)
        source.usage?.let{usage->
            Text(s.getString(R.string.usage_gb,ByteTotals.add(usage.uploaded,usage.downloaded)/1_073_741_824.0))
            usage.total?.let{Text(s.getString(R.string.total_quota)+": "+android.text.format.Formatter.formatShortFileSize(LocalContext.current,it))}
            usage.expires?.takeIf{it in 1..Long.MAX_VALUE/1000}?.let{Text(s.getString(R.string.access_until,java.text.DateFormat.getDateInstance().format(java.util.Date(it*1000))))}
        }
        Button(onClick={model.source(draft)},enabled=valid&&!busy,modifier=Modifier.fillMaxWidth().testTag("source-save")){Text(s.getString(R.string.save))}
        OutlinedButton(onClick={model.refreshSource(source)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.refresh_locations))}
    }
}

@Composable fun ProfileActionsSheet(model:ClientViewModel,profile:Profile,onDismiss:()->Unit,onForm:()->Unit){
    val s=LocalResources.current;val context=LocalContext.current;val busy by model.busy.collectAsStateWithLifecycle();val error by model.lastError.collectAsStateWithLifecycle()
    var mode by remember(profile.id){mutableStateOf("actions")};var json by remember(profile.id){mutableStateOf(profile.configuration)};var title by remember(profile.id){mutableStateOf(profile.title)}
    FormSheet(profile.title,{if(!busy)onDismiss()}){
        when(mode){
            "json"->{
                FormText(s.getString(R.string.server_name),title,{title=it})
                Text(s.getString(R.string.edit_profile_hint),style=MaterialTheme.typography.bodySmall)
                FormText("Xray JSON",json,{if(it.toByteArray().size<=SubscriptionImporter.MAX_BYTES)json=it},lines=6,tag="profile-json")
                Button(onClick={model.action(s.getString(R.string.import_failed),s.getString(R.string.config_saved)){model.repository.editProfile(profile.id,json,title);onDismiss()}},enabled=!busy&&title.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(s.getString(if(profile.sourceId!=null)R.string.save_local_copy else R.string.save))}
            }
            "qr"->{
                val bitmap by produceState<android.graphics.Bitmap?>(null,profile.id){value=withContext(Dispatchers.IO){runCatching{android.graphics.Bitmap.createBitmap(QrEncoder.pixels(SecureSharing.payload(profile)),640,640,android.graphics.Bitmap.Config.ARGB_8888)}.getOrNull()}}
                bitmap?.let{Image(it.asImageBitmap(),s.getString(R.string.show_qr),Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.White))}?:Text(s.getString(R.string.qr_too_large))
                Text(s.getString(R.string.copy_sensitive_hint),style=MaterialTheme.typography.bodySmall)
            }
            "delete"->{Text(s.getString(R.string.delete_config_warning));Button(onClick={model.action{model.repository.removeProfile(profile.id);onDismiss()}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.delete))}}
            else->{
                Text(s.getString(R.string.copy_sensitive_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                ServerSummary.read(profile)?.let{Text(it.label,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(ServerForm.from(profile)!=null)OutlinedButton(onClick=onForm,modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.edit_server))}
                OutlinedButton(onClick={mode="json"},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.edit_json))}
                OutlinedButton(onClick={model.favorite(profile.id);onDismiss()},modifier=Modifier.fillMaxWidth()){Text(s.getString(if(profile.favorite)R.string.remove_favorite else R.string.add_favorite))}
                OutlinedButton(onClick={model.action(success=s.getString(R.string.copied)){SecureSharing.copy(context,profile.configuration)}},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.copy_config))}
                OutlinedButton(onClick={mode="qr"},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.show_qr))}
                OutlinedButton(onClick={model.action{withContext(Dispatchers.IO){SecureSharing.shareFile(context,profile.configuration,s.getString(R.string.share_config))}}},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.share_config))}
                TextButton(onClick={mode="delete"},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.delete_config))}
            }
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
