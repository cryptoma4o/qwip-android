package com.qwipvpn.app

import com.qwipvpn.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedConnection(val requestId: String, val profile: Profile)

/** All UI, shortcut and automatic starts use an immutable encrypted snapshot. */
class ConnectionLauncher(private val app: QWIPApplication) {
    private val mutex = Mutex()
    suspend fun prepare(manual: Boolean = true, allowSmartSelection: Boolean = true, progress: suspend (List<ProbeResult>) -> Unit = {}): PreparedConnection = withContext(Dispatchers.IO) {
        mutex.withLock {
            app.repository.load()
            val snapshot = if (manual) app.repository.store.value else app.repository.diskSnapshot()
            var profile = snapshot.profiles.firstOrNull { it.id == snapshot.selected } ?: error("No selected profile")
            if (allowSmartSelection && snapshot.settings.smartSelection) {
                val candidates = (listOf(profile) + snapshot.profiles.filter { it.id != profile.id && it.sourceId == profile.sourceId }.sortedByDescending { it.favorite }).take(ServerChooser.CANDIDATE_LIMIT)
                var measurements = app.probes.check(candidates, snapshot.settings, progress = progress).associateBy { it.profileId }
                if(!ServerChooser.fresh(candidates.map{it.id},measurements))measurements=app.probes.check(candidates,snapshot.settings,force=true,progress=progress).associateBy{it.profileId}
                val chosen = ServerChooser.best(candidates.map { it.id }, measurements, profile.id, app.recentFailures)
                if (chosen != null) profile = candidates.first { it.id == chosen }
                if (manual && profile.id != snapshot.selected) app.repository.update { old -> if (old.profiles.any { it.id == profile.id }) old.copy(selected = profile.id) else old }
            }
            currentCoroutineContext().ensureActive()
            val routingId = snapshot.sources.firstOrNull { it.id == profile.sourceId }?.routingId ?: snapshot.activeRoutingId
            val routing = snapshot.routingProfiles.firstOrNull { it.id == routingId }?.takeIf { it.applies(profile) }
            val base = app.geoStore.ensureBase(snapshot.geoGeneration,snapshot.geoSources)
            if(manual&&base.name!=snapshot.geoGeneration)app.repository.update{if(it.geoGeneration==snapshot.geoGeneration)it.copy(geoGeneration=base.name)else it}
            val assets = app.geoStore.resolve(base.name, routing)
            val id = app.repository.writeTunnelRequest(profile, snapshot.settings, assets.path, snapshot)
            currentCoroutineContext().ensureActive()
            if (manual) app.telemetry.update { it.copy(autoPaused = false) }
            PreparedConnection(id, profile)
        }
    }
}
