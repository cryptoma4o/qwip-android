package com.qwipvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QwipConnectionLinkTests {
    private val token = "a".repeat(64)

    @Test fun cabinetAndManualImportSelectFullProfiles() {
        for (kind in listOf("d", "s")) {
            val feed = "https://qwipvpn.com/$kind/$token?format=xray"
            val handoff = "https://www.qwipvpn.com/open#$kind/$token"
            assertEquals(feed, QrPayload.normalize(handoff))
            assertEquals(feed, SubscriptionHttp.importUrl(handoff))
            val source = Source(url = "https://qwipvpn.com/$kind/$token?client=qwip")
            assertEquals(feed, SubscriptionHttp.requestUrl(source).toString())
        }
    }

    @Test fun malformedLinksAreRejectedAndOtherProvidersAreUntouched() {
        for (url in listOf("https://user@www.qwipvpn.com/open#d/$token",
                "https://www.qwipvpn.com:8443/open#d/$token",
                "https://www.qwipvpn.com/open?other#d/$token",
                "https://www.qwipvpn.com/open#https://elsewhere.invalid",
                "https://www.qwipvpn.com/open#d/short")) {
            assertFailsWith<IllegalArgumentException> { QrPayload.normalize(url) }
        }
        val other = "https://example.invalid/sub?custom=1"
        assertEquals(other, QrPayload.normalize(other))
        assertEquals(other, SubscriptionHttp.requestUrl(Source(url = other)).toString())
    }
}
