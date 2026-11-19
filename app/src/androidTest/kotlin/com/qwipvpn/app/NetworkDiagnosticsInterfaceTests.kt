package com.qwipvpn.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qwipvpn.core.*
import com.qwipvpn.app.ui.NetworkDiagnosticsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkDiagnosticsInterfaceTests {
    @get:Rule val rule=createComposeRule()
    @Test fun showsConfirmedSelectiveRestrictionAndCanCopyASafeReport() {
        val probe=DiagnosticProber { target,_ -> DiagnosticMeasurement(target,if(target.group==DiagnosticGroup.ALLOWED)DiagnosticOutcome.REACHABLE else DiagnosticOutcome.TIMEOUT,httpStatus=if(target.group==DiagnosticGroup.ALLOWED)403 else null) }
        rule.setContent { MaterialTheme { NetworkDiagnosticsScreen(TunnelState(),probe) } }
        rule.onNodeWithTag("network-diagnostics-start").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("network-diagnostics-result").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("network-diagnostics-result").assertTextEquals("Похоже на белые списки")
        rule.onNodeWithTag("network-diagnostics-result").performScrollTo()
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val folder=java.io.File(context.getExternalFilesDir(null),"qa").apply { mkdirs() }
        java.io.File(folder,"network-diagnosis-android.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        rule.onNodeWithTag("network-diagnostics-copy").performScrollTo().performClick()
    }
    @Test fun anActiveVPNDisablesDirectDiagnosis() {
        val probe=DiagnosticProber { _,_ -> error("A VPN-active screen must not issue a probe") }
        rule.setContent { MaterialTheme { NetworkDiagnosticsScreen(TunnelState(phase=TunnelPhase.CONNECTED),probe) } }
        rule.onNodeWithTag("network-diagnostics-vpn-warning").assertIsDisplayed()
        rule.onNodeWithTag("network-diagnostics-start").assertIsNotEnabled()
    }
}
