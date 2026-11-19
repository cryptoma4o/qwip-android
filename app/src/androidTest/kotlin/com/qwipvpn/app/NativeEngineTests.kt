package com.qwipvpn.app

import android.net.VpnService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.data.SecureVault
import com.qwipvpn.app.engine.XrayEngine
import com.qwipvpn.app.tunnel.TunnelController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.net.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineTests {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as QWIPApplication
    @Test fun nativeVersionMatchesPinnedCore() { assertTrue(XrayEngine.version().contains("26.10.21")) }
    @Test fun actualJniConvertsVlessLink() {
        val config = XrayEngine.convertLink("vless://00000000-0000-4000-8000-000000000001@127.0.0.1:14443?type=tcp&encryption=none#Fixture")
        val profiles = SubscriptionImporter { error("JSON must bypass projection") }.parse(config)
        assertEquals(1,profiles.size); assertTrue(config.contains("vless"))
    }
    @Test fun keystoreEncryptsAndRandomizesEveryWrite() {
        val vault = SecureVault(app); val name = "test-"+UUID.randomUUID()
        val data = "synthetic-access-value-"+UUID.randomUUID()
        val file = File(app.noBackupFilesDir,"vault/$name.bin")
        try {
            vault.write(name,data); val first=file.readBytes()
            assertEquals(data,vault.read(name)); assertFalse(String(first).contains(data))
            vault.write(name,data); assertFalse(first.contentEquals(file.readBytes())); assertEquals(data,vault.read(name))
        } finally { vault.delete(name) }
    }
    @Test fun pinnedTlsProxyWorksIndependentlyOfAndroidTun() {
        val configuration=instrumentation.context.assets.open("fixture.json").bufferedReader().use { it.readText() }
        val data=XrayEngine.call("pingBatch",buildJsonObject {
            put("timeout",12);put("url","http://198.51.100.9/fixture")
            putJsonArray("configs") { add(buildJsonObject { put("xrayJson",configuration);put("outboundTag","fixture-proxy") }) }
        })
        assertTrue("Pinned TLS proxy probe failed",data.jsonObject.getValue("results").jsonArray.single().jsonObject.getValue("success").jsonPrimitive.boolean)
    }
    @Test fun actualCoreValidatesBundledRulesAndRejectsCorruption() = runBlocking<Unit> {
        val generation = app.geoStore.prepare()
        val prior = app.geoStore.active(generation)!!
        val bad = File(app.noBackupFilesDir,"invalid-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            File(bad,"geoip.dat").writeText("not a protobuf database")
            assertTrue(runCatching { XrayEngine.validateGeo(bad.path,"geoip","ip") }.isFailure)
            assertTrue(prior.resolve("ready").isFile)
            assertTrue(runCatching { app.geoStore.prepare(true,listOf("http://invalid.invalid/geoip.dat","http://invalid.invalid/geosite.dat")) }.isFailure)
            assertNotNull(app.geoStore.active(generation))
        } finally { bad.deleteRecursively() }
    }
    @Test fun realVpnTunCarriesTcpThroughPinnedTlsAndResolvesUdpDns() = runBlocking<Unit> {
        check(VpnService.prepare(app) == null) { "Grant ACTIVATE_VPN to this package on the owned emulator before running the suite" }
        val configuration = instrumentation.context.assets.open("fixture.json").bufferedReader().use { it.readText() }
        val profile = SubscriptionImporter { error("No projection") }.parse(configuration).single()
        app.telemetry.preferences(ClientSettings(),true,profile)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val controller = TunnelController(app)
        try {
            val generation = app.geoStore.prepare()
            val assets = app.geoStore.active(generation)!!
            val request = app.repository.writeTunnelRequest(profile,ClientSettings(),assets.path)
            instrumentation.runOnMainSync { controller.bind(); controller.start(request) }
            val state = withTimeout(35_000) { controller.state.first { it.phase in listOf(TunnelPhase.CONNECTED,TunnelPhase.ERROR) } }
            assertEquals(state.error,TunnelPhase.CONNECTED,state.phase)
            suspend fun requestThroughVpn(target: String) = withContext(Dispatchers.IO) { Socket().use { socket ->
                socket.soTimeout = 12_000
                socket.connect(InetSocketAddress(target,80),12_000)
                socket.getOutputStream().write("GET /fixture HTTP/1.1\r\nHost: qwip-fixture.invalid\r\nConnection: close\r\n\r\n".toByteArray())
                val response=java.io.ByteArrayOutputStream()
                val chunk=ByteArray(2048)
                while (!response.toString("UTF-8").contains("QWIP encrypted tunnel fixture v1")) {
                    val n=socket.getInputStream().read(chunk)
                    assertTrue("Fixture response ended prematurely",n>0)
                    response.write(chunk,0,n);assertTrue(response.size()<16_384)
                }
                assertTrue(response.toString("UTF-8").contains("200 OK"))
            } }
            requestThroughVpn("198.51.100.9")
            // Persist a deliberately different snapshot. A live session must keep its original.
            app.repository.load()
            val changed=profile.copy(configuration=RawJson.setting(profile.configuration,"outbounds","""[{"protocol":"blackhole","tag":"next-connection-only"}]"""))
            app.repository.update { it.copy(profiles=it.profiles.filterNot { p -> p.id==changed.id }+changed,selected=changed.id) }
            requestThroughVpn("2001:db8::9")
            // Direct UDP DNS ingress must be handled by Core and its preserved hosts map.
            DatagramSocket().use { socket ->
                socket.soTimeout=8_000
                val name="qwip-fixture.invalid".split('.').flatMap { listOf(it.length.toByte())+it.toByteArray().toList() }+listOf(0.toByte())
                val query=byteArrayOf(0x53,0x4f,1,0,0,1,0,0,0,0,0,0)+name.toByteArray()+byteArrayOf(0,1,0,1)
                socket.send(DatagramPacket(query,query.size,InetAddress.getByName(RuntimeConfiguration.RESOLVER),53))
                val reply=DatagramPacket(ByteArray(2048),2048);socket.receive(reply)
                assertEquals(0x53,reply.data[0].toInt()); assertEquals(0x4f,reply.data[1].toInt())
                assertEquals(0,reply.data[3].toInt() and 15)
                assertTrue((reply.data[7].toInt() and 255)>0)
                assertTrue(reply.data.copyOf(reply.length).toList().windowed(4).any { it==listOf(198.toByte(),51.toByte(),100.toByte(),9.toByte()) })
            }
            // Updating saved data must not disconnect or mutate the active native session.
            assertEquals(TunnelPhase.CONNECTED,controller.state.value.phase)
            withTimeout(8_000) { controller.state.first { it.received > 0 && it.sent > 0 } }
            assertTrue(app.telemetry.read().history.any { it.serverName == profile.title && it.ended == null })
            // The isolated process must contribute safe lifecycle evidence to the shared export.
            withTimeout(8_000) {
                while (true) {
                    val logs=app.diagnostics.prepare(ClientStore(),controller.state.value,XrayEngine.version()).text
                    if(logs.contains("TUNNEL CONNECT_STAGE stage=CORE_RUNNING") && logs.contains("TUNNEL TUNNEL_STATE phase=CONNECTED")) {
                        assertFalse(logs.contains(profile.configuration)); assertFalse(logs.contains("00000000-0000-4000-8000-000000000001"))
                        break
                    }
                    delay(150)
                }
            }
        } finally {
            instrumentation.runOnMainSync { controller.stop() }
            withTimeout(8_000) { controller.state.first { it.phase == TunnelPhase.DISCONNECTED } }
            val networks=app.getSystemService(android.net.ConnectivityManager::class.java)
            withTimeout(5_000) {
                while (networks.allNetworks.any { networks.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true }) delay(100)
            }
            instrumentation.runOnMainSync { controller.close() };scenario.close()
            assertTrue(app.telemetry.read().history.any { it.serverName == profile.title && it.ended != null && it.totals.received > 0 })
        }
    }
}
