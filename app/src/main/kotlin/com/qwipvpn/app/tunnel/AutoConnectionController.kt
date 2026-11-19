package com.qwipvpn.app.tunnel

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.*
import android.os.*
import com.qwipvpn.core.*
import com.qwipvpn.app.QWIPApplication
import com.qwipvpn.app.data.SecureVault
import kotlinx.coroutines.*

/** Opt-in, visible foreground monitoring. System Always-on takes priority over app rules. */
class AutoConnectionController(private val service: QWIPVpnService,private val state:()->TunnelState,
    private val stop:(String,Boolean)->Unit,private val idle:()->Unit) {
    private val app get()=service.application as QWIPApplication
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val handler=Handler(Looper.getMainLooper())
    private val manager=service.getSystemService(ConnectivityManager::class.java)
    @Volatile var enabled=false; private set
    private var registered=false
    private var systemStart=false
    private var job:Job?=null
    private var networkIdentity:String?=null
    @Volatile private var evaluationGeneration=0
    private val callback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network)=evaluate(false)
        override fun onLost(network:Network)=evaluate(false)
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities)=evaluate(false)
        override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties)=evaluate(false)
    }
    fun start(system:Boolean=false){
        systemStart=systemStart||system
        job?.cancel()
        job=scope.launch {
            val snapshot=app.repository.diskSnapshot();val paused=app.telemetry.read().autoPaused
            val alwaysOn=systemStart || Build.VERSION.SDK_INT>=29&&service.isAlwaysOn
            if(!app.vpnDisclosureAccepted||snapshot.profiles.isEmpty()||(!alwaysOn&&(!snapshot.settings.autoConnect||paused))){enabled=false;handler.post{idle()};return@launch}
            enabled=true
            synchronized(this@AutoConnectionController){if(!registered){manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);registered=true}}
            evaluate()
        }
    }
    @Synchronized private fun evaluate(force:Boolean=true){
        if(!enabled)return
        val identity=manager.allNetworks.mapNotNull{n->manager.getNetworkCapabilities(n)?.takeIf{it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}?.let{cap->
            val link=manager.getLinkProperties(n);"$n:${cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}:${cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}:${link?.dnsServers}:${link?.domains}"
        }}.sorted().joinToString("|")
        if(!force&&identity==networkIdentity)return
        networkIdentity=identity
        val generation=++evaluationGeneration
        // Each evaluation owns its own generation: a late preparation cannot reconnect after manual stop.
        job?.cancel()
        job=scope.launch {
            val snapshot=app.repository.diskSnapshot();val paused=app.telemetry.read().autoPaused
            val alwaysOn=systemStart || Build.VERSION.SDK_INT>=29&&service.isAlwaysOn
            if(!alwaysOn&&(!snapshot.settings.autoConnect||paused))return@launch
            val network=manager.allNetworks.filter{n->manager.getNetworkCapabilities(n)?.let{it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
                .sortedByDescending{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}.firstOrNull()
            val action=if(network==null)"disconnect" else if(alwaysOn)"connect" else {
                val capabilities=manager.getNetworkCapabilities(network)
                val type=if(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)"wifi" else if(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true)"cellular" else "any"
                val link=manager.getLinkProperties(network);val servers=link?.dnsServers?.mapNotNull{it.hostAddress}.orEmpty();val domains=link?.domains?.split(' ')?.filter(String::isNotBlank).orEmpty()
                val ssid=if(type=="wifi"&&service.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)runCatching{@Suppress("DEPRECATION") service.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ssid?.trim('"')?.takeUnless{it==WifiManager.UNKNOWN_SSID}}.getOrNull()else null
                if(snapshot.settings.onDemandRules.isEmpty())"connect" else snapshot.settings.onDemandRules.firstOrNull{it.matches(type,servers,domains,ssid)}?.action?:"ignore"
            }
            if(action=="disconnect"&&state().phase in listOf(TunnelPhase.CONNECTED,TunnelPhase.CONNECTING))handler.post{if(enabled&&generation==evaluationGeneration)stop("network",true)}
            if(action=="connect"&&state().phase in listOf(TunnelPhase.DISCONNECTED,TunnelPhase.ERROR)){
                val prepared=try{app.launcher.prepare(false)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){return@launch}
                ensureActive()
                handler.post{
                    if(enabled&&generation==evaluationGeneration&&state().phase in listOf(TunnelPhase.DISCONNECTED,TunnelPhase.ERROR))service.startService(Intent(service,QWIPVpnService::class.java).setAction(TunnelProtocol.START).putExtra(TunnelProtocol.REQUEST_ID,prepared.requestId))
                    else scope.launch{SecureVault(service).delete("tunnel-${prepared.requestId}")}
                }
            }
        }
    }
    fun retry(){if(enabled)scope.launch{delay(30_000);evaluate()}}
    fun pause(){enabled=false;systemStart=false;++evaluationGeneration;job?.cancel();synchronized(this){if(registered){runCatching{manager.unregisterNetworkCallback(callback)};registered=false}};scope.launch{runCatching{app.telemetry.update{it.copy(autoPaused=true)}}}}
    fun close(){enabled=false;++evaluationGeneration;job?.cancel();if(registered)runCatching{manager.unregisterNetworkCallback(callback)};scope.cancel()}
}
