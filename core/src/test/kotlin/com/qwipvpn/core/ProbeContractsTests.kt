package com.qwipvpn.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ProbeContractsTests {
    private fun profile(outbounds:String)=Profile(title="Synthetic",configuration=RawJson.obj("outbounds" to outbounds),originKey="probe-contract")
    @Test fun cacheSeparatesUrlSuffixFromTimeout()=runBlocking {
        var calls=0
        val probes=ServerProbes(ProxyProbe{ps,_,_->calls++;ps.map{calls*100}})
        val p=profile("""[{"protocol":"socks","tag":"candidate","settings":{"address":"example.invalid","port":443}}]""")
        val first=ClientSettings(probeMethod="proxy",probeUrl="https://example.invalid/probe1",probeTimeout=2)
        probes.check(listOf(p),first)
        val second=probes.check(listOf(p),first.copy(probeUrl="https://example.invalid/probe",probeTimeout=12)).single()
        assertEquals(2,calls);assertEquals(200,second.milliseconds);assertEquals("proxy",second.method)
    }
    @Test fun directOutboundNamedProxyCannotOverrideTheSelectedProxy() {
        val p=profile("""[{"protocol":"freedom","tag":"proxy"},{"protocol":"socks","tag":"candidate","settings":{"address":"example.invalid","port":443}}]""")
        assertEquals("candidate",ProxyProbeConfiguration.from(p).outboundTag)
    }
    @Test fun untaggedProxyKeepsDependenciesAndExactOpaqueNumbers() {
        val number="90071992547409931234567890.000123000"
        val direct="""{"protocol":"freedom","tag":"direct"}"""
        val target="""{"protocol":"socks","settings":{"address":"example.invalid","port":443,"opaque":$number},"streamSettings":{"sockopt":{"dialerProxy":"chain"}}}"""
        val dependency="""{"protocol":"socks","tag":"chain","settings":{"address":"chain.invalid","port":1080}}"""
        val collision="""{"protocol":"freedom","tag":"__qwip_probe"}"""
        val projected=ProxyProbeConfiguration.from(profile(RawJson.array(listOf(direct,target,dependency,collision))))
        val parts=RawJson.elements(RawJson.members(projected.configuration).getValue("outbounds"))
        assertEquals(direct,parts[0]);assertEquals(dependency,parts[2]);assertEquals(collision,parts[3])
        val proxy=RawJson.members(parts[1])
        assertEquals(projected.outboundTag,Json.parseToJsonElement(proxy.getValue("tag")).jsonPrimitive.content)
        assertNotEquals("__qwip_probe",projected.outboundTag)
        assertEquals(number,RawJson.members(proxy.getValue("settings"))["opaque"])
        assertEquals(RawJson.members(target)["streamSettings"],proxy["streamSettings"])
    }
    @Test fun freshnessHasABoundedAgeAndRejectsFutureTimestamps() {
        val value=ProbeResult("sample","reachable",0,1_000,"tcp")
        assertTrue(value.fresh(1_000));assertTrue(value.fresh(300_999))
        assertFalse(value.fresh(301_000));assertFalse(value.fresh(999))
    }
}
