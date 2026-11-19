package com.qwipvpn.app.data

import android.content.Context
import com.qwipvpn.core.*
import com.qwipvpn.app.engine.XrayEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID

class ClientRepository(context: Context,private val http:SubscriptionHttp=SubscriptionHttp()) {
    private val appContext=context.applicationContext
    private val refreshLocks=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private val vault = SecureVault(context)
    private val mutex = Mutex()
    private var loaded = false
    @Volatile private var epoch=0
    private val _store = MutableStateFlow(ClientStore())
    val store = _store.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val importer = SubscriptionImporter { XrayEngine.convertLink(it) }
    private val identities = IdentityStore(context)
    private val telemetry = TelemetryStore(context)
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { if (!loaded) { vault.read("client")?.let { _store.value = json.decodeFromString<ClientStore>(it) }; loaded = true; val s=_store.value;runCatching { telemetry.preferences(s.settings,s.profiles.isNotEmpty(),s.profiles.firstOrNull{it.id==s.selected}) } } }
    }
    suspend fun diskSnapshot(): ClientStore = withContext(Dispatchers.IO) { vault.read("client")?.let { json.decodeFromString<ClientStore>(it) } ?: ClientStore() }
    suspend fun update(edit: (ClientStore) -> ClientStore) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded) { "Storage has not loaded" }
            currentCoroutineContext().ensureActive()
            val new = edit(_store.value)
            require(SettingsValidation.valid(new.settings))
            require(new.geoSources.size <= 12 && new.geoSources.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == new.geoSources.size)
            new.geoSources.forEach(GeoSource::validate)
            val encoded = json.encodeToString(new)
            currentCoroutineContext().ensureActive()
            vault.write("client",encoded)
            val previous=_store.value
            _store.value = new
            runCatching { telemetry.preferences(new.settings,new.profiles.isNotEmpty(),new.profiles.firstOrNull{it.id==new.selected}) }
            if(previous.selected!=new.selected||previous.profiles!=new.profiles)runCatching{com.qwipvpn.app.surfaces.SurfaceUpdates.request(appContext)}
            if(previous.sources.map{it.id to it.autoUpdate}!=new.sources.map{it.id to it.autoUpdate}||previous.settings.expiryReminders!=new.settings.expiryReminders)SubscriptionJobs.schedule(appContext,new)
        }
    }
    suspend fun import(text: String) = withContext(Dispatchers.IO) {
        val input = text.trim()
        if (input.startsWith("qwip://routing/") || input.startsWith("happ://routing/") || input.startsWith("notvpn://routing/")) {
            val command = RoutingLinks.parse(input)
            update { old -> if (command.disable) old.copy(activeRoutingId = null) else {
                val profile = requireNotNull(command.profile)
                old.copy(routingProfiles = (old.routingProfiles + profile).takeLast(100), activeRoutingId = if (command.activate) profile.id else old.activeRoutingId)
            } }; return@withContext
        }
        if (input.startsWith("https://",true) && !input.contains('\n')) {
            val feed = SubscriptionHttp.importUrl(input)
            val candidate = Source(url = feed)
            val request = SubscriptionHttp.requestUrl(candidate)
            val existing = store.value.sources.firstOrNull {
                it.url == feed || runCatching { SubscriptionHttp.requestUrl(it) }.getOrNull() == request
            }
            refresh(existing ?: candidate)
        } else {
            val job = currentCoroutineContext()
            val decoded = XrayEngine.decodeSubscription(input, identities.age()?.secretKey)
            val profiles = importer.parse(decoded,checkCanceled={job.ensureActive()})
            currentCoroutineContext().ensureActive()
            update { old ->
                val new = profiles.filter { incoming -> old.profiles.none { it.originKey == incoming.originKey && it.sourceId == null } }
                require(new.isNotEmpty())
                old.copy(profiles = old.profiles + new, selected = old.selected ?: new.first().id)
            }
        }
    }
    suspend fun refresh(source: Source) = withContext(Dispatchers.IO) {
        val known=store.value.sources.any{it.id==source.id}
        refreshLocks.getOrPut(source.id){Mutex()}.withLock{
            val current=store.value.sources.firstOrNull{it.id==source.id}
            if(known&&current==null)return@withLock
            refreshLocked(current?:source)
        }
    }
    private suspend fun refreshLocked(source: Source) = withContext(Dispatchers.IO) {
        val refreshEpoch=epoch
        val wasStored=store.value.sources.any{it.id==source.id}
        var fallback = false
        val document = try { withTimeout(46_000) { http.fetch(source, identities.headers(store.value.settings.sendDeviceId)) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val url = source.fallbackUrl ?: throw error
            SubscriptionHttp.validateUrl(url); fallback = true
            withTimeout(46_000) { http.fetch(source.copy(url = url, etag = null, modified = null), identities.headers(store.value.settings.sendDeviceId)) }
        }
        val job = currentCoroutineContext()
        val incoming = document.body?.let { importer.parse(XrayEngine.decodeSubscription(it, identities.age()?.secretKey),source.id) { job.ensureActive() } }
        currentCoroutineContext().ensureActive()
        update { old ->
            // A deleted source must not be resurrected by an in-flight refresh.
            val current=old.sources.firstOrNull{it.id==source.id}
            if(refreshEpoch!=epoch || wasStored&&current==null || current!=null&&(current.url!=source.url||current.format!=source.format||fallback&&current.fallbackUrl!=source.fallbackUrl))return@update old
            val base=current?:source
            val latest = base.copy(title = if(base.userNamed)base.title else document.title ?: base.title, fetchedAt = System.currentTimeMillis(),
                etag = if (fallback) null else document.etag ?: if (document.body == null) source.etag else null,
                modified = if (fallback) null else document.modified ?: if (document.body == null) source.modified else null,
                usage = document.usage ?: source.usage, updateHours = document.updateHours ?: source.updateHours)
            if (incoming != null) ProfileMerge.replace(old,latest,incoming)
            else { require(old.profiles.any { it.sourceId == source.id }); old.copy(sources = old.sources.map { if (it.id == source.id) latest else it }) }
        }
    }
    suspend fun refreshAll() { for (source in store.value.sources) { currentCoroutineContext().ensureActive(); refresh(source) } }
    suspend fun refreshDue() {
        val now = System.currentTimeMillis()
        for (source in store.value.sources.filter { it.autoUpdate && now - it.fetchedAt >= it.updateHours.coerceIn(1,720) * 3_600_000L }.sortedBy { it.fetchedAt }.take(5)) {
            currentCoroutineContext().ensureActive()
            try { refresh(source) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* keep the complete previous source */ }
        }
    }
    suspend fun saveSource(source: Source) {
        require(source.title.isNotBlank() && source.title.length <= 160 && source.updateHours in 1..720)
        SubscriptionHttp.validateUrl(source.url); source.fallbackUrl?.let(SubscriptionHttp::validateUrl)
        require(source.format in listOf("auto","xray-json"))
        update { it.copy(sources = it.sources.map { old -> if (old.id == source.id) source.copy(userNamed=source.userNamed||source.title!=old.title,
            etag=source.etag.takeIf{source.url==old.url&&source.format==old.format},modified=source.modified.takeIf{source.url==old.url&&source.format==old.format}) else old }) }
    }
    suspend fun saveServer(form: ServerForm, editing: Profile? = null) = withContext(Dispatchers.IO) {
        val config = if (editing == null) form.configuration() else form.updating(editing.configuration)
        XrayEngine.validateServer(config)
        val verified = importer.parse(config).single().copy(title = form.name.trim(), sourceId = null, origin = if (editing?.origin == "json") "json" else "form")
        update { old ->
            if (editing == null || editing.sourceId != null) old.copy(profiles = old.profiles + verified, selected = old.selected ?: verified.id)
            else old.copy(profiles = old.profiles.map { if (it.id == editing.id) verified.copy(id = it.id, favorite = it.favorite, originKey = it.originKey) else it })
        }
    }
    suspend fun saveRouting(profile: RoutingProfile) {
        profile.validate()
        update { old -> old.copy(routingProfiles = (old.routingProfiles.filterNot { it.id == profile.id } + profile).takeLast(100), activeRoutingId = old.activeRoutingId ?: profile.id) }
    }
    suspend fun removeRouting(id: String) = update { it.copy(routingProfiles = it.routingProfiles.filterNot { p -> p.id == id }, activeRoutingId = it.activeRoutingId?.takeUnless { a -> a == id }, sources = it.sources.map { s -> if (s.routingId == id) s.copy(routingId = null) else s }) }
    suspend fun removeProfile(id: String) = update { old ->
        val profiles = old.profiles.filterNot { it.id == id }
        old.copy(profiles = profiles,selected = old.selected?.takeUnless { it == id } ?: profiles.firstOrNull()?.id)
    }
    suspend fun removeSource(id: String) = update { old ->
        val profiles = old.profiles.filterNot { it.sourceId == id }
        old.copy(profiles = profiles,sources = old.sources.filterNot { it.id == id },
            selected = old.selected?.takeIf { selected -> profiles.any { it.id == selected } } ?: profiles.firstOrNull()?.id)
    }
    suspend fun editProfile(id: String, config: String, title: String? = null) {
        val verified = withContext(Dispatchers.Default) { importer.parse(config).single() }
        update { old ->
            val original = old.profiles.firstOrNull { it.id == id } ?: error("Missing profile")
            require(title == null || title.isNotBlank() && title.length <= 160)
            if (original.sourceId != null) old.copy(profiles = old.profiles + verified.copy(title=title?:original.title,sourceId = null), selected = old.selected)
            else old.copy(profiles = old.profiles.map { if (it.id == id) it.copy(title=title?:it.title,configuration = verified.configuration,origin="json") else it })
        }
    }
    suspend fun discardTunnelRequest(id: String) = withContext(Dispatchers.IO) { vault.delete("tunnel-$id") }
    suspend fun writeTunnelRequest(profile: Profile, settings: ClientSettings, assets: String, snapshot: ClientStore = store.value): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val routingId = snapshot.sources.firstOrNull { it.id == profile.sourceId }?.routingId ?: snapshot.activeRoutingId
        val routing = snapshot.routingProfiles.firstOrNull { it.id == routingId }
        vault.write("tunnel-$id",json.encodeToString(TunnelRequest(profile,settings,assets,routing)))
        id
    }
    fun backup(): String = json.encodeToString(store.value.copy(geoGeneration = null))
    suspend fun restore(text: String) = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= 15 * 1024 * 1024)
        val restored = json.decodeFromString<ClientStore>(text)
        require(restored.schema == 1 && restored.profiles.size <= 500)
        restored.profiles.forEach { require(UUID.fromString(it.id) != null); importer.parse(it.configuration) }
        restored.sources.forEach { SubscriptionHttp.validateUrl(it.url) }
        require(SettingsValidation.valid(restored.settings) && restored.routingProfiles.size <= 100)
        restored.routingProfiles.forEach(RoutingProfile::validate)
        require(restored.geoSources.size <= 12 && restored.geoSources.map{it.name.lowercase(java.util.Locale.ROOT)}.distinct().size==restored.geoSources.size)
        restored.geoSources.forEach(GeoSource::validate)
        val new = restored.copy(geoGeneration = null,selected = restored.selected?.takeIf { id -> restored.profiles.any { it.id == id } } ?: restored.profiles.firstOrNull()?.id)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            epoch++
            vault.write("client",json.encodeToString(new)); _store.value = new; loaded = true
            telemetry.preferences(new.settings,new.profiles.isNotEmpty(),new.profiles.firstOrNull{it.id==new.selected})
            SubscriptionJobs.schedule(appContext,new)
        }
    }
    suspend fun reset(mode: String):Unit = withContext(Dispatchers.IO) {
        when (mode) {
            "settings" -> update { it.copy(settings = ClientSettings(), activeRoutingId = null) }
            "routing" -> update { it.copy(routingProfiles = emptyList(), activeRoutingId = null, sources = it.sources.map { s -> s.copy(routingId = null) }, settings = it.settings.copy(bypassLan = false, directDomains = emptyList(), blockedDomains = emptyList(), includedRoutes = emptyList(), excludedRoutes = emptyList())) }
            "everything" -> { epoch++;update { ClientStore() }; identities.clear();vault.deleteTunnelRequests();WidgetStore(appContext).clear();telemetry.update { TelemetryState() };java.io.File(appContext.cacheDir,"exports").deleteRecursively();java.io.File(appContext.cacheDir,"support-exports").deleteRecursively() }
            else -> error("Invalid reset")
        }
        Unit
    }
}

@kotlinx.serialization.Serializable
data class TunnelRequest(val profile: Profile, val settings: ClientSettings, val assets: String, val routing: RoutingProfile? = null)
