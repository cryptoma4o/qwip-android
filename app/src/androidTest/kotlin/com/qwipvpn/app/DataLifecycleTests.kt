package com.qwipvpn.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.data.*
import com.qwipvpn.app.engine.XrayEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Only synthetic data in a separate storage namespace on the owned emulator. */
class DataLifecycleTests {
    @Test fun restoreKeepsOpaqueDataAndResetErasesPrivateSideStores()=runBlocking<Unit>{
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.cacheDir,"lifecycle-fixture-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(app){
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=File(directory,"private").apply{mkdirs()}
            override fun getCacheDir()=File(directory,"cache").apply{mkdirs()}
        }
        try {
            val repo=ClientRepository(context);repo.load()
            val configuration="""{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"example.invalid","port":443,"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}}],"future":18446744073709551615}"""
            assertEquals(configuration,XrayEngine.decodeSubscription(configuration,null))
            val profile=Profile(title="Synthetic backup fixture",configuration=configuration,originKey="fixture")
            val source=Source(url="https://example.invalid/synthetic",fallbackUrl="https://example.invalid/fallback",autoUpdate=false)
            repo.update{it.copy(profiles=listOf(profile),sources=listOf(source),selected=profile.id,settings=ClientSettings(theme="dark",trafficStatistics=false))}
            val backup=repo.backup();repo.reset("settings");repo.restore(backup)
            assertEquals(configuration,repo.store.value.profiles.single().configuration)
            assertEquals(profile.id,repo.store.value.selected);assertEquals(source,repo.store.value.sources.single())
            assertFalse(TelemetryStore(context).read().statistics)
            val identity=IdentityStore(context);identity.saveAge(AgeIdentity("AGE-SECRET-KEY-SYNTHETIC","age1synthetic"))
            val widgets=WidgetStore(context);widgets.write(WidgetLibrary().save(WidgetTheme(name="Synthetic theme")));widgets.save(0,WidgetPreferences(layout="ring"))
            val photo=File(context.noBackupFilesDir,"widget-photos/widget-bg-${UUID.randomUUID()}.jpg").apply{writeText("synthetic image")}
            val vault=SecureVault(context);val request="tunnel-${UUID.randomUUID()}";vault.write(request,"synthetic configuration")
            TelemetryStore(context).begin("synthetic-session",profile)
            repo.reset("everything")
            assertTrue(repo.store.value.profiles.isEmpty());assertTrue(repo.store.value.sources.isEmpty())
            assertNull(identity.age());assertNull(vault.read(request));assertFalse(photo.exists())
            assertTrue(widgets.library().custom.isEmpty());assertEquals(WidgetPreferences(),widgets.preferences(0))
            assertTrue(TelemetryStore(context).read().history.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
