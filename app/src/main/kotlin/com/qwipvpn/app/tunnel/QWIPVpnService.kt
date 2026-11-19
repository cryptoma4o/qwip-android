package com.qwipvpn.app.tunnel

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import com.qwipvpn.core.*
import com.qwipvpn.app.MainActivity
import com.qwipvpn.app.QWIPApplication
import com.qwipvpn.app.support.SupportDiagnostics
import com.qwipvpn.app.R
import com.qwipvpn.app.data.SecureVault
import com.qwipvpn.app.data.TunnelRequest
import com.qwipvpn.app.surfaces.SurfaceUpdates
import com.qwipvpn.app.engine.XrayEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import libXray.DialerController
import libXray.LibXray

/** Core lifecycle is isolated from the UI in the :tunnel process. */
class QWIPVpnService : VpnService() {
    private val handler = Handler(Looper.getMainLooper())
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it,"QWIP-Core") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineThread)
    private val surfaceScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val stateLock=Any()
    private val clients = mutableListOf<Messenger>()
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile private var state = TunnelState()
    @Volatile private var sequence = 0
    @Volatile private var diagnosticStage: SupportStage? = null
    @Volatile private var attemptStarted: Long = 0
    private val app get() = application as QWIPApplication
    private var activeRequest: TunnelRequest? = null
    private var metricsAccess: MetricsAccess? = null
    private var inboundTag = ""
    private var sessionId: String? = null
    private var previous = ByteTotals()
    private var sampleAt = 0L
    private var trafficJob: Job? = null
    private val automatic by lazy { AutoConnectionController(this,{state},::stopTunnel) {
        if(state.phase !in listOf(TunnelPhase.CONNECTED,TunnelPhase.CONNECTING)) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    } }
    private val diagnostics get() = (application as QWIPApplication).diagnostics
    private fun step(value: SupportStage) {
        diagnosticStage=value
        diagnostics.record(SupportEventKind.CONNECT_STAGE,stage=value,elapsedMillis=SystemClock.elapsedRealtime()-attemptStarted)
    }
    private val controller = object : DialerController {
        override fun protectFd(fd: Long): Boolean = fd in 0..Int.MAX_VALUE.toLong() && protect(fd.toInt())
    }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                TunnelProtocol.REGISTER -> message.replyTo?.let { clients.removeAll { client -> client.binder == it.binder }; clients += it; send(it) }
                TunnelProtocol.UNREGISTER -> clients.removeAll { it.binder == message.replyTo?.binder }
                TunnelProtocol.DISCONNECT -> stopTunnel()
            }
        }
    })
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else messenger.binder
    override fun onCreate() {
        super.onCreate()
        diagnostics.record(SupportEventKind.SERVICE_CREATED)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.vpn_channel),NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TunnelProtocol.STOP) { stopTunnel(); return START_NOT_STICKY }
        if (intent == null || intent.action in listOf(TunnelProtocol.AUTO,SERVICE_INTERFACE)) {
            if(prepare(this)!=null){stopSelf();return START_NOT_STICKY}
            try {
                if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification(getString(R.string.monitoring_network)),ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                else startForeground(NOTIFICATION,notification(getString(R.string.monitoring_network)))
                automatic.start(intent==null||intent.action==SERVICE_INTERFACE)
            }catch(_:Exception){stopSelf()}
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(TunnelProtocol.REQUEST_ID)
        if (intent?.action != TunnelProtocol.START || id == null || runCatching { UUID.fromString(id) }.isFailure) { stopSelf(); return START_NOT_STICKY }
        if (state.phase in listOf(TunnelPhase.CONNECTING,TunnelPhase.CONNECTED,TunnelPhase.DISCONNECTING)) {
            scope.launch{runCatching{SecureVault(this@QWIPVpnService).delete("tunnel-$id")}};return START_NOT_STICKY
        }
        attemptStarted=SystemClock.elapsedRealtime()
        step(SupportStage.FOREGROUND)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION,notification(getString(R.string.connecting)),ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(NOTIFICATION,notification(getString(R.string.connecting)))
        } catch (error: Exception) {
            diagnostics.record(SupportEventKind.CONNECT_FAILED,stage=diagnosticStage,issue=SupportDiagnostics.issue(error),network=diagnostics.network())
            publish(TunnelState(TunnelPhase.ERROR,error=getString(R.string.connect_failed))); stopSelf(); return START_NOT_STICKY
        }
        val generation = synchronized(stateLock){++sequence;publish(TunnelState(TunnelPhase.CONNECTING));sequence}
        watchdog(generation,30_000)
        scope.launch {
            try {
                step(SupportStage.CONSENT)
                check(prepare(this@QWIPVpnService) == null)
                step(SupportStage.REQUEST)
                val vault = SecureVault(this@QWIPVpnService)
                val raw = vault.read("tunnel-$id") ?: error("Missing request")
                vault.delete("tunnel-$id")
                val request = Json.decodeFromString<TunnelRequest>(raw)
                activeRequest=request
                synchronized(stateLock){if(generation==sequence)publish(TunnelState(TunnelPhase.CONNECTING,request.profile.id,request.profile.title))}
                step(SupportStage.ASSETS)
                val geoRoot = File(noBackupFilesDir,"geo").canonicalFile
                val assets = File(request.assets).canonicalFile
                require(assets.parentFile == geoRoot && File(assets,"ready").isFile)
                if (generation != sequence) return@launch
                // An unpinned underlying network lets Android follow Wi-Fi/cellular changes.
                // Bootstrap DNS sockets are protected and use an explicit IP resolver.
                val dns = request.settings.dns.firstOrNull() ?: "1.1.1.1"
                require(RuntimeConfiguration.isIpLiteral(dns))
                step(SupportStage.DNS)
                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)
                LibXray.setDNS(controller,if (dns.contains(':')) "[$dns]:53" else "$dns:53")
                val builder = Builder().setSession("QWIP").setMtu(request.settings.mtu)
                    .addAddress("198.18.0.2",30).addAddress("fc00::2",64).addRoute(RuntimeConfiguration.RESOLVER,32)
                    .addDnsServer(RuntimeConfiguration.RESOLVER)
                    .setConfigureIntent(PendingIntent.getActivity(this@QWIPVpnService,0,Intent(this@QWIPVpnService,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                // Core duplicates the fd; the original stays owned by this service.
                Cidr.routes(request.settings.includedRoutes,request.settings.excludedRoutes).forEach{route->val parts=route.toString().split('/');builder.addRoute(parts[0],parts[1].toInt())}
                if (request.routing?.let { it.applies(request.profile) && it.fakeDns } == true) builder.addRoute("198.18.0.0",15)
                val appPackages = AppSplit.clean(request.settings.appPackages)
                require(AppSplit.isValid(request.settings.appMode,appPackages))
                if (request.settings.appMode != AppSplit.ALL) {
                    val applied = appPackages.count { name -> runCatching { if (request.settings.appMode == AppSplit.ONLY) builder.addAllowedApplication(name) else builder.addDisallowedApplication(name) }.isSuccess }
                    require(request.settings.appMode != AppSplit.ONLY || applied > 0)
                }
                step(SupportStage.TUN)
                val tun = builder.establish() ?: error("TUN denied")
                descriptor = tun
                step(SupportStage.CONFIGURATION)
                val access=MetricsAccess(XrayEngine.freePort(),"qwip",UUID.randomUUID().toString()+UUID.randomUUID().toString());metricsAccess=access
                val config = RuntimeConfiguration.build(request.profile,request.settings,tun.fd,assets.path,request.routing,access)
                inboundTag=Json.parseToJsonElement(config).jsonObject.getValue("inbounds").jsonArray.first{it.jsonObject["protocol"]?.jsonPrimitive?.content=="tun"}.jsonObject.getValue("tag").jsonPrimitive.content
                step(SupportStage.CORE_START)
                XrayEngine.run(config)
                step(SupportStage.CORE_RUNNING)
                check(XrayEngine.running())
                if (generation != sequence) { shutdownCore(); return@launch }
                sessionId=UUID.randomUUID().toString();previous=ByteTotals();sampleAt=SystemClock.elapsedRealtime()
                runCatching{app.telemetry.begin(sessionId!!,request.profile)}
                val accepted=synchronized(stateLock){if(generation==sequence){publish(TunnelState(TunnelPhase.CONNECTED,request.profile.id,request.profile.title,SystemClock.elapsedRealtime()));true}else false}
                if(!accepted){shutdownCore("user");return@launch}
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(getString(R.string.connected_location,request.profile.title)))
                trafficJob=scope.launch {
                    var tick=0
                    while(isActive&&generation==sequence){
                        delay(1000);sample(generation);tick++
                        if(tick%5==0&&!runCatching{XrayEngine.running()}.getOrDefault(false)){handler.post{stopTunnel("error",automatic.enabled)};return@launch}
                        if(tick%30==0)SurfaceUpdates.request(this@QWIPVpnService)
                    }
                }
                if(request.settings.autoConnect)handler.post{automatic.start()}
            } catch (error: Exception) {
                diagnostics.record(SupportEventKind.CONNECT_FAILED,stage=diagnosticStage,issue=SupportDiagnostics.issue(error),
                    network=diagnostics.network(),elapsedMillis=SystemClock.elapsedRealtime()-attemptStarted)
                if(generation==sequence)runCatching{app.telemetry.fail(activeRequest?.profile,diagnosticStage?.name?.lowercase()?:"configuration")}
                val failedProfile=activeRequest?.profile
                shutdownCore("error")
                if (generation == sequence) {
                    publish(TunnelState(TunnelPhase.ERROR,failedProfile?.id,failedProfile?.title.orEmpty(),error=getString(R.string.connect_failed)))
                    handler.post { if(automatic.enabled){getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(getString(R.string.monitoring_network)));automatic.retry()}else{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()} }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun stopTunnel(reason:String="user",keepMonitoring:Boolean=false) {
        if(!keepMonitoring)automatic.pause()
        if (state.phase == TunnelPhase.DISCONNECTING) return
        val generation = synchronized(stateLock){++sequence;publish(state.copy(phase = TunnelPhase.DISCONNECTING,error = null));sequence}
        watchdog(generation,2_000)
        scope.launch {
            shutdownCore(reason)
            publish(TunnelState())
            handler.post { if(automatic.enabled){getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(getString(R.string.monitoring_network)));if(reason=="error")automatic.retry()} else {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()} }
        }
    }
    private suspend fun sample(generation:Int,final:Boolean=false) {
        val access=metricsAccess?:return;val request=activeRequest?:return;val id=sessionId?:return
        val totals=withContext(Dispatchers.IO){runCatching{TrafficMonitor.read(access,inboundTag)}.getOrNull()}?:return
        currentCoroutineContext().ensureActive();if(!final&&generation!=sequence)return
        val now=SystemClock.elapsedRealtime();val delta=totals.delta(previous);val span=(now-sampleAt).coerceAtLeast(1)
        previous=totals;sampleAt=now
        runCatching{app.telemetry.update{it.recording(id,request.profile,java.time.LocalDate.now().toString(),delta,totals)}}
        synchronized(stateLock){if(final||generation==sequence)publish(state.copy(sent=totals.sent,received=totals.received,sendRate=(delta.sent*1000.0/span).toLong(),receiveRate=(delta.received*1000.0/span).toLong()),false)}
    }
    private suspend fun shutdownCore(reason:String="system") {
        trafficJob?.cancel();trafficJob=null
        runCatching{sample(sequence,true)}
        sessionId?.let{id->runCatching{app.telemetry.finish(id,reason,previous)}};sessionId=null
        runCatching { XrayEngine.stop() }.onFailure { diagnostics.record(SupportEventKind.CORE_STOP_FAILED) }
        runCatching { LibXray.resetDNS() }
        runCatching { descriptor?.close() }; descriptor = null
        metricsAccess=null;activeRequest=null
    }
    private fun watchdog(generation: Int, delay: Long) {
        handler.postDelayed({
            if (sequence == generation && state.phase in listOf(TunnelPhase.CONNECTING,TunnelPhase.DISCONNECTING)) {
                diagnostics.record(if(state.phase==TunnelPhase.CONNECTING)SupportEventKind.CONNECT_TIMEOUT else SupportEventKind.STOP_TIMEOUT,
                    phase=state.phase,stage=diagnosticStage,issue=SupportIssue.TIMEOUT,network=diagnostics.network(),
                    elapsedMillis=SystemClock.elapsedRealtime()-attemptStarted)
                if (state.phase == TunnelPhase.CONNECTING) {
                    publish(TunnelState(TunnelPhase.ERROR,error=getString(R.string.connect_timeout)))
                }
                runCatching { descriptor?.close() }; descriptor = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                // Terminate only our isolated native process if a blocking native call cannot stop.
                Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) },150)
            }
        },delay)
    }
    private fun publish(value: TunnelState,statusEvent:Boolean=true) {
        val old=state
        state = value
        if(statusEvent&&old.phase!=value.phase)diagnostics.record(SupportEventKind.TUNNEL_STATE,phase=value.phase,network=diagnostics.network())
        surfaceScope.launch {
            runCatching{app.telemetry.update{record->val current=state;record.copy(surface=record.surface.copy(phase=current.phase,serverName=current.title,profileId=current.profileId,startedAt=current.startedAt,updatedAt=System.currentTimeMillis(),totals=ByteTotals(current.sent,current.received),hasProfiles=activeRequest!=null||record.surface.hasProfiles))}}
            if(old.phase!=value.phase)SurfaceUpdates.request(this@QWIPVpnService)
        }
        handler.post { val iterator = clients.iterator(); while (iterator.hasNext()) if (!send(iterator.next())) iterator.remove() }
    }
    private fun send(client: Messenger): Boolean = runCatching { client.send(Message.obtain(null,TunnelProtocol.STATE).apply { data = TunnelProtocol.encode(state) }); true }.getOrDefault(false)
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this,1,Intent(this,QWIPVpnService::class.java).setAction(TunnelProtocol.STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_vpn_notification).setContentTitle("QWIP VPN").setContentText(getString(R.string.vpn_channel)).build()
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle("QWIP VPN").setContentText(text).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setWhen(if(state.startedAt>0)System.currentTimeMillis()-(SystemClock.elapsedRealtime()-state.startedAt)else System.currentTimeMillis())
            .setUsesChronometer(state.phase==TunnelPhase.CONNECTED).setShowWhen(state.phase==TunnelPhase.CONNECTED)
            .setCategory(Notification.CATEGORY_SERVICE).addAction(Notification.Action.Builder(null,getString(R.string.disconnect),stop).build()).build()
    }
    override fun onRevoke() { diagnostics.record(SupportEventKind.VPN_REVOKED); stopTunnel("system",false); super.onRevoke() }
    override fun onDestroy() {
        diagnostics.record(SupportEventKind.SERVICE_DESTROYED,phase=state.phase)
        ++sequence
        automatic.close()
        handler.removeCallbacksAndMessages(null)
        scope.launch { shutdownCore();publish(TunnelState());surfaceScope.coroutineContext[Job]?.children?.toList()?.joinAll();surfaceScope.cancel();scope.cancel();engineThread.close() }
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "qwip-vpn"; private const val NOTIFICATION = 17 }
}
