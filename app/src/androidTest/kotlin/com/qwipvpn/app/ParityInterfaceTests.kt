package com.qwipvpn.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.data.WidgetStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ParityInterfaceTests {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as QWIPApplication
    @Before fun prepare() {
        rule.waitUntil(30_000){app.repository.store.value.geoGeneration!=null}
        val source=Source(id="fixture-source",title="Fixture subscription",url="https://example.invalid/synthetic",usage=SubscriptionUsage(downloaded=1_073_741_824,total=10_737_418_240))
        val form=ServerForm(name="Fixture 🇩🇪",address="example.invalid",id="00000000-0000-4000-8000-000000000001")
        val p=Profile(id="fixture-profile",title=form.name,configuration=form.configuration(),sourceId=source.id,originKey="fixture",origin="form")
        runBlocking {app.repository.update{it.copy(profiles=listOf(p),sources=listOf(source),selected=p.id,settings=ClientSettings(theme="light"),collapsedGroups=emptySet(),routingProfiles=emptyList(),activeRoutingId=null)}}
    }
    private fun settings(title:Int) {
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithTag("settings").performScrollToNode(hasText(app.getString(title)))
        rule.onNodeWithText(app.getString(title)).performClick()
    }
    @Test fun groupCollapsePersistsAndDoesNotChangeSelection() {
        rule.onNodeWithTag("group-fixture-source").performScrollTo().performClick()
        rule.waitUntil(5_000){"fixture-source" in app.repository.store.value.collapsedGroups}
        rule.onNodeWithTag("home-profile-fixture-profile").assertDoesNotExist()
        assertEquals("fixture-profile",app.repository.store.value.selected)
        rule.onNodeWithTag("group-fixture-source").performScrollTo().performClick()
        rule.waitUntil(5_000){"fixture-source" !in app.repository.store.value.collapsedGroups}
        rule.onNodeWithTag("home-profile-fixture-profile").performScrollTo().assertIsDisplayed()
        screenshot("groups-light")
    }
    @Test fun manualEditorValidatesAndCreatesAUsableSavedServer() {
        rule.onNodeWithTag("add-config").performClick()
        rule.onNodeWithTag("manual-server").performScrollTo().performClick()
        rule.onNodeWithTag("server-name").performTextInput("Synthetic manual server")
        rule.onNodeWithTag("server-address").performTextInput("example.invalid")
        rule.onNodeWithTag("server-id").performTextInput("00000000-0000-4000-8000-000000000001")
        screenshot("server-editor")
        rule.onNodeWithTag("server-save").assertIsDisplayed().performClick()
        rule.waitUntil(10_000){app.repository.store.value.profiles.any{it.title=="Synthetic manual server"}}
        assertEquals("form",app.repository.store.value.profiles.first{it.title=="Synthetic manual server"}.origin)
    }
    @Test fun advancedSettingsSaveAndReopenWithProviderOptInDisabled() {
        settings(R.string.advanced_settings)
        rule.onNodeWithTag("xray-mux").performScrollTo().performClick()
        rule.onNodeWithTag("advanced-save").performScrollTo().performClick()
        rule.waitUntil(5_000){app.repository.store.value.settings.xray.muxEnabled}
        assertFalse(app.repository.store.value.settings.xray.applyToProviderJson)
        rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        rule.onNodeWithText(app.getString(R.string.advanced_settings)).performScrollTo().performClick()
        rule.onNodeWithTag("xray-mux").assertIsOn()
        screenshot("advanced-settings")
    }
    @Test fun routingPresetCreatesRealRulesAndCanBeDisabled() {
        settings(R.string.routing)
        rule.onNodeWithText(app.getString(R.string.preset_lan)).performScrollTo().performClick()
        rule.onNodeWithTag("routing-save").performScrollTo().performClick()
        rule.waitUntil(5_000){app.repository.store.value.routingProfiles.size==1}
        val saved=app.repository.store.value.routingProfiles.single()
        assertTrue("10.0.0.0/8" in saved.directIps);assertEquals(saved.id,app.repository.store.value.activeRoutingId)
        rule.onNodeWithTag("routing-selection").performScrollTo().assertTextEquals(app.getString(R.string.routing_selection,saved.name))
        rule.onNodeWithTag("routing-disable").performScrollTo().performClick()
        rule.waitUntil(5_000){app.repository.store.value.activeRoutingId==null}
        screenshot("routing-profiles")
    }
    @Test fun widgetPreviewCreatesAndPersistsACustomTheme() {
        val store=WidgetStore(app);store.write(WidgetLibrary());store.save(0,WidgetPreferences())
        settings(R.string.widgets)
        rule.waitUntil(5_000){rule.onAllNodesWithTag("widget-preview-ready").fetchSemanticsNodes().isNotEmpty()}
        screenshot("widgets-preview")
        rule.onNodeWithTag("widget-new-theme").performScrollTo().performClick()
        rule.onNodeWithTag("widget-theme-name").performScrollTo().performTextReplacement("Fixture theme")
        rule.onNodeWithTag("widget-theme-save").performScrollTo().performClick()
        rule.onNodeWithTag("widget-save").performScrollTo().performClick()
        rule.waitUntil(5_000){store.library().custom.any{it.name=="Fixture theme"}}
        assertEquals("Fixture theme",store.library().theme(store.preferences(0).themeId).name)
    }
    private fun screenshot(name:String) {
        rule.waitForIdle();val directory=File(app.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
