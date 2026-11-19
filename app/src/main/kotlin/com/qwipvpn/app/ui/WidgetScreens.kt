@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qwipvpn.app.ui

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qwipvpn.core.*
import com.qwipvpn.app.ClientViewModel
import com.qwipvpn.app.R
import com.qwipvpn.app.data.WidgetStore
import com.qwipvpn.app.surfaces.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable fun WidgetsScreen(model:ClientViewModel?=null,widgetId:Int=AppWidgetManager.INVALID_APPWIDGET_ID,onSaved:(()->Unit)?=null){
    val s=LocalResources.current;val context=LocalContext.current;val scope=rememberCoroutineScope();val store=remember{WidgetStore(context)}
    var library by remember{mutableStateOf(WidgetLibrary())};var preferences by remember{mutableStateOf(WidgetPreferences())};var loaded by remember{mutableStateOf(false)}
    var editing by remember{mutableStateOf<WidgetTheme?>(null)};var deleting by remember{mutableStateOf<WidgetTheme?>(null)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf(false)}
    var medium by remember{mutableStateOf(true)};var connected by remember{mutableStateOf(true)}
    LaunchedEffect(widgetId){runCatching{withContext(Dispatchers.IO){store.library() to store.preferences(if(widgetId>0)widgetId else 0)}}.onSuccess{library=it.first;preferences=it.second}.onFailure{error=true};loaded=true}
    val theme=library.theme(preferences.themeId)
    SettingsForm{
        Text(s.getString(R.string.widgets_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ChoicePicker(s.getString(R.string.widget_preview),if(medium)"medium"else"small",listOf("small" to s.getString(R.string.widget_size_small),"medium" to s.getString(R.string.widget_size_medium))){medium=it=="medium"}
        WidgetPreview(theme,preferences,connected,medium)
        ToggleRow(s.getString(R.string.connected),connected,{connected=it})
        ChoicePicker(s.getString(R.string.widget_layout),preferences.layout,listOf("standard" to s.getString(R.string.widget_standard),"compact" to s.getString(R.string.widget_compact),"button" to s.getString(R.string.widget_big_button),"ring" to s.getString(R.string.widget_ring),"icon" to s.getString(R.string.widget_icon))){preferences=preferences.copy(layout=it)}
        ChoicePicker(s.getString(R.string.widget_theme),preferences.themeId?:library.defaultId,library.all.map{it.id to themeName(it,s)}){id->if(widgetId>0)preferences=preferences.copy(themeId=id)else{library=library.copy(defaultId=id);preferences=preferences.copy(themeId=null)}}
        ToggleRow(s.getString(R.string.widget_show_status),preferences.showStatus,{preferences=preferences.copy(showStatus=it)})
        ToggleRow(s.getString(R.string.widget_show_server),preferences.showServer,{preferences=preferences.copy(showServer=it)})
        ToggleRow(s.getString(R.string.widget_show_flag),preferences.showFlag,{preferences=preferences.copy(showFlag=it)})
        ToggleRow(s.getString(R.string.widget_show_timer),preferences.showTimer,{preferences=preferences.copy(showTimer=it)})
        ToggleRow(s.getString(R.string.widget_show_button),preferences.showButton,{preferences=preferences.copy(showButton=it)})
        FormText(s.getString(R.string.widget_accent),preferences.accent.orEmpty(),{preferences=preferences.copy(accent=it.takeIf(String::isNotBlank))},error=preferences.accent?.let{!WidgetTheme.validColor(it)}==true)
        if(library.custom.any{it.id==theme.id})Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={editing=theme}){Text(s.getString(R.string.edit_profile))}
            TextButton(onClick={deleting=theme}){Text(s.getString(R.string.delete))}
        }
        OutlinedButton(onClick={editing=theme.copy(id=UUID.randomUUID().toString(),name=themeName(theme,s)+" · "+s.getString(R.string.widget_theme_copy))},enabled=library.custom.size<12,modifier=Modifier.fillMaxWidth().testTag("widget-new-theme")){Text(s.getString(R.string.widget_new_theme))}
        Text(s.getString(R.string.widget_theme_limit),style=MaterialTheme.typography.bodySmall)
        if(error)Text(s.getString(R.string.storage_failed),color=MaterialTheme.colorScheme.error)
        Button(onClick={scope.launch{busy=true;try{withContext(Dispatchers.IO){store.write(library);store.save(if(widgetId>0)widgetId else 0,preferences)};SurfaceUpdates.request(context);onSaved?.invoke()}catch(_:Exception){error=true}finally{busy=false}}},enabled=loaded&&!busy&&runCatching{preferences.validate()}.isSuccess,modifier=Modifier.fillMaxWidth().testTag("widget-save")){Text(s.getString(R.string.save))}
        if(widgetId<=0){
            val manager=context.getSystemService(AppWidgetManager::class.java)
            if(manager.isRequestPinAppWidgetSupported)OutlinedButton(onClick={manager.requestPinAppWidget(ComponentName(context,QWIPWidgetProvider::class.java),null,null)},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.widget_add))}
            if(Build.VERSION.SDK_INT>=33)OutlinedButton(onClick={context.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(context,VpnQuickTileService::class.java),"QWIP VPN",Icon.createWithResource(context,R.drawable.ic_vpn_notification),context.mainExecutor){ }},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.add_tile))}
            else Text(s.getString(R.string.quick_settings_hint),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))},modifier=Modifier.fillMaxWidth()){Text(s.getString(R.string.notifications_settings))}
        }
    }
    editing?.let{initial->WidgetThemeEditor(initial,store,onDismiss={editing=null}){themeValue->
        val next=runCatching{library.save(themeValue)}.getOrNull()
        if(next!=null){library=next;if(widgetId>0)preferences=preferences.copy(themeId=themeValue.id)else library=next.copy(defaultId=themeValue.id);editing=null}
    }}
    deleting?.let{value->ClearDialog(s.getString(R.string.delete_question,value.name),{deleting=null}){library=library.delete(value.id);preferences=preferences.copy(themeId=null);deleting=null}}
}
@Composable internal fun WidgetPreview(theme:WidgetTheme,preferences:WidgetPreferences,connected:Boolean,medium:Boolean=true){
    val context=LocalContext.current
    val views by produceState<RemoteViews?>(null,theme,preferences,connected,medium){
        value=withContext(Dispatchers.IO){WidgetRenderer.render(context,0,SurfaceSnapshot(if(connected)TunnelPhase.CONNECTED else TunnelPhase.DISCONNECTED,serverName="QWIP 🇳🇱",selectedServerName="QWIP 🇳🇱",startedAt=SystemClock.elapsedRealtime()-80_000,hasProfiles=true),preferences,theme,false,if(medium)320 else 170,if(medium)170 else 150)}
    }
    Box(Modifier.fillMaxWidth().height(if(medium)170.dp else 150.dp).testTag("widget-preview"),contentAlignment=androidx.compose.ui.Alignment.Center){
        if(views==null)CircularProgressIndicator()
        views?.let{remote->AndroidView(factory={FrameLayout(it).apply{importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}},update={frame->frame.removeAllViews();frame.addView(remote.apply(context,frame))},modifier=Modifier.width(if(medium)320.dp else 170.dp).fillMaxHeight().testTag("widget-preview-ready"))}
    }
}
private fun themeName(theme:WidgetTheme,resources:android.content.res.Resources):String=when(theme.id){
    "light"->resources.getString(R.string.theme_light)
    "dark"->resources.getString(R.string.widget_graphite)
    "ocean"->resources.getString(R.string.widget_ocean)
    "purple"->resources.getString(R.string.widget_lilac)
    "sunset"->resources.getString(R.string.widget_sunset)
    "mint"->resources.getString(R.string.widget_mint)
    else->theme.name
}
@Composable private fun WidgetThemeEditor(initial:WidgetTheme,store:WidgetStore,onDismiss:()->Unit,onSave:(WidgetTheme)->Unit){
    val s=LocalResources.current;val scope=rememberCoroutineScope();var theme by remember(initial.id){mutableStateOf(initial)};var error by remember{mutableStateOf(false)};var loading by remember{mutableStateOf(false)}
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)scope.launch{loading=true;try{val imported=withContext(Dispatchers.IO){store.importPhoto(uri)};theme=theme.copy(background="photo",photo=imported.first,photoLuminance=imported.second)}catch(_:Exception){error=true}finally{loading=false}}}
    FormSheet(s.getString(R.string.widget_theme),onDismiss){
        WidgetPreview(theme,WidgetPreferences(),true)
        FormText(s.getString(R.string.server_name),theme.name,{theme=theme.copy(name=it)},tag="widget-theme-name")
        ChoicePicker(s.getString(R.string.widget_background),theme.background,listOf("system" to s.getString(R.string.theme_system),"solid" to s.getString(R.string.widget_solid),"gradient" to s.getString(R.string.widget_gradient),"photo" to s.getString(R.string.widget_photo))){theme=theme.copy(background=it)}
        when(theme.background){
            "solid"->ColorField(s.getString(R.string.widget_solid),theme.solid){theme=theme.copy(solid=it)}
            "gradient"->{ColorField(s.getString(R.string.widget_gradient_start),theme.gradientStart){theme=theme.copy(gradientStart=it)};ColorField(s.getString(R.string.widget_gradient_end),theme.gradientEnd){theme=theme.copy(gradientEnd=it)};Text(s.getString(R.string.widget_gradient_angle)+": ${theme.angle}°");Slider(theme.angle.toFloat(),{theme=theme.copy(angle=it.toInt())},valueRange=0f..360f)}
            "photo"->{OutlinedButton(onClick={photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!loading){Text(s.getString(R.string.widget_pick_photo))};Text(s.getString(R.string.widget_photo_dim));Slider(theme.photoDim,{theme=theme.copy(photoDim=it)},valueRange=0f..0.8f)}
        }
        ColorField(s.getString(R.string.widget_accent),theme.accent){theme=theme.copy(accent=it)}
        ColorField(s.getString(R.string.widget_inactive),theme.inactive){theme=theme.copy(inactive=it)}
        ChoicePicker(s.getString(R.string.widget_text_tone),theme.textTone,listOf("auto" to s.getString(R.string.widget_text_auto),"light" to s.getString(R.string.widget_text_light),"dark" to s.getString(R.string.widget_text_dark),"custom" to s.getString(R.string.widget_text_custom))){theme=theme.copy(textTone=it)}
        if(theme.textTone=="custom")ColorField(s.getString(R.string.widget_text_custom),theme.textColor){theme=theme.copy(textColor=it)}
        ChoicePicker(s.getString(R.string.widget_font),theme.font,listOf("system" to s.getString(R.string.widget_font_system),"rounded" to s.getString(R.string.widget_font_rounded),"serif" to s.getString(R.string.widget_font_serif),"mono" to s.getString(R.string.widget_font_mono))){theme=theme.copy(font=it)}
        if(error)Text(s.getString(R.string.storage_failed),color=MaterialTheme.colorScheme.error)
        Button(onClick={val valid=runCatching{theme.validate()}.isSuccess;if(valid)onSave(theme)else error=true},enabled=!loading,modifier=Modifier.fillMaxWidth().testTag("widget-theme-save")){Text(s.getString(R.string.save))}
    }
}
@Composable private fun ColorField(title:String,value:String,onChange:(String)->Unit){
    FormText(title,value,onChange,error=!WidgetTheme.validColor(value))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("#0E8A47","#2789EE","#8C63E8","#E35B9F","#EA4D4D","#ED953E","#F4D15B","#28ADAB","#FFFFFF","#171B18").forEach{color->
            Box(Modifier.size(36.dp).background(androidx.compose.ui.graphics.Color(WidgetRenderer.color(color)),androidx.compose.foundation.shape.CircleShape).border(1.dp,MaterialTheme.colorScheme.outlineVariant,androidx.compose.foundation.shape.CircleShape).clickable{onChange(color)})
        }
    }
}
