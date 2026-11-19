package com.qwipvpn.app

import android.net.VpnService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Drives the real VpnService. 198.51.100.9 is a documentation address that only the synthetic
 * proxy fixture can answer, so a response proves the app's traffic entered the tunnel and a failure
 * proves it bypassed it.
 */
@RunWith(AndroidJUnit4::class)
class AppSplitNativeTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as QWIPApplication

    private suspend fun reachesFixture(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 6_000
                socket.connect(InetSocketAddress("198.51.100.9", 80), 6_000)
                socket.getOutputStream().write("GET /fixture HTTP/1.1\r\nHost: qwip-fixture.invalid\r\nConnection: close\r\n\r\n".toByteArray())
                val text = StringBuilder()
                val chunk = ByteArray(2048)
                while (!text.contains("QWIP encrypted tunnel fixture v1")) {
                    val n = socket.getInputStream().read(chunk)
                    if (n <= 0) return@use false
                    text.append(String(chunk, 0, n)); if (text.length > 16_384) return@use false
                }
                true
            }
        }.getOrDefault(false)
    }

    /** Connects with [settings], runs [block] while the tunnel is up and always disconnects afterwards. */
    private fun withVpn(settings: ClientSettings, block: suspend () -> Unit) = runBlocking<Unit> {
        check(VpnService.prepare(app) == null) { "Grant ACTIVATE_VPN to this package on the owned emulator before running the suite" }
        val configuration = instrumentation.context.assets.open("fixture.json").bufferedReader().use { it.readText() }
        val profile = SubscriptionImporter { error("No projection") }.parse(configuration).single()
        app.telemetry.preferences(settings, true, profile)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val controller = TunnelController(app)
        try {
            val assets = app.geoStore.active(app.geoStore.prepare())!!
            val request = app.repository.writeTunnelRequest(profile, settings, assets.path)
            instrumentation.runOnMainSync { controller.bind(); controller.start(request) }
            val state = withTimeout(35_000) { controller.state.first { it.phase in listOf(TunnelPhase.CONNECTED, TunnelPhase.ERROR) } }
            assertEquals(state.error, TunnelPhase.CONNECTED, state.phase)
            block()
        } finally {
            instrumentation.runOnMainSync { controller.stop() }
            withTimeout(8_000) { controller.state.first { it.phase == TunnelPhase.DISCONNECTED || it.phase == TunnelPhase.ERROR } }
            val networks = app.getSystemService(android.net.ConnectivityManager::class.java)
            withTimeout(5_000) {
                while (networks.allNetworks.any { networks.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }) delay(100)
            }
            instrumentation.runOnMainSync { controller.close() }; scenario.close()
        }
    }

    @Test fun allAppsModeSendsTheAppThroughTheTunnel() =
        withVpn(ClientSettings(appMode = AppSplit.ALL)) { assertTrue("Traffic must use the tunnel", reachesFixture()) }

    @Test fun exceptModeWithThisAppBypassesTheTunnel() =
        withVpn(ClientSettings(appMode = AppSplit.EXCEPT, appPackages = listOf(app.packageName))) {
            assertTrue("An excluded app must not use the tunnel", !reachesFixture())
        }

    @Test fun onlyModeWithAnotherAppBypassesTheTunnel() =
        withVpn(ClientSettings(appMode = AppSplit.ONLY, appPackages = listOf("com.android.settings"))) {
            assertTrue("An app outside the allowed list must not use the tunnel", !reachesFixture())
        }

    @Test fun onlyModeWithThisAppUsesTheTunnel() =
        withVpn(ClientSettings(appMode = AppSplit.ONLY, appPackages = listOf(app.packageName))) {
            assertTrue("An allowed app must use the tunnel", reachesFixture())
        }
}
