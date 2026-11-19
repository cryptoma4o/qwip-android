package com.qwipvpn.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class ByteTotals(val sent: Long = 0, val received: Long = 0) {
    operator fun plus(other: ByteTotals) = ByteTotals(add(sent, other.sent), add(received, other.received))
    fun delta(previous: ByteTotals) = ByteTotals((sent - previous.sent).coerceAtLeast(0), (received - previous.received).coerceAtLeast(0))
    companion object { fun add(a: Long, b: Long) = if (Long.MAX_VALUE - a.coerceAtLeast(0) < b.coerceAtLeast(0)) Long.MAX_VALUE else a.coerceAtLeast(0) + b.coerceAtLeast(0) }
}
@Serializable data class TrafficBucket(val id: String, val title: String = "", val totals: ByteTotals = ByteTotals())
@Serializable data class ConnectionRecord(val id: String = UUID.randomUUID().toString(), val serverName: String,
    val started: Long = System.currentTimeMillis(), val ended: Long? = null, val outcome: String = "active",
    val reason: String? = null, val stage: String? = null, val totals: ByteTotals = ByteTotals())
@Serializable data class SpeedMeasurement(val id: String = UUID.randomUUID().toString(), val date: Long = System.currentTimeMillis(),
    val serverName: String? = null, val megabits: Double, val latency: Int, val bytes: Long, val seconds: Double)
@Serializable data class SurfaceSnapshot(val phase: TunnelPhase = TunnelPhase.DISCONNECTED, val serverName: String = "",
    val profileId: String? = null, val startedAt: Long = 0, val updatedAt: Long = System.currentTimeMillis(),
    val totals: ByteTotals = ByteTotals(), val hasProfiles: Boolean = false, val selectedServerName:String="") {
    fun settled(now: Long = System.currentTimeMillis()): SurfaceSnapshot {
        val stale = now - updatedAt > 90_000 || now < updatedAt - 5000
        return if (stale && phase in listOf(TunnelPhase.CONNECTED, TunnelPhase.CONNECTING, TunnelPhase.DISCONNECTING)) copy(phase = TunnelPhase.DISCONNECTED, startedAt = 0) else this
    }
}
@Serializable data class TelemetryState(
    val statistics: Boolean = true, val historyEnabled: Boolean = true, val total: ByteTotals = ByteTotals(),
    val days: List<TrafficBucket> = emptyList(), val servers: List<TrafficBucket> = emptyList(),
    val history: List<ConnectionRecord> = emptyList(), val speedTests: List<SpeedMeasurement> = emptyList(),
    val surface: SurfaceSnapshot = SurfaceSnapshot(), val autoPaused: Boolean = false,
) {
    fun recording(id: String, profile: Profile, day: String, delta: ByteTotals, absolute: ByteTotals): TelemetryState {
        fun update(list: List<TrafficBucket>, key: String, title: String, limit: Int): List<TrafficBucket> {
            val value = list.firstOrNull { it.id == key } ?: TrafficBucket(key, title)
            return (listOf(value.copy(title = title, totals = value.totals + delta)) + list.filterNot { it.id == key }).take(limit)
        }
        return copy(total = if (statistics) total + delta else total,
            days = if (statistics) update(days, day, "", 90) else days,
            servers = if (statistics) update(servers, profile.id, profile.title, 500) else servers,
            history = if (historyEnabled) history.map { if (it.id == id) it.copy(totals = absolute) else it } else history)
    }
}

class SpeedMeter {
    private val samples = mutableListOf<Pair<Double, Long>>()
    var total: Long = 0; private set
    fun record(bytes: Int, at: Double) { if (bytes > 0 && at >= (samples.lastOrNull()?.first ?: 0.0)) { total += bytes; samples += at to total } }
    val elapsed get() = samples.lastOrNull()?.first ?: 0.0
    val megabits: Double? get() {
        val first = samples.firstOrNull() ?: return null; val last = samples.last(); if (last.first <= first.first) return null
        val start = if (last.first - first.first >= 0.5) samples.firstOrNull { it.first >= 0.2 } ?: first else first
        if (last.first <= start.first) return null
        return ((last.second - start.second) * 8.0 / (last.first - start.first) / 1_000_000).takeIf { it.isFinite() && it > 0 }
    }
}
