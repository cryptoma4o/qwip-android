package com.qwipvpn.app

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.ui.ConnectionScreen
import com.qwipvpn.app.ui.ImportSheet
import com.qwipvpn.app.ui.NetworkDiagnosticsScreen
import com.qwipvpn.app.ui.SettingsScreen
import com.qwipvpn.app.ui.WidgetsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChineseLocalizationTests {
    @get:Rule val rule = createComposeRule()
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun localized(tag: String) = target.createConfigurationContext(Configuration(target.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag))
    })
    private fun chinese(content: @Composable () -> Unit) {
        val context = localized("zh-Hans-CN")
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration) {
                MaterialTheme(content=content)
            }
        }
    }

    @Test fun simplifiedChineseResolvesAcrossRegionsAndRussianRemainsAvailable() {
        for(tag in listOf("zh-CN","zh-SG","zh-Hans")) {
            assertEquals("设置",localized(tag).getString(R.string.settings))
            assertEquals("节点数：3",localized(tag).getString(R.string.location_count,3))
        }
        assertEquals("Настройки",localized("ru").getString(R.string.settings))
        assertEquals("Settings",localized("en").getString(R.string.settings))
        if(android.os.Build.VERSION.SDK_INT>=33) {
            val locales=android.app.LocaleConfig(target).supportedLocales!!
            assertTrue(locales.toLanguageTags().contains("ru"))
            assertTrue(locales.toLanguageTags().contains("zh-Hans"))
        }
    }

    @Test fun chineseHomeKeepsTheOneTapImportActionAndAccessibilityLabel() {
        var imports=0
        chinese { ConnectionScreen(ClientStore(),TunnelState(),true,false,{}, {}, {}, { imports++ }) }
        rule.onNodeWithTag("connection-status").assertTextEquals("未连接")
        rule.onNodeWithContentDescription("连接").assertIsEnabled()
        rule.onNodeWithTag("vpn-power").performClick()
        rule.runOnIdle { assertEquals(1,imports) }
        screenshot("home-chinese")
    }

    @Test fun chineseSettingsCanOpenAndSaveRouting() {
        var saved: ClientSettings?=null
        chinese {
            var page by remember { mutableStateOf("settings") }
            SettingsScreen(page,ClientStore(),TunnelState(),"Xray",false,{ page=if(it=="routing-profiles")"routing" else it },{ saved=it },{}, {},
                { _,_ -> },{}, {},{}, {},{ _,_ -> })
        }
        rule.onNodeWithText("路由").performScrollTo().performClick()
        rule.onNodeWithText("直连域名").assertIsDisplayed()
        screenshot("routing-chinese")
        rule.onNodeWithText("保存").performScrollTo().performClick()
        rule.runOnIdle { assertNotNull(saved) }
    }

    @Test fun chineseImportShowsTranslatedErrorAndQrActions() {
        val message=localized("zh-Hans").getString(R.string.import_failed)
        chinese { ImportSheet("invalid",{},false,message,{}, {},{}, {},{}, {}) }
        rule.onNodeWithTag("import-error").assertTextEquals(message)
        rule.onNodeWithText("扫描二维码").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("从图片识别二维码").performScrollTo().assertIsDisplayed()
        screenshot("import-chinese")
    }

    @Test fun chineseDiagnosisExplainsASelectiveRestriction() {
        val probe=DiagnosticProber { target,_ -> DiagnosticMeasurement(target,
            if(target.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TIMEOUT) }
        chinese { NetworkDiagnosticsScreen(TunnelState(),probe) }
        rule.onNodeWithTag("network-diagnostics-start").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("network-diagnostics-result").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("network-diagnostics-result").assertTextEquals("可能存在白名单限制").performScrollTo()
        screenshot("diagnosis-chinese")
        rule.onNodeWithTag("network-diagnostics-copy").performScrollTo().performClick()
    }
    @Test fun chineseWidgetConfigurationHasTranslatedControls() {
        chinese { WidgetsScreen() }
        rule.onNodeWithTag("widget-new-theme").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("widget-save").performScrollTo().assertTextContains("保存")
        screenshot("widgets-chinese")
    }
    @Test fun largeTextKeepsConnectAndLocationAccessible() {
        val context=localized("en")
        val profile=Profile(title="Synthetic long server name 🇩🇪",configuration="""{"outbounds":[{"protocol":"freedom"}]}""",originKey="fixture")
        var connects=0;var locations=0
        rule.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration,LocalDensity provides Density(density,1.8f)) {
                MaterialTheme { ConnectionScreen(ClientStore(profiles=listOf(profile),selected=profile.id),TunnelState(),true,false,{connects++},{},{locations++},{}) }
            }
        }
        rule.onNodeWithTag("vpn-power").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,connects)}
        rule.onNodeWithTag("location-card").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,locations)}
        screenshot("home-large-text-english")
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val directory=File(target.getExternalFilesDir(null),"qa").apply { mkdirs() }
        val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory,"$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        image.recycle()
    }
}
