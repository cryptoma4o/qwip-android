package com.qwipvpn.app

import android.app.Application
import com.qwipvpn.app.data.ClientRepository
import com.qwipvpn.app.data.GeoStore
import com.qwipvpn.app.support.SupportDiagnostics
import com.qwipvpn.core.SupportEventKind
import com.qwipvpn.core.ServerProbes
import com.qwipvpn.core.ProxyProbe
import com.qwipvpn.core.Profile
import com.qwipvpn.app.data.TelemetryStore
import com.qwipvpn.app.data.IdentityStore
import com.qwipvpn.app.engine.XrayEngine
import com.qwipvpn.app.engine.ProbeNetwork
import java.util.concurrent.ConcurrentHashMap
import com.qwipvpn.app.data.SubscriptionJobs
import kotlinx.coroutines.*

class QWIPApplication : Application() {
    val repository by lazy { ClientRepository(this) }
    val geoStore by lazy { GeoStore(this) }
    val diagnostics by lazy { SupportDiagnostics(this) }
    val telemetry by lazy { TelemetryStore(this) }
    val identities by lazy { IdentityStore(this) }
    private val probeNetwork by lazy{ProbeNetwork(this)}
    val probes by lazy { ServerProbes(ProxyProbe { profiles, timeout, url -> measureProxy(profiles, timeout, url) },tcp=probeNetwork::tcp) }
    suspend fun measureProxy(profiles:List<Profile>,timeout:Int,url:String)=XrayEngine.probeAsync(profiles,timeout,url,probeNetwork)
    val recentFailures = ConcurrentHashMap<String, Long>()
    val launcher by lazy { ConnectionLauncher(this) }
    // The VPN service runs in a second process. This local marker is shared by both.
    private val disclosureFile get() = java.io.File(noBackupFilesDir, "vpn-disclosure-v1")
    val vpnDisclosureAccepted get() = disclosureFile.isFile
    fun acceptVpnDisclosure() {
        disclosureFile.createNewFile()
    }
    private val applicationScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        diagnostics.record(SupportEventKind.PROCESS_STARTED,network=diagnostics.network())
        diagnostics.observeNetwork()
        if(Application.getProcessName()==packageName)applicationScope.launch{runCatching{repository.load();SubscriptionJobs.schedule(this@QWIPApplication,repository.store.value)}}
    }
}
