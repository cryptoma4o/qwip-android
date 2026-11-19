package com.qwipvpn.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qwipvpn.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSplitInterfaceTests {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as QWIPApplication

    @Before fun prepare() {
        rule.waitUntil(30_000) { app.repository.store.value.geoGeneration != null }
        runBlocking { app.repository.update { it.copy(settings = ClientSettings(theme = "light")) } }
    }

    @Test fun modeAndSelectionAreSavedAndEmptyOnlyListIsFlagged() {
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithText("DNS и подключение").performScrollTo().performClick()
        rule.onNodeWithTag("apps-open").performScrollTo().performClick()
        rule.onNodeWithTag("apps-page").assertIsDisplayed()
        rule.onNodeWithTag("apps-warning").assertDoesNotExist()

        rule.onNodeWithText("Только выбранные").performClick()
        rule.waitUntil(5_000) { app.repository.store.value.settings.appMode == AppSplit.ONLY }
        rule.onNodeWithTag("apps-warning").assertIsDisplayed()

        rule.waitUntil(15_000) { rule.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(isToggleable()).onFirst().performClick()
        rule.waitUntil(5_000) { app.repository.store.value.settings.appPackages.size == 1 }
        rule.onNodeWithTag("apps-warning").assertDoesNotExist()
        assertTrue(AppSplit.isValid(AppSplit.ONLY, app.repository.store.value.settings.appPackages))

        rule.onAllNodes(isToggleable()).onFirst().performClick()
        rule.waitUntil(5_000) { app.repository.store.value.settings.appPackages.isEmpty() }
        rule.onNodeWithTag("apps-warning").assertIsDisplayed()

        rule.onNodeWithText("Все").performClick()
        rule.waitUntil(5_000) { app.repository.store.value.settings.appMode == AppSplit.ALL }
        assertEquals(AppSplit.ALL, app.repository.store.value.settings.appMode)
    }
}
