package com.qwipvpn.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local AES-GCM with a newly generated IV for every atomic write. */
class SecureVault(context: Context,private val keyAlias: String = ALIAS) {
    private val directory = File(context.noBackupFilesDir,"vault").apply { mkdirs() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }
    private fun file(name: String): File { require(name.matches(Regex("[a-zA-Z0-9-]{1,80}"))); return File(directory,name+".bin") }
    @Synchronized fun read(name: String): String? {
        val f = file(name)
        if (!f.exists() && !File(f.path+".bak").exists()) return null
        val data = AtomicFile(f).readFully()
        require(data.size in 30..(16 * 1024 * 1024) && data[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(1,13)))
        cipher.updateAAD(("QWIP-v1:"+name).toByteArray())
        return String(cipher.doFinal(data.copyOfRange(13,data.size)),Charsets.UTF_8)
    }
    @Synchronized fun write(name: String, text: String) {
        val plain = text.toByteArray(); require(plain.size <= 15 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        cipher.updateAAD(("QWIP-v1:"+name).toByteArray())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val atomic = AtomicFile(file(name)); val stream = atomic.startWrite()
        try { stream.write(encrypted); atomic.finishWrite(stream) } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
    @Synchronized fun delete(name: String) { AtomicFile(file(name)).delete() }
    @Synchronized fun deleteTunnelRequests() {
        directory.listFiles()?.map { it.name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".bin") }?.distinct()
            ?.filter { it.matches(Regex("tunnel-[a-f0-9-]{36}")) }?.forEach { delete(it) }
    }
    companion object { private const val ALIAS = "com.qwipvpn.app.storage.v1" }
}
