package com.qwipvpn.app.surfaces

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.os.Bundle
import android.os.SystemClock
import com.qwipvpn.core.*
import com.qwipvpn.app.QWIPApplication
import com.qwipvpn.app.data.WidgetStore
import kotlinx.coroutines.*

class QWIPWidgetProvider:AppWidgetProvider(){
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{
            try{
                val store=WidgetStore(context);val library=runCatching{store.library()}.getOrDefault(WidgetLibrary())
                val snapshot=runCatching{(context.applicationContext as QWIPApplication).telemetry.read().surface}.getOrDefault(SurfaceSnapshot())
                for(id in ids){if(manager.getAppWidgetInfo(id)?.provider?.packageName!=context.packageName)continue;val preferences=runCatching{store.preferences(id)}.getOrDefault(WidgetPreferences());manager.updateAppWidget(id,WidgetRenderer.render(context,id,snapshot,preferences,library.theme(preferences.themeId)))}
                if(snapshot.settled().phase in listOf(TunnelPhase.CONNECTED,TunnelPhase.CONNECTING,TunnelPhase.DISCONNECTING)){
                    val alarm=PendingIntent.getBroadcast(context,171,Intent(context,QWIPWidgetProvider::class.java).setAction(CHECK),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime()+95_000,alarm)
                }
            }finally{pending.finish()}
        }
    }
    override fun onReceive(context:Context,intent:Intent){if(intent.action==CHECK){val manager=AppWidgetManager.getInstance(context);onUpdate(context,manager,manager.getAppWidgetIds(ComponentName(context,QWIPWidgetProvider::class.java)))}else super.onReceive(context,intent)}
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle){onUpdate(context,manager,intArrayOf(id))}
    override fun onDeleted(context:Context,ids:IntArray){val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{WidgetStore(context).remove(ids)}finally{pending.finish()}}}
    companion object{private const val CHECK="com.qwipvpn.app.WIDGET_FRESHNESS"}
}
