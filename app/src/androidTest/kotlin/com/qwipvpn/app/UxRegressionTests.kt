package com.qwipvpn.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qwipvpn.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic data only; tools/test_emulator.py enforces the dedicated AVD. */
@RunWith(AndroidJUnit4::class)
class UxRegressionTests {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as QWIPApplication
    private val model get()=ViewModelProvider(rule.activity)[ClientViewModel::class.java]
    private val canada=Profile(id="ux-canada",title="Канада 🇨🇦",originKey="ux-fixture",configuration="""{"outbounds":[{"protocol":"freedom","tag":"direct"}]}""")
    private val germany=canada.copy(id="ux-germany",title="Германия 🇩🇪")
    @Before fun prepare(){
        rule.waitUntil(30_000){model.ready.value}
        if(model.connection.value.phase!=TunnelPhase.DISCONNECTED){
            rule.runOnUiThread{model.disconnectFromSurface()}
            rule.waitUntil(10_000){model.connection.value.phase==TunnelPhase.DISCONNECTED}
        }
        runBlocking{app.repository.update{it.copy(profiles=listOf(canada,germany),sources=emptyList(),selected=canada.id,
            settings=ClientSettings(theme="light"),routingProfiles=emptyList(),activeRoutingId=null,collapsedGroups=emptySet())}}
    }
    @After fun stopSyntheticTunnel(){
        if(model.connection.value.phase!=TunnelPhase.DISCONNECTED){
            rule.runOnUiThread{model.disconnectFromSurface()}
            rule.waitUntil(10_000){model.connection.value.phase==TunnelPhase.DISCONNECTED}
        }
    }
    private fun openEditor(){
        rule.onNodeWithTag("add-config").performClick()
        rule.onNodeWithTag("manual-server").performScrollTo().performClick()
    }
    @Test fun editorSurvivesRecreationAndProtectsUnsavedInput(){
        openEditor()
        rule.onNodeWithTag("server-name").performTextInput("Synthetic draft")
        rule.onNodeWithTag("server-address").performScrollTo().performTextInput("example.invalid")
        rule.onNodeWithTag("server-id").performScrollTo().performTextInput("00000000-0000-4000-8000-000000000001")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("server-name").performScrollTo().assertTextContains("Synthetic draft")
        assertEquals("00000000-0000-4000-8000-000000000001",model.serverEditor.value?.form?.id)
        rule.onNodeWithTag("server-cancel").assertIsDisplayed().performClick()
        rule.onNodeWithText(app.getString(R.string.discard_changes_title)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.keep_editing)).performClick()
        rule.onNodeWithTag("server-name").assertTextContains("Synthetic draft")
        rule.onNodeWithTag("server-cancel").performClick()
        rule.onNodeWithTag("discard-server-draft").performClick()
        rule.onNodeWithTag("server-name").assertDoesNotExist()
        openEditor()
        assertEquals("",model.serverEditor.value?.form?.name)
        screenshot("ux-editor-clean")
    }
    @Test fun saveFocusesInvalidPortAndStaysReachableAboveKeyboard(){
        openEditor()
        rule.onNodeWithTag("server-name").performTextInput("Synthetic validation")
        rule.onNodeWithTag("server-address").performScrollTo().performTextInput("example.invalid")
        rule.onNodeWithTag("server-id").performScrollTo().performTextInput("00000000-0000-4000-8000-000000000001")
        rule.onNodeWithTag("server-port").performScrollTo().performTextReplacement("70000")
        rule.onNodeWithTag("server-save").assertIsDisplayed().performClick()
        rule.onNodeWithTag("server-port").assertIsFocused().assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.error_server_port)).assertIsDisplayed()
        rule.onNodeWithTag("server-save").assertIsDisplayed()
        screenshot("ux-editor-port-error")
    }
    @Test fun switchingTabsRestoresTheSettingsPage(){
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithText(app.getString(R.string.routing)).performScrollTo().performClick()
        rule.onNodeWithTag("routing-profiles").assertIsDisplayed()
        rule.onNodeWithTag("tab-connect").performClick()
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithTag("routing-profiles").assertIsDisplayed()
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithTag("settings").assertIsDisplayed()
        screenshot("ux-settings-groups")
    }
    @Test fun activeLocationStaysHonestUntilExplicitReconnect(){
        // Reproduce the first connection even after another instrumentation run.
        java.io.File(app.noBackupFilesDir, "vpn-disclosure-v1").delete()
        rule.onNodeWithTag("vpn-power").performClick()
        rule.onNodeWithText(app.getString(R.string.vpn_disclosure_title)).assertIsDisplayed()
        rule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        assertFalse(app.vpnDisclosureAccepted)
        assertEquals(TunnelPhase.DISCONNECTED, model.connection.value.phase)
        rule.onNodeWithTag("vpn-power").performClick()
        rule.onNodeWithText(app.getString(R.string.vpn_disclosure_accept)).performClick()
        rule.waitUntil(30_000){model.connection.value.phase==TunnelPhase.CONNECTED}
        assertEquals(canada.id,model.connection.value.profileId)
        rule.onNodeWithTag("location-card").performScrollTo().performClick()
        rule.onNodeWithText(germany.title).performClick()
        rule.waitUntil(5_000){app.repository.store.value.selected==germany.id&&!model.busy.value}
        rule.onNodeWithTag("active-server").performScrollTo().assertTextContains("Канада",substring=true)
        rule.onNodeWithTag("selected-server",useUnmergedTree=true).assertTextEquals("Германия")
        assertEquals(canada.id,model.connection.value.profileId)
        screenshot("ux-active-and-next")
        // An explicit location choice must remain exact even with smart selection enabled.
        runBlocking{app.repository.update{it.copy(settings=it.settings.copy(smartSelection=true))}}
        rule.onNodeWithTag("vpn-reconnect").performScrollTo().performClick()
        rule.waitUntil(30_000){model.connection.value.phase==TunnelPhase.CONNECTED&&model.connection.value.profileId==germany.id}
        rule.onNodeWithTag("active-server").performScrollTo().assertTextContains("Германия",substring=true)
        rule.onNodeWithTag("vpn-reconnect").assertDoesNotExist()
        screenshot("ux-reconnected")
    }
    private fun screenshot(name:String){
        rule.waitForIdle()
        val folder=java.io.File(app.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
