package com.qwipvpn.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CabinetLinkTests {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun incomingCabinetLinkOpensADraftAndWaitsForConfirmation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as QWIPApplication
        runBlocking { app.repository.load() }
        val previouslySaved = app.repository.store.value.sources
        val token = "a".repeat(64)
        val handoff = Uri.parse("https://www.qwipvpn.com/open#d/$token")
        val feed = "https://qwipvpn.com/d/$token?format=xray"
        val intent = Intent(Intent.ACTION_VIEW, handoff, app, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var model: ClientViewModel
            scenario.onActivity { model = ViewModelProvider(it)[ClientViewModel::class.java] }
            rule.waitUntil(15_000) { model.ready.value && model.importDraft.value == feed }
            rule.onNodeWithTag("import-text").assertTextContains(feed)
            assertEquals(previouslySaved, app.repository.store.value.sources)
        }
    }
}
