package com.qwipvpn.app.data

import android.content.Context
import android.os.Build
import com.qwipvpn.app.engine.XrayEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable data class AgeIdentity(val secretKey: String, val publicKey: String)
class IdentityStore(context: Context) {
    private val vault = SecureVault(context)
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun deviceId(): String = vault.read("device-id")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
        ?: UUID.randomUUID().toString().replace("-", "").also { vault.write("device-id", it) }
    @Synchronized fun resetDeviceId(): String { vault.delete("device-id"); return deviceId() }
    @Synchronized fun age(): AgeIdentity? = vault.read("age-identity")?.let { json.decodeFromString<AgeIdentity>(it) }
    suspend fun generateAge(hybrid: Boolean): AgeIdentity = withContext(Dispatchers.IO) {
        val pair = XrayEngine.ageKeyPair(hybrid); AgeIdentity(pair.first, pair.second).also { saveAge(it) }
    }
    @Synchronized fun saveAge(value: AgeIdentity) {
        require(value.secretKey.startsWith("AGE-SECRET-KEY-") && value.secretKey.length <= 8192 && value.secretKey.none(Char::isWhitespace) && (value.publicKey.isBlank() || value.publicKey.startsWith("age1")) && value.publicKey.length <= 4096 && value.publicKey.none(Char::isISOControl))
        vault.write("age-identity", json.encodeToString(value))
    }
    @Synchronized fun clear() { vault.delete("age-identity"); vault.delete("device-id") }
    fun headers(sendDevice: Boolean): Map<String, String> = buildMap {
        if (sendDevice) {
            put("X-HWID", deviceId()); put("X-Device-OS", "Android"); put("X-Ver-OS", Build.VERSION.RELEASE.take(64))
            put("X-Device-Model", Build.MODEL.filterNot(Char::isISOControl).take(160))
        }
        age()?.publicKey?.takeIf(String::isNotBlank)?.let { put("X-Age-Public-Key", it) }
    }
}
