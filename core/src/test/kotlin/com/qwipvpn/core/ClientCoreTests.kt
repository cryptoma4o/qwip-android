package com.qwipvpn.core

import kotlin.test.*
import java.util.Base64

class ClientCoreTests {
    private val config = """{ "remarks":"Германия", "outbounds":[{"tag":"edge","protocol":"vless","settings":{"vnext":[{"address":"example.invalid","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","xhttpSettings":{"extra":{"opaque":18446744073709551615}}}}],"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["geosite:private"],"outboundTag":"edge"}]},"future":{"huge":900719925474099312345,"precise":1.234567890123456789e+100},"dns":{"servers":["1.1.1.1"],"hosts":{"fixture.invalid":"198.51.100.9"}} }"""
    private fun importer() = SubscriptionImporter { error("Full JSON must bypass projection") }
    @Test fun fullProviderJsonRemainsExact() {
        val profile = importer().parse(config).single()
        assertEquals(config,profile.configuration)
        assertEquals("Германия",profile.title)
        assertTrue(profile.configuration.contains("18446744073709551615"))
    }
    @Test fun arrayKeepsEveryOpaqueConfiguration() {
        val second = config.replace("Германия","Канада")
        val profiles = importer().parse("[$config,$second]")
        assertEquals(listOf(config,second),profiles.map { it.configuration })
    }
    @Test fun base64JsonBypassesProjection() {
        val encoded = Base64.getEncoder().encodeToString(config.toByteArray())
        assertEquals(config,importer().parse(encoded).single().configuration)
    }
    @Test fun duplicateKeysRejectedEvenInsideOpaqueValue() {
        assertFails { RawJson.validate("""{"a":{"x":1,"x":2}}""") }
        assertFails { RawJson.validate("""{"a":1,"\u0061":2}""") }
    }
    @Test fun malformedJsonRejected() {
        listOf("{\"a\":01}","[1,]","{\"a\":NaN}","{} {}","{\"a\":\"\\q\"}").forEach { assertFails(it) { RawJson.validate(it) } }
    }
    @Test fun nestedAndEscapedKeysAreReadStructurally() {
        val fields = RawJson.members("""{"\u0061":{"x":["} ] \\\"",1]},"z":1e+20}""")
        assertEquals(setOf("a","z"),fields.keys)
        assertEquals("1e+20",fields["z"])
    }
    @Test fun runtimeChangesOnlyOwnedFields() {
        val profile = importer().parse(config).single()
        val runtime = RuntimeConfiguration.build(profile,ClientSettings(),57,"/private/geo/test")
        val before = RawJson.members(config); val after = RawJson.members(runtime)
        assertEquals(before["future"],after["future"])
        assertEquals(RawJson.elements(before.getValue("outbounds")).first(),RawJson.elements(after.getValue("outbounds")).first())
        assertEquals(config,profile.configuration)
        assertTrue(after.getValue("env").contains("57"))
        assertTrue(after.getValue("inbounds").contains("\"protocol\":\"tun\""))
        assertEquals(RawJson.elements(RawJson.members(before.getValue("routing")).getValue("rules")).last(),
            RawJson.elements(RawJson.members(after.getValue("routing")).getValue("rules")).last())
    }
    @Test fun runtimeReusesInboundTagReferencedByRules() {
        val original = RawJson.setting(config,"inbounds","""[{"tag":"provider-in","protocol":"socks","settings":{},"sniffing":{"enabled":false,"future":9007199254740993}}]""")
        val out = RuntimeConfiguration.build(importer().parse(original).single(),ClientSettings(),9,"/geo")
        assertTrue(out.contains("\"tag\":\"provider-in\""))
        assertTrue(out.contains("\"future\":9007199254740993"))
        assertFalse(out.contains("\"protocol\":\"socks\""))
    }
    @Test fun sourceRefreshPreservesIdentityFavoriteAndSelection() {
        val source = Source(url="https://example.invalid/subscription")
        val first = importer().parse(config,source.id).single().copy(favorite=true)
        val store = ClientStore(profiles=listOf(first),sources=listOf(source),selected=first.id)
        val changed = config.replace("\"domainStrategy\":\"AsIs\"","\"domainStrategy\":\"IPIfNonMatch\"")
        val merged = ProfileMerge.replace(store,source,importer().parse(changed,source.id))
        assertEquals(first.id,merged.selected)
        assertTrue(merged.profiles.single().favorite)
        assertEquals(changed,merged.profiles.single().configuration)
        assertEquals(config,store.profiles.single().configuration)
    }
    @Test fun emptyRefreshCannotDestroyWorkingProfiles() {
        val source = Source(url="https://example.invalid/subscription")
        val store = ClientStore(profiles=importer().parse(config,source.id))
        assertFails { ProfileMerge.replace(store,source,emptyList()) }
        assertEquals(1,store.profiles.size)
    }
    @Test fun customRoutingDoesNotOverwriteProviderRules() {
        val runtime = RuntimeConfiguration.build(importer().parse(config).single(),ClientSettings(bypassLan=true,directDomains=listOf("local.invalid"),blockedDomains=listOf("blocked.invalid")),5,"/geo")
        val rules = RawJson.members(RawJson.members(runtime).getValue("routing")).getValue("rules")
        assertTrue(rules.contains("geosite:private")); assertTrue(rules.contains("geoip:private"))
        assertTrue(rules.indexOf("blocked.invalid") < rules.indexOf("local.invalid"))
    }
    @Test fun dnsDefaultsAndValidationAreExplicit() {
        assertTrue(RuntimeConfiguration.isIpLiteral("1.1.1.1"))
        assertTrue(RuntimeConfiguration.isIpLiteral("2606:4700:4700::1111"))
        assertFalse(RuntimeConfiguration.isIpLiteral("localhost"))
        assertFalse(RuntimeConfiguration.isIpLiteral("256.1.1.1"))
        assertFails { RuntimeConfiguration.build(importer().parse(config).single(),ClientSettings(dns=listOf("example.org")),1,"/geo") }
        assertFails { RuntimeConfiguration.build(importer().parse(config).single(),ClientSettings(mtu=9000),1,"/geo") }
    }
    @Test fun loopbackSystemResolverCannotCreateDnsLoop() {
        val invalid = RawJson.setting(config,"dns","""{"servers":["localhost"]}""")
        assertFails { RuntimeConfiguration.build(importer().parse(invalid).single(),ClientSettings(),1,"/geo") }
    }
    @Test fun onlyHttpsSourcesWithoutEmbeddedCredentialsAccepted() {
        assertEquals("example.invalid",SubscriptionHttp.validateUrl("https://example.invalid/a?key=synthetic").host)
        listOf("http://example.invalid/a","https://user:pass@example.invalid/a","file:///etc/hosts","https://example.invalid/a#bad").forEach { assertFails { SubscriptionHttp.validateUrl(it) } }
    }
    @Test fun subscriptionUsageParsingIsBoundedAndTolerant() {
        val usage = SubscriptionHttp.parseUsage("upload=4; download=8; total=1024; expire=1800000000; unknown=hi")!!
        assertEquals(4,usage.uploaded); assertEquals(8,usage.downloaded); assertEquals(1024,usage.total)
        assertNull(SubscriptionHttp.parseUsage("total=-1;expire=999999999999999999999")!!.expires)
    }
    @Test fun linksAreConvertedIndependentlyAndNamed() {
        val importer = SubscriptionImporter { config }
        val profiles = importer.parse("vless://synthetic@example.invalid:443#Germany%20%2B\nvless://synthetic@example.invalid:444#Canada")
        assertEquals(listOf("Germany +","Canada"),profiles.map { it.title })
        assertNotEquals(profiles[0].originKey,profiles[1].originKey)
    }
    @Test fun unreasonableDepthCannotCrashParser() {
        assertFails { RawJson.validate("[".repeat(150)+"1"+"]".repeat(150)) }
    }
    @Test fun payloadLimitPrecedesNativeConversion() {
        assertFails { importer().parse("x".repeat(SubscriptionImporter.MAX_BYTES+1)) }
    }
    @Test fun malformedUtf8InFilesAndBase64IsNeverSilentlyReplaced() {
        assertFails { SubscriptionImporter.decodeUtf8(byteArrayOf(0xc3.toByte(),0x28)) }
        val bytes=config.toByteArray();val split=bytes.indexOf('Г'.toString().toByteArray().first())
        bytes[split]=0xff.toByte()
        assertFails { importer().parse(Base64.getEncoder().encodeToString(bytes)) }
    }
    @Test fun fakeDnsProviderConfigKeepsHostnameRecovery() {
        val original = RawJson.setting(config,"fakedns","""[{"ipPool":"198.18.0.0/15","poolSize":1024}]""")
        val runtime = RuntimeConfiguration.build(importer().parse(original).single(),ClientSettings(),7,"/geo")
        assertTrue(RawJson.members(runtime).getValue("inbounds").contains("\"fakedns\""))
        assertEquals(RawJson.members(original)["fakedns"],RawJson.members(runtime)["fakedns"])
    }
    @Test fun cancelDuringNodeParsingStopsBeforeNextNativeConversion() {
        var conversions = 0; var checks = 0
        val parser = SubscriptionImporter { conversions++; config }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            parser.parse("vless://a@example.invalid:443\nvless://b@example.invalid:443") {
                checks++;if(checks>=3)throw kotlinx.coroutines.CancellationException()
            }
        }
        assertEquals(1,conversions)
    }
}
