package com.qwipvpn.core

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val configuration: String,
    val sourceId: String? = null,
    val originKey: String,
    val favorite: Boolean = false,
    val origin: String = "json",
)
@Serializable data class Source(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Подписка",
    val url: String,
    val etag: String? = null,
    val modified: String? = null,
    val fetchedAt: Long = 0,
    val usage: SubscriptionUsage? = null,
    val fallbackUrl: String? = null,
    val autoUpdate: Boolean = false,
    val updateHours: Int = 24,
    val routingId: String? = null,
    val userNamed:Boolean=false,
    val format:String="auto",
)
@Serializable data class SubscriptionUsage(val uploaded: Long = 0, val downloaded: Long = 0, val total: Long? = null, val expires: Long? = null)
@Serializable data class ClientSettings(
    val useProfileDns: Boolean = true,
    val dns: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val mtu: Int = 1500,
    val bypassLan: Boolean = false,
    val directDomains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    val theme: String = "system",
    val refreshOnOpen: Boolean = false,
    val probeMethod: String = "tcp",
    val probeUrl: String = "https://cp.cloudflare.com/",
    val probeTimeout: Int = 5,
    val probeDouble: Boolean = false,
    val probeOnOpen: Boolean = false,
    val serverSort: String = "original",
    val smartSelection: Boolean = false,
    val sendDeviceId: Boolean = false,
    val expiryReminders: Boolean = false,
    val trafficStatistics: Boolean = true,
    val connectionHistory: Boolean = true,
    val ipCheckUrl: String = "https://api.ipify.org",
    val speedTestUrl: String = "https://speed.cloudflare.com/__down?bytes=20000000",
    val autoConnect: Boolean = false,
    val onDemandRules: List<AutoConnectRule> = emptyList(),
    val xray: XraySettings = XraySettings(),
    val includedRoutes: List<String> = emptyList(),
    val excludedRoutes: List<String> = emptyList(),
    val appMode: String = "all",
    val appPackages: List<String> = emptyList(),
)
@Serializable data class ClientStore(
    val schema: Int = 1,
    val profiles: List<Profile> = emptyList(),
    val sources: List<Source> = emptyList(),
    val selected: String? = null,
    val settings: ClientSettings = ClientSettings(),
    val geoGeneration: String? = null,
    val routingProfiles: List<RoutingProfile> = emptyList(),
    val activeRoutingId: String? = null,
    val collapsedGroups: Set<String> = emptySet(),
    val geoSources: List<GeoSource> = emptyList(),
)

@Serializable data class GeoSource(val name: String, val url: String, val kind: String = "domain") {
    fun validate() {
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,32}")) && name.lowercase(java.util.Locale.ROOT) !in setOf("geoip","geosite") && kind in setOf("ip","domain"))
        SubscriptionHttp.validateUrl(url)
    }
}

object ProfileMerge {
    /** A source refresh is one immutable transaction and never changes the active session. */
    fun replace(store: ClientStore, source: Source, incoming: List<Profile>): ClientStore {
        require(incoming.isNotEmpty())
        val old = store.profiles.filter { it.sourceId == source.id }.associateBy { it.originKey }
        val replacement = incoming.map { new ->
            old[new.originKey]?.let { new.copy(id = it.id, favorite = it.favorite, sourceId = source.id) }
                ?: new.copy(sourceId = source.id)
        }
        val profiles = store.profiles.filterNot { it.sourceId == source.id } + replacement
        return store.copy(profiles = profiles,
            sources = store.sources.filterNot { it.id == source.id } + source,
            selected = store.selected?.takeIf { id -> profiles.any { it.id == id } } ?: replacement.first().id)
    }
}

@Serializable enum class TunnelPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
data class TunnelState(val phase: TunnelPhase = TunnelPhase.DISCONNECTED, val profileId: String? = null,
    val title: String = "", val startedAt: Long = 0, val error: String? = null,
    val sent: Long = 0, val received: Long = 0, val sendRate: Long = 0, val receiveRate: Long = 0)

object UserMessages {
    const val IMPORT_FAILED = "Не удалось добавить конфигурацию. Проверьте ссылку или формат данных."
    const val NETWORK_FAILED = "Не удалось загрузить подписку. Проверьте интернет и попробуйте снова."
    const val CONNECT_FAILED = "Не удалось подключиться. Проверьте локацию и настройки."
    const val STORAGE_FAILED = "Не удалось сохранить изменения. Попробуйте снова."
}
