package com.qwipvpn.app.data

import android.content.Context
import com.qwipvpn.core.*
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The service and UI always read the latest encrypted value under the same cross-process lock. */
class TelemetryStore(context: Context) {
    private val lockFile = File(context.noBackupFilesDir, "telemetry.lock")
    private val vault = SecureVault(context, "com.qwipvpn.app.telemetry.v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun <T> locked(work: () -> T): T = locks.getOrPut(lockFile.path) { ReentrantLock() }.withLock {
        RandomAccessFile(lockFile, "rw").channel.use { channel -> channel.lock().use { work() } }
    }
    private fun current(): TelemetryState = vault.read("telemetry")?.let { json.decodeFromString<TelemetryState>(it) } ?: TelemetryState()
    fun read(): TelemetryState = locked { current() }
    fun update(edit: (TelemetryState) -> TelemetryState): TelemetryState = locked {
        val state = edit(current()).let { it.copy(days = it.days.take(90), servers = it.servers.take(500), history = it.history.take(100), speedTests = it.speedTests.take(20)) }
        vault.write("telemetry", json.encodeToString(state)); state
    }
    fun preferences(settings: ClientSettings, hasProfiles: Boolean,selected:Profile?=null) = update { state ->
        state.copy(statistics = settings.trafficStatistics, historyEnabled = settings.connectionHistory,
            history = if (!settings.connectionHistory) state.history.map { if (it.outcome == "active") it.copy(outcome = "interrupted", ended = System.currentTimeMillis()) else it } else state.history,
            surface = state.surface.copy(hasProfiles = hasProfiles,selectedServerName=selected?.title.orEmpty()))
    }
    fun begin(id: String, profile: Profile) = update { state ->
        val closed = state.history.map { if (it.outcome == "active") it.copy(outcome = "interrupted", ended = System.currentTimeMillis()) else it }
        state.copy(history = if (state.historyEnabled) (listOf(ConnectionRecord(id = id, serverName = profile.title)) + closed).take(100) else closed)
    }
    fun finish(id: String, reason: String, totals: ByteTotals) = update { state ->
        state.copy(history = state.history.map { if (it.id == id && it.outcome == "active") it.copy(ended = System.currentTimeMillis(), outcome = "ended", reason = reason, totals = totals) else it })
    }
    fun fail(profile: Profile?, stage: String) = update { state ->
        state.copy(history = if (state.historyEnabled && profile != null) (listOf(ConnectionRecord(serverName = profile.title, ended = System.currentTimeMillis(), outcome = "failed", stage = stage)) + state.history).take(100) else state.history)
    }
    fun clearStatistics() = update { it.copy(total = ByteTotals(), days = emptyList(), servers = emptyList()) }
    fun clearHistory() = update { it.copy(history = emptyList()) }
    fun clearSpeed() = update { it.copy(speedTests = emptyList()) }
    fun speed(value: SpeedMeasurement) = update { it.copy(speedTests = (listOf(value) + it.speedTests).take(20)) }
    companion object { private val locks = ConcurrentHashMap<String, ReentrantLock>() }
}
