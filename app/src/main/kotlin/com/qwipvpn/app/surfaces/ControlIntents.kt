package com.qwipvpn.app.surfaces

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.qwipvpn.app.MainActivity
import com.qwipvpn.app.R
import java.util.UUID

/** Exported import URLs cannot forge private launcher/widget control actions. */
object ControlIntents {
    private const val ACTION="com.qwipvpn.app.CONTROL"
    @Synchronized private fun token(context:Context):String {
        val prefs=context.getSharedPreferences("surface-control",Context.MODE_PRIVATE)
        return prefs.getString("token",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("token",it).commit()}
    }
    fun intent(context:Context,command:String)=Intent(context,MainActivity::class.java).setAction(ACTION).setData(android.net.Uri.parse("qwip-control://"+command))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("command",command).putExtra("control-token",token(context))
    fun pending(context:Context,command:String,requestCode:Int=command.hashCode()):PendingIntent=PendingIntent.getActivity(context,requestCode,intent(context,command),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun command(context:Context,intent:Intent):String? {
        if(intent.action!=ACTION || intent.getStringExtra("control-token")!=token(context))return null
        return intent.getStringExtra("command")?.takeIf{it in setOf("connect","disconnect","open")}
    }
    fun shortcuts(context:Context){
        val manager=context.getSystemService(ShortcutManager::class.java)
        runCatching{manager.dynamicShortcuts=listOf("connect" to R.string.shortcut_connect,"disconnect" to R.string.shortcut_disconnect).map{(command,title)->
            ShortcutInfo.Builder(context,command).setShortLabel(context.getString(title)).setIcon(Icon.createWithResource(context,R.drawable.ic_vpn_notification)).setIntent(intent(context,command)).build()
        }}
    }
}
