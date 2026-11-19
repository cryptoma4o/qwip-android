package com.qwipvpn.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qwipvpn.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceTests {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as QWIPApplication
    @Before fun prepare() {
        rule.waitUntil(30_000) { app.repository.store.value.geoGeneration != null }
        val parser=SubscriptionImporter { error("No native projection") }
        val germany=parser.parse("""{"remarks":"Германия 🇩🇪","outbounds":[{"protocol":"freedom","tag":"test-germany"}]}""").single()
        val canada=parser.parse("""{"remarks":"Канада 🇨🇦","outbounds":[{"protocol":"freedom","tag":"test-canada"}]}""").single()
        runBlocking { app.repository.update { it.copy(profiles=listOf(germany,canada),sources=emptyList(),selected=germany.id,settings=ClientSettings(theme="light")) } }
    }
    @Test fun chooseLocationInTwoTapsThenPowerIsReady() {
        rule.onNodeWithTag("location-card").performClick()
        screenshot("locations-light")
        rule.onNodeWithText("Канада 🇨🇦").performClick()
        rule.waitUntil(5_000) { app.repository.store.value.selected == app.repository.store.value.profiles.first { it.title.startsWith("Канада") }.id }
        rule.onNodeWithTag("selected-server",useUnmergedTree=true).assertTextEquals("Канада").assertIsDisplayed()
        rule.onNodeWithTag("vpn-power").assertIsEnabled()
        rule.onNodeWithText("DNS и подключение").assertDoesNotExist()
        screenshot("home-light")
    }
    @Test fun advancedControlsLiveInSettingsAndSupportDarkTheme() {
        runBlocking { app.repository.update { it.copy(settings=it.settings.copy(theme="dark")) } }
        screenshot("home-dark")
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithText("Маршрутизация").performScrollTo().assertIsDisplayed()
        screenshot("settings-dark")
        rule.onNodeWithText("DNS и подключение").performScrollTo().performClick()
        rule.onNodeWithText("Резервные DNS-серверы").assertIsDisplayed()
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.onNodeWithTag("tab-connect").performClick()
        rule.onNodeWithTag("vpn-power").assertIsDisplayed()
    }
    @Test fun invalidImportReturnsAnErrorAndLeavesControlsResponsive() {
        rule.onNodeWithTag("add-config").performClick()
        rule.onNodeWithTag("import-text").performTextInput("invalid test fixture")
        rule.onNodeWithTag("import-submit").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(UserMessages.IMPORT_FAILED).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("import-text").assertIsEnabled()
        rule.onNodeWithTag("import-submit").assertIsEnabled()
        screenshot("import-error")
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val folder=java.io.File(app.getExternalFilesDir(null),"qa").apply { mkdirs() }
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(folder,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
}
