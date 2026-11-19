package com.qwipvpn.app.surfaces

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qwipvpn.core.TunnelPhase
import com.qwipvpn.app.QWIPApplication
import com.qwipvpn.app.R
import kotlinx.coroutines.*

class VpnQuickTileService:TileService(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var refresh:Job?=null
    private var phase=TunnelPhase.DISCONNECTED
    override fun onStartListening(){super.onStartListening();refresh?.cancel();refresh=scope.launch{
        while(isActive){
            val snapshot=withContext(Dispatchers.IO){runCatching{(application as QWIPApplication).telemetry.read().surface.settled()}.getOrNull()}
            phase=snapshot?.phase?:TunnelPhase.DISCONNECTED
            qsTile?.apply{
                state=if(phase==TunnelPhase.CONNECTED)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label="QWIP VPN"
                if(Build.VERSION.SDK_INT>=29)subtitle=getString(when(phase){TunnelPhase.CONNECTED->R.string.connected;TunnelPhase.CONNECTING->R.string.connecting;TunnelPhase.DISCONNECTING->R.string.disconnecting;else->R.string.disconnected})
                contentDescription=label;updateTile()
            }
            delay(1500)
        }
    }}
    override fun onStopListening(){refresh?.cancel();super.onStopListening()}
    override fun onClick(){super.onClick();if(isLocked)unlockAndRun{open()}else open()}
    // The PendingIntent overload exists only from API 34. API 28–33 require Intent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(){
        val command=if(phase in listOf(TunnelPhase.CONNECTED,TunnelPhase.CONNECTING,TunnelPhase.DISCONNECTING))"disconnect"else"connect"
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(ControlIntents.pending(this,command,170))
        else @Suppress("DEPRECATION") startActivityAndCollapse(ControlIntents.intent(this,command))
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
