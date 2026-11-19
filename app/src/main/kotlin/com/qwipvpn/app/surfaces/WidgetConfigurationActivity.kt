package com.qwipvpn.app.surfaces

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.qwipvpn.app.ui.WidgetsScreen

class WidgetConfigurationActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider=AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        if(id<=0||provider?.className!=QWIPWidgetProvider::class.java.name||provider.packageName!=packageName){finish();return}
        setContent{MaterialTheme(colorScheme=if(isSystemInDarkTheme())darkColorScheme(primary=Color(0xFF32C45E))else lightColorScheme(primary=Color(0xFF0E8A47))){
            Surface(modifier=Modifier.safeDrawingPadding()){WidgetsScreen(widgetId=id,onSaved={setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()})}
        }}
    }
}
