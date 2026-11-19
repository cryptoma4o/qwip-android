package com.qwipvpn.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

class ParityTests {
    private fun form() = ServerForm(name="Fixture 🇩🇪",address="example.invalid",id="00000000-0000-4000-8000-000000000001",password="synthetic-password")
    private fun profile(config:String=form().configuration(), origin:String="form") = Profile(title="Fixture",configuration=config,originKey="fixture",origin=origin)

    @Test fun oldBackupsRetainProfilesAndConservativeDefaults() {
        val store=Json.decodeFromString<ClientStore>("""{"profiles":[{"id":"old","title":"Old","configuration":"{}","originKey":"old"}],"sources":[{"url":"https://example.invalid/sub"}],"selected":"old","settings":{"theme":"dark"}}""")
        assertEquals("old",store.selected);assertEquals("json",store.profiles.single().origin)
        assertFalse(store.sources.single().autoUpdate);assertFalse(store.settings.autoConnect)
        assertFalse(store.settings.sendDeviceId);assertEquals("dark",store.settings.theme)
        assertTrue(store.routingProfiles.isEmpty())
    }
    @Test fun namedGeoDatabasesRequireSafeNamesTypesAndHttps() {
        GeoSource("custom_domains","https://example.invalid/custom.dat").validate()
        for(name in listOf("../vault","geoip","GEOIP","custom.dat",""))assertFails{GeoSource(name,"https://example.invalid/data").validate()}
        assertFails{GeoSource("custom","http://example.invalid/data").validate()}
        assertFails{GeoSource("custom","https://example.invalid/data","unknown").validate()}
    }
    @Test fun sixProtocolsAndTransportsRoundTripWithoutDroppingCredentials() {
        for(protocol in ServerForm.protocols) {
            val original=form().copy(protocol=protocol,user=if(protocol in listOf("socks","http"))"fixture" else "")
            assertTrue(original.problems().isEmpty(),protocol)
            val read=assertNotNull(ServerForm.from(profile(original.configuration())))
            assertEquals(original.protocol,read.protocol);assertEquals(original.address,read.address)
            if(protocol!="vless"&&protocol!="vmess")assertEquals(original.password,read.password)
        }
        for(network in ServerForm.networks) {
            val original=form().copy(network=network,host="front.invalid",path="/fixture",rawHttpHeader=true,serviceName="service",seed="seed")
            val read=assertNotNull(ServerForm.from(profile(original.configuration())))
            assertEquals(network,read.network)
            if(network in listOf("raw","ws","xhttp","httpupgrade")){assertEquals(original.path,read.path);assertEquals(original.host,read.host)}
            if(network=="grpc")assertEquals("service",read.serviceName)
            if(network=="kcp")assertEquals("seed",read.seed)
        }
    }
    @Test fun structuralEditingKeepsNestedUsersAndOpaqueNumberLiterals() {
        val config="""{"future":18446744073709551615,"outbounds":[{"tag":"p","protocol":"vless","opaque":1.234567890123456789e+100,"settings":{"future":900719925474099312345,"vnext":[{"address":"old.invalid","port":443,"future":18446744073709551615,"users":[{"id":"old","encryption":"none","future":900719925474099312345}]}]},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/old","extra":{"number":18446744073709551615}}}}]}"""
        val result=assertNotNull(ServerForm.from(profile(config))).copy(name="Edited",address="new.invalid",id="next",path="/next").updating(config)
        assertTrue(result.contains("\"future\":18446744073709551615"));assertTrue(result.contains("1.234567890123456789e+100"))
        assertTrue(result.contains("900719925474099312345"));assertTrue(result.contains("\"extra\":{\"number\":18446744073709551615}"))
        val outbound=Json.parseToJsonElement(result).jsonObject.array("outbounds").first().jsonObject
        assertEquals("next",outbound.obj("settings").array("vnext").first().jsonObject.array("users").first().jsonObject.text("id"))
    }
    @Test fun compositeAndMultiUserConfigurationsStayInJsonEditor() {
        val out=form().outbound().toString()
        assertNull(ServerForm.from(profile("""{"outbounds":[$out,$out]}""")))
        assertNull(ServerForm.from(profile("""{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[{"id":"a"},{"id":"b"}]}]}}]}""")))
    }
    @Test fun protocolSwitchClearsOldTlsTransportButKeepsOpaqueSocketSettings() {
        val original=form().copy(network="ws",security="tls",host="front.invalid",serverName="front.invalid").configuration()
        val root=Json.parseToJsonElement(original).jsonObject
        val first=root.array("outbounds").first().jsonObject
        val stream=RawJson.setting(first.getValue("streamSettings").toString(),"sockopt","""{"future":18446744073709551615}""")
        val outbound=RawJson.setting(first.toString(),"streamSettings",stream)
        val config=RawJson.setting(original,"outbounds",RawJson.array(listOf(outbound)))
        val updated=assertNotNull(ServerForm.from(profile(config))).copy(protocol="socks",user="",password="").updating(config)
        val changed=Json.parseToJsonElement(updated).jsonObject.array("outbounds").single().jsonObject
        assertEquals("socks",changed.text("protocol"));assertFalse(changed.obj("streamSettings").containsKey("tlsSettings"))
        assertFalse(changed.obj("streamSettings").containsKey("wsSettings"));assertTrue(updated.contains("18446744073709551615"))
    }
    @Test fun realityAndNoiseValidationRejectsAmbiguousInputs() {
        assertTrue(form().copy(security="reality").problems().contains("publicKey"))
        assertTrue(form().copy(security="reality",publicKey="public",serverName="example.invalid",shortId="abc").problems().contains("shortId"))
        assertFalse(XrayNoise(packet="100-50").valid());assertFalse(XrayNoise(type="base64",packet="?!").valid())
        assertFalse(XraySettings(noises=List(5){XrayNoise()}).valid());assertFalse(XraySettings.range("1-2-3"))
    }
    @Test fun providerOverlaysRequireOptInAndNoiseHasAnActualDialer() {
        val p=profile(origin="json");val inputs=RawJson.elements(RawJson.members(p.configuration).getValue("outbounds"))
        val settings=XraySettings(noisesEnabled=true)
        assertEquals(inputs,settings.overlay(p,inputs))
        val result=settings.copy(applyToProviderJson=true).overlay(p,inputs)
        assertEquals(inputs.size+1,result.size);assertTrue(result.first().contains("dialerProxy"));assertTrue(result.last().contains("noises"))
        assertEquals(p.configuration,RoutingProfile(directIps=listOf("10.0.0.0/8")).apply(p))
    }
    @Test fun metricsHasNoPublicDebugListenerAndRoutesOnlyAuthenticatedInbound() {
        val p=profile();val credentials=MetricsAccess(32199,"fixture","synthetic_password_123")
        val result=Json.parseToJsonElement(RuntimeConfiguration.build(p,ClientSettings(),8,"/geo",metrics=credentials)).jsonObject
        assertFalse(result.obj("metrics").containsKey("listen"));assertTrue(result.obj("metrics").text("tag").isNotEmpty())
        val inbound=result.array("inbounds").map{it.jsonObject}.single{it.text("protocol")=="http"}
        assertEquals("127.0.0.1",inbound.text("listen"));assertEquals(32199,inbound.int("port"))
        assertEquals(credentials.password,inbound.obj("settings").array("accounts").single().jsonObject.text("pass"))
        val first=result.obj("routing").array("rules").first().jsonObject
        assertEquals(inbound.text("tag"),first.array("inboundTag").single().jsonPrimitive.content)
        assertEquals(result.obj("metrics").text("tag"),first.text("outboundTag"));assertFalse(credentials.toString().contains(credentials.password))
        assertFalse(p.configuration.contains("metrics"))
    }
    @Test fun metricsCountsOnlyOwnInboundAndIgnoresDoubleCountedOutbound() {
        assertEquals(ByteTotals(7,11),MetricsCounters.parse("""{"stats":{"inbound":{"tun":{"uplink":7,"downlink":11},"other":{"uplink":500}},"outbound":{"proxy":{"uplink":700,"downlink":1100}}}}""","tun"))
        assertEquals(ByteTotals(),MetricsCounters.parse("""{"stats":{"inbound":{"tun":{"uplink":-2}}}}""","tun"))
    }
    @Test fun cidrExclusionSplitsV4AndV6WithoutResolvingHostnames() {
        val routes=Cidr.routes(listOf("10.0.0.0/8","2001:db8::/32"),listOf("10.1.0.0/16","2001:db8:1::/48"))
        assertFalse(routes.any{it.contains(Cidr.parse("10.1.2.3"))});assertFalse(routes.any{it.contains(Cidr.parse("2001:db8:1::7"))})
        assertTrue(routes.any{it.contains(Cidr.parse("10.2.3.4"))});assertTrue(routes.any{it.contains(Cidr.parse("2001:db8:2::7"))})
        assertEquals("10.0.0.0/8",Cidr.parse("10.7.9.4/8").toString());assertFails{Cidr.parse("example.invalid/24")}
        assertFails{Cidr.parse("127.0.0.1/33")}
    }
    @Test fun routingWireRoundTripAndBootstrapAreEffective() {
        val routing=RoutingProfile(name="Fixture",directDomains=listOf("example.invalid"),blockIps=listOf("192.0.2.0/24"),fakeDns=true,hosts=mapOf("fixture.invalid" to listOf("198.51.100.9")),geoIpUrl="https://example.invalid/ip.dat")
        for(scheme in listOf("qwip","happ","notvpn")) {
            val parsed=RoutingLinks.parse(RoutingLinks.export(routing).replace("qwip://","$scheme://"))
            assertTrue(parsed.activate);assertEquals(routing.copy(id=parsed.profile!!.id),parsed.profile)
        }
        assertTrue(RoutingLinks.parse("qwip://routing/off").disable)
        val applied=Json.parseToJsonElement(routing.apply(profile())).jsonObject
        assertEquals("1.1.1.1",applied.obj("dns").obj("hosts").array("cloudflare-dns.com").single().jsonPrimitive.content)
        assertTrue(applied.containsKey("fakedns"));assertEquals("192.0.2.0/24",applied.obj("routing").array("rules").first().jsonObject.array("ip").single().jsonPrimitive.content)
    }
    @Test fun countryPresetCannotReferenceAbsentTags() {
        assertFails{RoutingProfile.country("ZZ",setOf("RU"),setOf("RU"),"Fixture")}
        val p=RoutingProfile.country("ru",setOf("RU"),setOf("CATEGORY-RU"),"Fixture")
        assertTrue("geoip:ru" in p.directIps);assertEquals(listOf("geosite:category-ru"),p.directDomains)
    }
    @Test fun probesBoundBatchesCacheByConfigurationAndInvalidateNetworks() = runBlocking {
        val sizes=mutableListOf<Int>();var calls=0
        val probes=ServerProbes(ProxyProbe{ps,_,_->sizes+=ps.size;calls++;ps.map{0}})
        val ps=List(12){profile().copy(id="$it")};val settings=ClientSettings(probeMethod="proxy")
        assertTrue(probes.check(ps,settings).all{it.milliseconds==0});assertEquals(listOf(5,5,2),sizes)
        probes.check(ps,settings);assertEquals(3,calls)
        probes.check(listOf(ps.first().copy(configuration=form().copy(address="next.invalid").configuration())),settings);assertEquals(4,calls)
        probes.invalidate();probes.check(ps,settings);assertEquals(7,calls)
    }
    @Test fun secondProbeUsesBestSuccessfulAttemptIncludingZero() = runBlocking {
        var call=0;val probes=ServerProbes(ProxyProbe{_,_,_->listOf(if(call++==0)null else 0)})
        assertEquals(0,probes.check(listOf(profile()),ClientSettings(probeMethod="proxy",probeDouble=true)).single().milliseconds)
    }
    @Test fun networkInvalidationDiscardsInFlightProbeResults() = runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val probes=ServerProbes(ProxyProbe{_,_,_->started.complete(Unit);release.await();listOf(10)})
        val result=async{runCatching{probes.check(listOf(profile()),ClientSettings(probeMethod="proxy"))}}
        started.await();probes.invalidate();release.complete(Unit);assertTrue(result.await().exceptionOrNull() is CancellationException)
    }
    @Test fun smartSelectionUsesHysteresisAndRecentFailures() {
        val now=System.currentTimeMillis();val results=mapOf("a" to ProbeResult("a","reachable",100,now),"b" to ProbeResult("b","reachable",70,now))
        assertEquals("a",ServerChooser.best(listOf("a","b"),results,"a"))
        assertEquals("b",ServerChooser.best(listOf("a","b"),results,"a",mapOf("a" to now)))
        assertTrue(ServerChooser.fresh(listOf("a"),results,now));assertFalse(ServerChooser.fresh(listOf("a"),results,now+121_000))
    }
    @Test fun telemetryRespectsDisabledTrackingAndAvoidsOverflow() {
        val p=profile();val record=ConnectionRecord(id="session",serverName=p.title)
        val tracked=TelemetryState(history=listOf(record)).recording("session",p,"2026-11-01",ByteTotals(4,8),ByteTotals(40,80))
        assertEquals(ByteTotals(4,8),tracked.total);assertEquals(ByteTotals(40,80),tracked.history.single().totals)
        val disabled=tracked.copy(statistics=false,historyEnabled=false)
        assertEquals(disabled,disabled.recording("session",p,"next",ByteTotals(100,100),ByteTotals(100,100)))
        assertEquals(Long.MAX_VALUE,(ByteTotals(Long.MAX_VALUE,0)+ByteTotals(1,1)).sent)
        assertEquals(ByteTotals(),ByteTotals(1,1).delta(ByteTotals(2,2)))
    }
    @Test fun telemetryBoundsDaysAndServers() {
        var state=TelemetryState();for(i in 0..510)state=state.recording("none",profile().copy(id="$i"),"day-$i",ByteTotals(1,1),ByteTotals(1,1))
        assertEquals(90,state.days.size);assertEquals(500,state.servers.size);assertEquals(511L,state.total.received)
    }
    @Test fun staleWidgetsCannotKeepAdvertisingAConnectedSession() {
        val state=SurfaceSnapshot(phase=TunnelPhase.CONNECTED,updatedAt=100_000,startedAt=50_000)
        assertEquals(TunnelPhase.CONNECTED,state.settled(101_000).phase)
        assertEquals(TunnelPhase.DISCONNECTED,state.settled(191_000).phase)
        assertEquals(TunnelPhase.DISCONNECTED,state.settled(80_000).phase)
    }
    @Test fun widgetLibraryBoundsThemesAndRejectsPhotoTraversal() {
        var library=WidgetLibrary();repeat(12){library=library.save(WidgetTheme(name="Fixture $it"))}
        assertEquals(19,library.all.size);assertFails{library.save(WidgetTheme())}
        assertFails{WidgetTheme(photo="../../private.jpg").validate()};assertFails{WidgetTheme(accent="red").validate()}
        for(layout in listOf("standard","compact","button","ring","icon"))WidgetPreferences(layout=layout).validate()
    }
    @Test fun autoConnectNeedsAllConditionsAndSkipsRedactedSsid() {
        val rule=AutoConnectRule(network="wifi",dnsServers=listOf("1.1.1.1"),dnsDomains=listOf("example.invalid"),ssids=listOf("Fixture"))
        assertTrue(rule.valid());assertTrue(rule.matches("wifi",listOf("1.1.1.1"),listOf("sub.example.invalid"),"Fixture"))
        assertFalse(rule.matches("wifi",listOf("1.1.1.1"),listOf("example.invalid")))
        assertFalse(rule.matches("cellular",listOf("1.1.1.1"),listOf("example.invalid"),"Fixture"))
    }
    @Test fun speedMeterExcludesWarmupAndInvalidSamples() {
        val meter=SpeedMeter();meter.record(100,0.0);meter.record(100,0.2);meter.record(1_000_000,1.2)
        assertEquals(8.0,meter.megabits!!,0.0001);meter.record(-100,2.0);meter.record(100,0.5);assertEquals(1_000_200L,meter.total)
    }
    @Test fun providerRequestFormattingPreservesRawTokensAndParameters() {
        val source=Source(url="https://notvpn.vip/s/fixture?token=a%2Bb%2Fc&format=auto&client=legacy&next=x+y")
        assertEquals("https://notvpn.vip/s/fixture?token=a%2Bb%2Fc&next=x+y&format=xray",SubscriptionHttp.requestUrl(source).toString())
        assertEquals("https://example.invalid/sub/json?token=a%2Bb",SubscriptionHttp.requestUrl(Source(url="https://example.invalid/sub?token=a%2Bb",format="xray-json")).toString())
    }
    @Test fun externalIpIsBoundedValidatedAndClosesConnection() = runBlocking<Unit> {
        var last:FakeConnection?=null
        val valid=TransferChecks{FakeConnection("198.51.100.9\n").also{last=it}}.externalIp("https://example.invalid/ip")
        assertEquals("198.51.100.9",valid);assertTrue(last!!.closed)
        assertFails{TransferChecks{FakeConnection("not an address")}.externalIp("https://example.invalid/ip")}
        assertFails{TransferChecks{FakeConnection("x".repeat(4097))}.externalIp("https://example.invalid/ip")}
        assertFails{TransferChecks{error("No HTTP")}.externalIp("http://example.invalid/ip")}
    }
    private class FakeConnection(private val body:String):HttpURLConnection(URL("https://example.invalid")) {
        var closed=false
        override fun connect(){};override fun disconnect(){closed=true};override fun usingProxy()=false
        override fun getResponseCode()=200;override fun getInputStream()=body.byteInputStream()
    }
}
