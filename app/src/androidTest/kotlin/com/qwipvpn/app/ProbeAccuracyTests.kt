package com.qwipvpn.app

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.engine.XrayEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbeAccuracyTests {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as QWIPApplication
    private fun asset(name:String)=instrumentation.context.assets.open(name).bufferedReader().use{it.readText()}
    private val target get()=Json.parseToJsonElement(asset("probe-target.json")).jsonObject
    @Test fun proxyProbeNeverSilentlyMeasuresTheDirectOutbound()=runBlocking<Unit>{
        val port=target.getValue("closedPort").jsonPrimitive.int
        val config="""{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"candidate","protocol":"socks","settings":{"servers":[{"address":"10.0.2.2","port":$port}]}}]}"""
        val profile=Profile(title="Synthetic closed SOCKS",configuration=config,originKey="probe-closed")
        assertNull("A reachable direct outbound must not hide a failed proxy",XrayEngine.probe(listOf(profile),1,target.getValue("url").jsonPrimitive.content).single())
    }
    @Test fun tcpProbeDoesNotReportSuccessFromTheActiveTunHandshake()=runBlocking<Unit>{
        withBlackholeVpn{
            val port=target.getValue("closedPort").jsonPrimitive.int
            val p=Profile(title="Synthetic closed endpoint",originKey="probe-tcp",configuration=ServerForm(name="Closed",protocol="socks",address="10.0.2.2",port=port.toString()).configuration())
            val result=app.probes.check(listOf(p),ClientSettings(probeTimeout=1),force=true).single()
            assertNull("The remote port is closed; the TUN must not produce a false TCP ping",result.milliseconds)
        }
    }
    @Test fun nativeProxyProbeCanReachItsServerOutsideTheActiveVpn()=runBlocking<Unit>{
        withBlackholeVpn{
            val profile=SubscriptionImporter{error("No projection")}.parse(asset("fixture.json")).single()
            val result=app.measureProxy(listOf(profile),2,"http://198.51.100.9/fixture").single()
            assertNotNull("The running blackhole VPN must not swallow the independent proxy probe",result)
        }
    }
    @Test fun proxyProbeFindsAnUntaggedProxyAfterADirectOutbound()=runBlocking<Unit>{
        val configuration=asset("fixture.json")
        val first=RawJson.elements(RawJson.members(configuration).getValue("outbounds")).single()
        val untagged=RawJson.members(first).apply{remove("tag")}.entries.joinToString(",","{","}"){RawJson.quote(it.key)+":"+it.value}
        val profile=Profile(title="Synthetic non-first proxy",originKey="probe-untagged",configuration=RawJson.obj("outbounds" to RawJson.array(listOf("""{"protocol":"freedom","tag":"direct"}""",untagged))))
        assertNotNull(app.measureProxy(listOf(profile),3,"http://198.51.100.9/fixture").single())
    }
    @Test fun changingTheProbeMethodClearsPreviouslyDisplayedMeasurements()=runBlocking<Unit>{
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model:ClientViewModel
        scenario.onActivity{model=ViewModelProvider(it)[ClientViewModel::class.java]}
        try{
            withTimeout(30_000){model.ready.first{it}}
            val port=java.net.URI(target.getValue("url").jsonPrimitive.content).port
            val profile=Profile(title="Synthetic open TCP",originKey="probe-method",configuration=ServerForm(name="TCP",protocol="socks",address="10.0.2.2",port=port.toString()).configuration())
            app.repository.update{it.copy(profiles=listOf(profile),sources=emptyList(),selected=profile.id,settings=ClientSettings())}
            withContext(Dispatchers.Main){yield()}
            instrumentation.runOnMainSync{model.probeServers()}
            val measured=withTimeout(10_000){model.probes.first{it[profile.id]?.milliseconds!=null}}
            assertEquals("tcp",measured.getValue(profile.id).method)
            app.repository.update{it.copy(settings=it.settings.copy(probeMethod="proxy"))}
            withTimeout(5_000){model.probes.first{it.isEmpty()}}
        }finally{scenario.close()}
    }
    @Test fun httpLatencyIncludesAControlledResponseDelayWithAndWithoutVpn()=runBlocking<Unit>{
        val profile=SubscriptionImporter{error("No projection")}.parse(asset("fixture.json")).single()
        val port=java.net.URI(target.getValue("url").jsonPrimitive.content).port
        val tcpProfile=Profile(title="Synthetic timing endpoint",originKey="probe-timing",configuration=ServerForm(name="TCP",protocol="socks",address="10.0.2.2",port=port.toString()).configuration())
        val observations=mutableListOf<JsonObject>()
        suspend fun measure(label:String){
            val tcp=app.probes.check(listOf(tcpProfile),ClientSettings(probeTimeout=3),force=true).single().milliseconds
            val fast=app.measureProxy(listOf(profile),5,"http://198.51.100.9/fixture").single()
            val delayed=app.measureProxy(listOf(profile),5,"http://198.51.100.9/delay/350").single()
            observations+=buildJsonObject{
                put("phase",label);put("expectedHttpResponseDelayMs",350)
                put("tcpMs",tcp?.let(::JsonPrimitive)?:JsonNull)
                put("fastHttpMs",fast?.let(::JsonPrimitive)?:JsonNull)
                put("delayedHttpMs",delayed?.let(::JsonPrimitive)?:JsonNull)
            }
            assertNotNull("An open physical TCP port must be reachable",tcp)
            assertNotNull("The fast proxy request must complete",fast)
            assertNotNull("The delayed proxy request must complete",delayed)
            assertTrue("Milliseconds must include the controlled 350 ms response delay",delayed!! in 325..5000)
            assertTrue("TCP connection latency must not include HTTP response time",tcp!!<delayed)
        }
        try{
            measure("vpnOff")
            withBlackholeVpn{measure("vpnOn")}
        }finally{
            val folder=java.io.File(app.getExternalFilesDir(null),"qa").apply{mkdirs()}
            java.io.File(folder,"ping-timing.json").writeText(JsonArray(observations).toString())
        }
    }
    private suspend fun withBlackholeVpn(work:suspend()->Unit){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model:ClientViewModel
        scenario.onActivity{model=ViewModelProvider(it)[ClientViewModel::class.java]}
        try{
            withTimeout(30_000){model.ready.first{it}}
            val profile=Profile(title="Synthetic blackhole VPN",originKey="probe-blackhole",configuration="""{"outbounds":[{"protocol":"blackhole","tag":"blocked"}]}""")
            app.repository.update{it.copy(profiles=listOf(profile),sources=emptyList(),selected=profile.id,settings=ClientSettings(),routingProfiles=emptyList(),activeRoutingId=null)}
            instrumentation.runOnMainSync{model.connect()}
            val state=withTimeout(30_000){model.connection.first{it.phase in listOf(TunnelPhase.CONNECTED,TunnelPhase.ERROR)}}
            assertEquals(state.error,TunnelPhase.CONNECTED,state.phase)
            withContext(Dispatchers.Main){yield()}
            work()
        }finally{
            instrumentation.runOnMainSync{model.disconnectFromSurface()}
            withTimeout(10_000){model.connection.first{it.phase==TunnelPhase.DISCONNECTED}}
            scenario.close()
        }
    }
}
