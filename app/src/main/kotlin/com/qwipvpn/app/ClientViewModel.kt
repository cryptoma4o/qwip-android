package com.qwipvpn.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwipvpn.core.*
import com.qwipvpn.app.engine.XrayEngine
import com.qwipvpn.app.tunnel.TunnelController
import com.qwipvpn.app.qr.QrImages
import com.qwipvpn.app.support.SupportDiagnostics
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.content.Intent
import com.qwipvpn.app.tunnel.QWIPVpnService
import com.qwipvpn.app.tunnel.TunnelProtocol
import com.qwipvpn.app.data.SubscriptionJobs
import com.qwipvpn.app.surfaces.SurfaceUpdates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Credentials stay in memory, never in Activity saved-state bundles. */
data class ServerEditorDraft(val profile: Profile?, val initial: ServerForm, val form: ServerForm = initial,
    val attempted: Boolean = false, val advanced: Boolean = false)

class ClientViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as QWIPApplication
    val repository = app.repository
    val store = repository.store
    private val tunnel = TunnelController(application).apply { bind() }
    val connection = tunnel.state
    val busy = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)
    val importOpen = MutableStateFlow(false)
    val importDraft = MutableStateFlow("")
    val serverEditor = MutableStateFlow<ServerEditorDraft?>(null)
    val requestedTab = MutableStateFlow<Int?>(null)
    val engineVersion = MutableStateFlow(app.getString(R.string.loading))
    val probes = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    val probing = MutableStateFlow(false)
    val preparing = MutableStateFlow(false)
    val externalIp = MutableStateFlow<String?>(null)
    val checkingIp = MutableStateFlow(false)
    val speedRunning = MutableStateFlow(false)
    val speedProgress = MutableStateFlow(0.0)
    val telemetry = flow { while (true) { emit(withContext(Dispatchers.IO) { runCatching { app.telemetry.read() }.getOrDefault(TelemetryState()) }); delay(1000) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TelemetryState())
    private val transfer = TransferChecks()
    private var probeJob: Job? = null
    @Volatile private var probeSequence=0L
    private var speedJob: Job? = null
    private val networks = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        private fun changed() { app.probes.invalidate(); viewModelScope.launch { cancelProbes(); speedJob?.cancel() } }
    }
    private var operation: Job? = null
    init {
        networks.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        viewModelScope.launch {
            var last:Pair<TunnelPhase,String?>?=null
            connection.collect { state ->
                val key=state.phase to state.profileId
                if(last!=null&&last!=key){speedJob?.cancel();cancelProbes();app.probes.invalidate()};last=key
                if (state.phase == TunnelPhase.ERROR) state.profileId?.let{app.recentFailures[it]=System.currentTimeMillis()}
            }
        }
        viewModelScope.launch{
            store.map{snapshot->snapshot.profiles.map{it.id to it.configuration} to listOf(snapshot.settings.probeMethod,snapshot.settings.probeUrl,snapshot.settings.probeTimeout.toString(),snapshot.settings.probeDouble.toString())}
                .distinctUntilChanged().drop(1).collect{cancelProbes();app.probes.invalidate()}
        }
        viewModelScope.launch {
            try {
                repository.load()
                if (app.geoStore.active(store.value.geoGeneration) == null) {
                    val generation = app.geoStore.prepare()
                    repository.update { it.copy(geoGeneration = generation) }
                }
                engineVersion.value = withContext(Dispatchers.IO) { XrayEngine.version() }
                ready.value = true
                app.diagnostics.record(SupportEventKind.APP_READY)
            } catch (error: Exception) { app.diagnostics.record(SupportEventKind.APP_PREPARATION_FAILED,issue=SupportDiagnostics.issue(error)); message.value = app.getString(R.string.app_preparation_failed) }
        }
    }
    fun action(failure: String = app.getString(R.string.storage_failed), success: String? = null, work: suspend () -> Unit) {
        if (busy.value) return
        lastError.value = null
        busy.value = true
        operation = viewModelScope.launch {
            try { work(); if (success != null) message.value = success }
            catch (_: CancellationException) { message.value = app.getString(R.string.action_cancelled) }
            catch (_: Exception) { lastError.value = failure; message.value = failure }
            finally { busy.value = false }
        }
    }
    fun cancel() { operation?.cancel() }
    fun openImport() { lastError.value=null;importOpen.value=true }
    fun closeImport(clear: Boolean=false) { importOpen.value=false;if(clear)importDraft.value="" }
    fun editServer(profile: Profile? = null) {
        lastError.value = null
        serverEditor.value = ServerEditorDraft(profile, profile?.let(ServerForm::from) ?: ServerForm())
    }
    fun closeServerEditor() { serverEditor.value = null }
    fun cameraDenied() { importOpen.value=true;lastError.value=app.getString(R.string.camera_permission) }
    fun acceptQr(raw: String) {
        importOpen.value=true
        action(app.getString(R.string.qr_invalid)) {
            importDraft.value=withContext(Dispatchers.Default) { QrPayload.normalize(raw) }
        }
    }
    fun readQrImage(uri: android.net.Uri) {
        importOpen.value=true
        action(app.getString(R.string.qr_not_found)) {
            importDraft.value=withContext(Dispatchers.IO) { QrPayload.normalize(QrImages.decode(app,uri)) }
        }
    }
    fun import(text: String, done: () -> Unit) = action(app.getString(R.string.import_failed),app.getString(R.string.configs_added)) { repository.import(text); done() }
    fun refresh() = action(app.getString(R.string.network_failed),app.getString(R.string.locations_updated)) { repository.refreshAll() }
    fun select(id: String) = action { repository.update { it.copy(selected = id) } }
    fun favorite(id: String) = action { repository.update { it.copy(profiles = it.profiles.map { p -> if (p.id == id) p.copy(favorite = !p.favorite) else p }) } }
    fun settings(value: ClientSettings) = action(success=app.getString(R.string.settings_saved)) {
        val old=store.value.settings
        repository.update { it.copy(settings = value) }
        SubscriptionJobs.schedule(app,store.value)
        if(value.autoConnect&&!old.autoConnect)withContext(Dispatchers.IO){app.telemetry.update{it.copy(autoPaused=false)}}
        if((old.autoConnect!=value.autoConnect||old.onDemandRules!=value.onDemandRules)&&VpnService.prepare(app)==null){
            if(value.autoConnect)app.startForegroundService(Intent(app,QWIPVpnService::class.java).setAction(TunnelProtocol.AUTO))
            else app.startService(Intent(app,QWIPVpnService::class.java).setAction(TunnelProtocol.AUTO))
        }
    }
    fun opened() {
        if (!ready.value) return
        viewModelScope.launch(Dispatchers.IO){runCatching{SubscriptionJobs.remind(app,store.value)}}
        if (store.value.settings.refreshOnOpen) viewModelScope.launch { runCatching { repository.refreshDue() } }
        if (store.value.settings.probeOnOpen) probeServers()
    }
    fun probeServers(ids: List<String>? = null, force: Boolean = true) {
        probeJob?.cancel()
        val request=++probeSequence
        val snapshot=store.value
        val profiles=if(ids==null)snapshot.profiles else snapshot.profiles.filter{it.id in ids}
        val requestedIds=profiles.map{it.id}.toSet()
        probes.update{it-requestedIds}
        probeJob = viewModelScope.launch {
            probing.value = true
            try {
                withContext(Dispatchers.IO) { app.probes.check(profiles, snapshot.settings, force) { batch -> if(request==probeSequence)probes.update { it + batch.associateBy(ProbeResult::profileId) } } }
            } catch (_: CancellationException) { } catch (_: Exception) {
                if(request==probeSequence){
                    probes.update{it+profiles.associate{p->p.id to ProbeResult(p.id,"unavailable",method=snapshot.settings.probeMethod)}}
                    message.value = app.getString(R.string.network_failed)
                }
            }
            finally { if(request==probeSequence)probing.value = false }
        }
    }
    private fun cancelProbes(){++probeSequence;probeJob?.cancel();probing.value=false;probes.value=emptyMap()}
    fun checkIp() {
        if (checkingIp.value) return
        viewModelScope.launch { checkingIp.value = true; try { externalIp.value = withTimeout(15_000) { transfer.externalIp(store.value.settings.ipCheckUrl) } }
            catch (_: CancellationException) { } catch (_: Exception) { message.value = app.getString(R.string.network_test_failed) } finally { checkingIp.value = false } }
    }
    fun testSpeed() {
        if (speedRunning.value) { speedJob?.cancel(); return }
        speedJob = viewModelScope.launch {
            speedRunning.value = true; speedProgress.value = 0.0
            try {
                val snapshot = store.value
                val actual=connection.value
                val result = withTimeout(20_000) { transfer.speed(snapshot.settings.speedTestUrl) { rate, _ -> speedProgress.value = rate } }
                    .copy(serverName = actual.title.takeIf{actual.phase==TunnelPhase.CONNECTED})
                withContext(Dispatchers.IO) { app.telemetry.speed(result) }
            } catch (_: CancellationException) { } catch (_: Exception) { message.value = app.getString(R.string.network_test_failed) }
            finally { speedRunning.value = false }
        }
    }
    fun source(source: Source) = action { repository.saveSource(source);SubscriptionJobs.schedule(app,store.value) }
    fun refreshSource(source: Source) = action(app.getString(R.string.network_failed)) { repository.refresh(source) }
    fun saveServer(form: ServerForm, editing: Profile?, done: () -> Unit) = action(app.getString(R.string.server_validation_failed), app.getString(R.string.config_saved)) { repository.saveServer(form, editing); done() }
    fun saveRouting(profile: RoutingProfile, done: () -> Unit) = action(app.getString(R.string.storage_failed), app.getString(R.string.settings_saved)) { repository.saveRouting(profile); done() }
    private fun editGroups(edit:(ClientStore)->ClientStore){viewModelScope.launch{try{repository.update(edit)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){message.value=app.getString(R.string.storage_failed)}}}
    fun collapse(group: String) = editGroups { it.copy(collapsedGroups = if (group in it.collapsedGroups) it.collapsedGroups - group else it.collapsedGroups + group) }
    fun collapseAll(ids: Set<String>) = editGroups { it.copy(collapsedGroups = if (ids.all(it.collapsedGroups::contains)) emptySet() else ids) }
    fun connect() {
        if (!ready.value || connection.value.phase in listOf(TunnelPhase.CONNECTING,TunnelPhase.CONNECTED,TunnelPhase.DISCONNECTING)) return
        val snapshot = store.value
        val profile = snapshot.profiles.firstOrNull { it.id == snapshot.selected } ?: return
        action(app.getString(R.string.connect_failed)) {
            preparing.value = true
            app.diagnostics.record(SupportEventKind.CONNECT_REQUESTED,profileNumber=snapshot.profiles.indexOf(profile)+1,network=app.diagnostics.network())
            try {
                val prepared = app.launcher.prepare { batch -> probes.update { it + batch.associateBy(ProbeResult::profileId) } }
                tunnel.start(prepared.requestId)
            } catch(error: Exception) { app.diagnostics.record(SupportEventKind.CONNECT_PREPARATION_FAILED,issue=SupportDiagnostics.issue(error)); throw error }
            finally { preparing.value = false }
        }
    }
    fun disconnect() { if (preparing.value) operation?.cancel(); if (connection.value.phase in listOf(TunnelPhase.CONNECTING, TunnelPhase.CONNECTED, TunnelPhase.DISCONNECTING)) tunnel.stop() }
    fun reconnect() {
        if (!ready.value || busy.value || connection.value.phase != TunnelPhase.CONNECTED) return
        action(app.getString(R.string.connect_failed)) {
            preparing.value = true
            var pendingRequest:String?=null
            try {
                // Prepare first: a preparation failure must leave the existing session running.
                val prepared = app.launcher.prepare(allowSmartSelection = false)
                pendingRequest=prepared.requestId
                tunnel.stop()
                withTimeout(8_000) { connection.first { it.phase == TunnelPhase.DISCONNECTED } }
                withContext(Dispatchers.IO) { app.telemetry.update { it.copy(autoPaused = false) } }
                tunnel.start(prepared.requestId)
                pendingRequest=null
            } finally {
                pendingRequest?.let{id->withContext(NonCancellable){repository.discardTunnelRequest(id)}}
                preparing.value = false
            }
        }
    }
    fun disconnectFromSurface(){if(preparing.value)operation?.cancel();tunnel.stop()}
    fun reset(mode:String)=action{
        if(mode=="everything"){
            disconnectFromSurface()
            withTimeout(6000){connection.first{it.phase in listOf(TunnelPhase.DISCONNECTED,TunnelPhase.ERROR)}}
        }
        repository.reset(mode)
        if(mode=="everything"){
            importDraft.value="";serverEditor.value=null;externalIp.value=null;probes.value=emptyMap()
            val generation=app.geoStore.resetToBundled();repository.update{it.copy(geoGeneration=generation)}
            SurfaceUpdates.request(app)
        }
    }
    fun backgrounded(){probeJob?.cancel();speedJob?.cancel()}
    fun geo(download: Boolean, urls: List<String>? = null) = action(app.getString(R.string.rules_download_failed),app.getString(R.string.routing_rules_ready)) {
        val generation = app.geoStore.prepare(download,urls,if(download)store.value.geoSources else emptyList())
        repository.update { it.copy(geoGeneration = generation,geoSources=if(download)it.geoSources else emptyList()) }
        ready.value = true
    }
    fun geoExtras(sources:List<GeoSource>)=action(app.getString(R.string.rules_download_failed),app.getString(R.string.routing_rules_ready)){
        val generation=app.geoStore.prepareExtras(store.value.geoGeneration,sources)
        repository.update{it.copy(geoGeneration=generation,geoSources=sources)}
    }
    override fun onCleared() { runCatching { networks.unregisterNetworkCallback(networkCallback) }; tunnel.close(); super.onCleared() }
}
