package com.qwipvpn.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qwipvpn.app.qr.QrImages
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrImportTests {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private lateinit var model: ClientViewModel
    private val value="https://example.invalid/s/synthetic-qr-test"
    @Before fun prepare() {
        rule.runOnIdle { model=ViewModelProvider(rule.activity)[ClientViewModel::class.java];model.closeImport(clear=true) }
        rule.waitUntil(30_000) { model.ready.value }
    }
    private fun image(withCode: Boolean): File {
        val side=360;val bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
        val matrix=if(withCode)QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,side,side) else null
        val pixels=IntArray(side*side) { i -> if(matrix?.get(i%side,i/side)==true)android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        bitmap.setPixels(pixels,0,side,0,0,side,side)
        val file=File(rule.activity.cacheDir,"qr-test-${UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        return file
    }
    @Test fun selectedImageDecodesLocallyAndOnlyPrefillsImportDraft() {
        val file=image(true)
        val before=model.store.value
        try {
            assertEquals(value,QrImages.decode(rule.activity,Uri.fromFile(file)))
            rule.runOnIdle { model.readQrImage(Uri.fromFile(file)) }
            rule.waitUntil(8_000) { !model.busy.value && model.importDraft.value==value }
            rule.onNodeWithTag("import-text").assertTextContains(value)
            assertTrue(model.importOpen.value)
            assertEquals(before.profiles,model.store.value.profiles)
            assertEquals(before.sources,model.store.value.sources)
        } finally { file.delete() }
    }
    @Test fun missingQrKeepsPreviousDraftAndShowsRecoverableError() {
        val file=image(false)
        try {
            rule.runOnIdle { model.importDraft.value=value;model.readQrImage(Uri.fromFile(file)) }
            rule.waitUntil(8_000) { !model.busy.value && model.lastError.value!=null }
            assertEquals(value,model.importDraft.value)
            rule.onNodeWithTag("import-error").assertExists()
            rule.onNodeWithTag("qr-image").assertIsEnabled()
        } finally { file.delete() }
    }
    @Test fun cameraScannerOpensAndClosesWithoutLosingTheDraft() {
        rule.runOnIdle { model.importDraft.value=value;model.openImport() }
        rule.onNodeWithTag("scan-qr").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Закрыть сканер").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Закрыть сканер").performClick()
        rule.onNodeWithTag("import-text").assertTextContains(value)
        assertEquals(value,model.importDraft.value)
    }
}
