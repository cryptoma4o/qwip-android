package com.qwipvpn.app

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.ui.SecretSupportLogs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SupportLogsInterfaceTests {
    @get:Rule val rule=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val diagnostics get()=(context.applicationContext as QWIPApplication).diagnostics
    private val secret="synthetic-private-fixture-key"
    private fun store(): ClientStore {
        val profile=Profile(id=secret,title=secret,configuration="{\"privateKey\":\"$secret\"}",originKey=secret)
        return ClientStore(profiles=listOf(profile),selected=secret,sources=listOf(Source(id=secret,url="https://synthetic.invalid/$secret")))
    }
    @Test fun sevenTapsRevealLogsAndOnlyTheSendButtonOpensAChooser() {
        val sent=AtomicReference<Intent?>()
        val monitor=object: Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_CHOOSER)return null
                sent.set(intent)
                return Instrumentation.ActivityResult(0,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            rule.setContent { MaterialTheme { SecretSupportLogs(store(),TunnelState(TunnelPhase.ERROR,title=secret,error=secret),"26.10.21") } }
            rule.onNodeWithTag("support-logs-sheet").assertDoesNotExist()
            repeat(6) { rule.onNodeWithTag("about-version").performClick() }
            rule.onNodeWithTag("support-logs-sheet").assertDoesNotExist()
            assertNull(sent.get())
            rule.onNodeWithTag("about-version").performClick()
            rule.onNodeWithTag("support-logs-sheet").assertExists()
            rule.waitUntil(10_000) { !rule.onNodeWithTag("support-logs-send").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
            rule.onNodeWithTag("support-logs-send").assertIsEnabled()
            assertNull(sent.get())
            rule.onNodeWithTag("support-logs-preview").performClick()
            rule.onNodeWithTag("support-logs-text").assertTextContains("QWIP",substring=true)
            val folder=File(context.getExternalFilesDir(null),"qa").apply { mkdirs() }
            File(folder,"secret-support-logs.png").outputStream().use {
                rule.onNodeWithTag("support-logs-sheet").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            rule.onNodeWithTag("support-logs-send").performClick()
            rule.waitUntil(3000) { sent.get()!=null }
            val chooser=sent.get()!!
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND,send.action); assertEquals("text/plain",send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(0,send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            @Suppress("DEPRECATION") val uri=send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content",uri.scheme); assertEquals(context.packageName+".support-files",uri.authority)
            assertEquals(uri,send.clipData!!.getItemAt(0).uri)
            val report=context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertFalse(report.contains(secret)); assertFalse(report.contains("synthetic.invalid"))
            assertTrue(report.contains("VPN: ERROR")); assertTrue(report.contains("Профилей: 1"))
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun aPauseResetsTheSecretGesture() {
        rule.setContent { MaterialTheme { SecretSupportLogs(ClientStore(),TunnelState(),"26.10.21") } }
        repeat(6) { rule.onNodeWithTag("about-version").performClick() }
        SystemClock.sleep(2100)
        rule.onNodeWithTag("about-version").performClick()
        rule.onNodeWithTag("support-logs-sheet").assertDoesNotExist()
    }
    @Test fun exportCanReadAnEncryptedJournalButCannotExposeOtherAppFiles()=runBlocking {
        diagnostics.record(SupportEventKind.CONNECT_FAILED,stage=SupportStage.CORE_START,issue=SupportIssue.TIMEOUT)
        val result=diagnostics.prepare(store(),TunnelState(),"https://synthetic.invalid/$secret")
        assertTrue(result.text.contains("stage=CORE_START issue=TIMEOUT")); assertFalse(result.text.contains(secret))
        val encrypted=File(context.noBackupFilesDir,"vault/support-app.bin")
        assertTrue(encrypted.exists())
        assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1).contains("CONNECT_FAILED"))
        val info=context.packageManager.resolveContentProvider(context.packageName+".support-files",0)!!
        assertFalse(info.exported); assertTrue(info.grantUriPermissions)
        try {
            FileProvider.getUriForFile(context,context.packageName+".support-files",File(context.noBackupFilesDir,"vault/support-app.bin"))
            fail("Encrypted app data must not be inside a shareable root")
        } catch(_: IllegalArgumentException) { }
        try {
            FileProvider.getUriForFile(context,context.packageName+".support-files",File(context.cacheDir,"unrelated-private-file"))
            fail("Only the report subdirectory may be shared")
        } catch(_: IllegalArgumentException) { }
    }
}
