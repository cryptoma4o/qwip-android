package com.qwipvpn.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSplitTests {
    @Test fun cleanKeepsOnlyValidUniquePackageNames() {
        val cleaned = AppSplit.clean(listOf(" com.example.app ", "com.example.app", "bad", "1.bad.start", "x.y; rm", "org.test_1.A"))
        assertEquals(listOf("com.example.app", "org.test_1.A"), cleaned)
    }

    @Test fun cleanCapsListLength() {
        val many = (1..500).map { "com.example.app$it" }
        assertEquals(AppSplit.MAX_PACKAGES, AppSplit.clean(many).size)
    }

    @Test fun onlyModeRequiresAtLeastOneValidApp() {
        assertFalse(AppSplit.isValid(AppSplit.ONLY, emptyList()))
        assertFalse(AppSplit.isValid(AppSplit.ONLY, listOf("not a package")))
        assertTrue(AppSplit.isValid(AppSplit.ONLY, listOf("com.example.app")))
        assertTrue(AppSplit.isValid(AppSplit.EXCEPT, emptyList()))
        assertTrue(AppSplit.isValid(AppSplit.ALL, emptyList()))
        assertFalse(AppSplit.isValid("unknown", listOf("com.example.app")))
    }

    @Test fun toggleAddsAndRemovesWithoutDuplicates() {
        val added = AppSplit.toggle(emptyList(), "com.example.app", true)
        assertEquals(listOf("com.example.app"), AppSplit.toggle(added, "com.example.app", true))
        assertEquals(emptyList(), AppSplit.toggle(added, "com.example.app", false))
        assertEquals(emptyList(), AppSplit.toggle(emptyList(), "invalid", true))
    }

    @Test fun oldStoredSettingsKeepWorkingWithDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val settings = json.decodeFromString<ClientSettings>("""{"mtu":1400,"bypassLan":true}""")
        assertEquals(AppSplit.ALL, settings.appMode)
        assertTrue(settings.appPackages.isEmpty())
        assertEquals(1400, settings.mtu)
    }
}
