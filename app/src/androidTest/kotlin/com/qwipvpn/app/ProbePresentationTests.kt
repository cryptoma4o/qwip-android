package com.qwipvpn.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qwipvpn.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbePresentationTests {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun latencyLabelsExposeTheMethodSubMillisecondValueAndStaleness(){
        val app=rule.activity.application as QWIPApplication
        val model=ViewModelProvider(rule.activity)[ClientViewModel::class.java]
        rule.waitUntil(30_000){model.ready.value}
        val profiles=listOf("tcp","http","stale").map{id->Profile(id="ping-$id",title="Synthetic $id",originKey=id,configuration=ServerForm(name=id,protocol="socks",address="example.invalid").configuration())}
        runBlocking{app.repository.update{it.copy(profiles=profiles,sources=emptyList(),selected=profiles.first().id,settings=ClientSettings(theme="light"),collapsedGroups=emptySet())}}
        rule.runOnIdle{model.probes.value=mapOf(
            "ping-tcp" to ProbeResult("ping-tcp","reachable",0,method="tcp"),
            "ping-http" to ProbeResult("ping-http","reachable",125,method="proxy"),
            "ping-stale" to ProbeResult("ping-stale","reachable",30,System.currentTimeMillis()-ServerProbes.CACHE_TTL_MS-1000,"proxy"))}
        for((id,text) in listOf("tcp" to app.getString(R.string.ping_tcp_under_one),"http" to app.getString(R.string.ping_proxy_ms,125),"stale" to app.getString(R.string.ping_stale))){
            rule.onNodeWithTag("home-profile-ping-$id",useUnmergedTree=true).performScrollTo().assert(hasAnyDescendant(hasText(text)))
        }
        screenshot(app,"ping-types-light")
        runBlocking{app.repository.update{it.copy(settings=it.settings.copy(theme="dark"))}}
        rule.onNodeWithTag("home-profile-ping-stale").performScrollTo().assertIsDisplayed()
        screenshot(app,"ping-types-dark")
    }
    private fun screenshot(app:QWIPApplication,name:String){
        rule.waitForIdle()
        val folder=java.io.File(app.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
