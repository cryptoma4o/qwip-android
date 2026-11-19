package com.qwipvpn.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.viewModels
import com.qwipvpn.app.ui.QWIPRoot
import com.qwipvpn.app.surfaces.ControlIntents
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val model: ClientViewModel by viewModels()
    private var controlJob:Job?=null
    private var showVpnDisclosure by mutableStateOf(false)
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){authorizeVpn()}
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) model.connect()
        else {
            model.app.diagnostics.record(com.qwipvpn.core.SupportEventKind.VPN_CONSENT_DENIED)
            model.message.value = getString(R.string.vpn_permission)
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.action(getString(R.string.import_failed),getString(R.string.configs_added)) {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArrayOutputStream(); val bytes = ByteArray(16384)
                    while (true) { val n = stream.read(bytes); if (n < 0) break; require(buffer.size()+n <= 8*1024*1024); buffer.write(bytes,0,n) }
                    com.qwipvpn.core.SubscriptionImporter.decodeUtf8(buffer.toByteArray())
                } ?: error("No file")
            }
            model.repository.import(text)
        }
    }
    private val backupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.action(success=getString(R.string.backup_saved)) {
            val snapshot = model.repository.backup()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(snapshot.toByteArray()) } ?: error("No file") }
        }
    }
    private val restoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.action(getString(R.string.restore_failed),getString(R.string.backup_restored)) {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArrayOutputStream(); val bytes = ByteArray(16384)
                    while (true) { val n = stream.read(bytes); if (n < 0) break; require(buffer.size()+n <= 15*1024*1024); buffer.write(bytes,0,n) }
                    com.qwipvpn.core.SubscriptionImporter.decodeUtf8(buffer.toByteArray())
                } ?: error("No file")
            }
            model.repository.restore(text)
            if (model.app.geoStore.active(model.store.value.geoGeneration) == null) {
                val generation = model.app.geoStore.prepare()
                model.repository.update { it.copy(geoGeneration = generation) }
            }
        }
    }
    private val qrImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri==null)model.openImport()else model.readQrImage(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showVpnDisclosure = savedInstanceState?.getBoolean("show-vpn-disclosure") ?: false
        enableEdgeToEdge()
        ControlIntents.shortcuts(this)
        setContent {
            QWIPRoot(model,
                onConnect = ::authorizeConnect,
                showVpnDisclosure = showVpnDisclosure,
                onDisclosureChoice = ::completeVpnDisclosure,
                onImportFile = { importFile.launch(arrayOf("application/json","text/*","application/octet-stream")) },
                onQrImage = { qrImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onExport = { backupFile.launch("QWIP-backup.json") },
                onRestore = { restoreFile.launch(arrayOf("application/json","text/*")) })
        }
        receive(intent)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);receive(intent)}
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("show-vpn-disclosure", showVpnDisclosure)
        super.onSaveInstanceState(outState)
    }
    private fun completeVpnDisclosure(accepted: Boolean) {
        showVpnDisclosure = false
        if (!accepted) return
        try {
            model.app.acceptVpnDisclosure()
            authorizeConnect()
        } catch (_: java.io.IOException) {
            model.message.value = getString(R.string.vpn_disclosure_save_failed)
        }
    }
    private fun authorizeConnect(){
        if(model.store.value.profiles.isEmpty()){model.openImport();return}
        if (!model.app.vpnDisclosureAccepted) {
            showVpnDisclosure = true
            return
        }
        val prefs=getSharedPreferences("notification-consent",MODE_PRIVATE)
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!prefs.getBoolean("asked",false)){
            prefs.edit().putBoolean("asked",true).apply();notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS);return
        }
        authorizeVpn()
    }
    private fun authorizeVpn(){
        val consent=VpnService.prepare(this);if(consent!=null)vpnConsent.launch(consent)else model.connect()
    }
    private fun receive(incoming:Intent){
        val command=ControlIntents.command(this,incoming)
        if(command!=null){
            model.requestedTab.value=0;controlJob?.cancel()
            if(command=="disconnect")model.disconnectFromSurface()
            else if(command=="connect")controlJob=lifecycleScope.launch{model.ready.first{it};authorizeConnect()}
            return
        }
        val raw=if(incoming.action==Intent.ACTION_SEND)incoming.getStringExtra(Intent.EXTRA_TEXT)else if(incoming.action==Intent.ACTION_VIEW)incoming.dataString else null
        if(!raw.isNullOrBlank()&&raw.toByteArray().size<=com.qwipvpn.core.SubscriptionImporter.MAX_BYTES){
            if(raw.startsWith("content://")){
                model.action(getString(R.string.import_failed)){
                    val text=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){contentResolver.openInputStream(android.net.Uri.parse(raw))?.use{stream->val output=ByteArrayOutputStream();val chunk=ByteArray(16384);while(true){val n=stream.read(chunk);if(n<0)break;require(output.size()+n<=com.qwipvpn.core.SubscriptionImporter.MAX_BYTES);output.write(chunk,0,n)};com.qwipvpn.core.SubscriptionImporter.decodeUtf8(output.toByteArray())}?:error("No file")}
                    model.importDraft.value=text;model.openImport()
                }
            }else model.acceptQr(raw)
        }
    }
}
