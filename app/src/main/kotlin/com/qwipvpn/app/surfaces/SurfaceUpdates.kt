package com.qwipvpn.app.surfaces

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService

object SurfaceUpdates {
    fun request(context: Context) {
        val provider=ComponentName(context,context.packageName+".surfaces.QWIPWidgetProvider")
        val ids=AppWidgetManager.getInstance(context).getAppWidgetIds(provider)
        if(ids.isNotEmpty())context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,ids))
        runCatching { TileService.requestListeningState(context,ComponentName(context,context.packageName+".surfaces.VpnQuickTileService")) }
    }
}
