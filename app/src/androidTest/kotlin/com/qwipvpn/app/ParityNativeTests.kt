package com.qwipvpn.app

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qwipvpn.core.*
import com.qwipvpn.app.data.WidgetStore
import com.qwipvpn.app.engine.XrayEngine
import com.qwipvpn.app.surfaces.*
import com.qwipvpn.app.tunnel.TrafficMonitor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParityNativeTests {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as QWIPApplication
    private fun form()=ServerForm(name="Synthetic fixture",address="example.invalid",id="00000000-0000-4000-8000-000000000001",password="synthetic-password")

    @Test fun actualCoreAcceptsEveryEditorProtocolAndTransport() {
        for(protocol in ServerForm.protocols)XrayEngine.validateServer(form().copy(protocol=protocol,user=if(protocol in listOf("socks","http"))"fixture" else "").configuration())
        for(network in ServerForm.networks)XrayEngine.validateServer(form().copy(network=network,path="/fixture",serviceName="fixture",seed="fixture").configuration())
        XrayEngine.validateServer(form().copy(security="tls",serverName="example.invalid",fingerprint="chrome",alpn="h2,http/1.1").configuration())
        XrayEngine.validateServer(form().copy(security="reality",serverName="example.invalid",fingerprint="chrome",publicKey="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",shortId="12ab").configuration())
        val p=Profile(title="Fixture",configuration=form().configuration(),originKey="fixture",origin="form")
        val advanced=RuntimeConfiguration.build(p,ClientSettings(xray=XraySettings(muxEnabled=true,fragmentEnabled=true,noisesEnabled=true)),9,"/geo")
        XrayEngine.validateServer(advanced)
        val tls=form().copy(network="ws",security="tls",serverName="example.invalid").configuration()
        val switched=requireNotNull(ServerForm.from(p.copy(configuration=tls))).copy(protocol="http",user="fixture",password="synthetic-password").updating(tls)
        XrayEngine.validateServer(switched)
    }
    @Test fun metricsRejectsUnauthenticatedDebugAccessAndAuthenticatesPrivateMonitor() {
        val p=Profile(title="Fixture",configuration=form().configuration(),originKey="fixture")
        val access=MetricsAccess(XrayEngine.freePort(),"fixture","synthetic_password_for_test_only")
        val runtime=Json.parseToJsonElement(RuntimeConfiguration.build(p,ClientSettings(),9,"/geo",metrics=access)).jsonObject
        val config=JsonObject(runtime.toMutableMap().apply {
            remove("env");put("inbounds",JsonArray(runtime.getValue("inbounds").jsonArray.filter{it.jsonObject["protocol"]?.jsonPrimitive?.content!="tun"}))
        }).toString()
        try {
            XrayEngine.run(config)
            for(path in listOf("/debug/vars","/debug/pprof/heap")) {
                val connection=URL("http://127.0.0.1$path").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",access.port))) as HttpURLConnection
                try {connection.connectTimeout=2000;connection.readTimeout=2000;assertEquals(407,connection.responseCode)}finally{connection.disconnect()}
            }
            assertEquals(ByteTotals(),TrafficMonitor.read(access,"qwip-packets"))
        } finally { XrayEngine.stop() }
    }
    @Test fun ageKeysRoundTripThroughEncryptedStoreIncludingHybrid()=runBlocking {
        val prior=app.identities.age()
        try {
            for(hybrid in listOf(false,true)) {
                val generated=app.identities.generateAge(hybrid)
                assertEquals(generated,app.identities.age());assertTrue(generated.secretKey.startsWith("AGE-SECRET-KEY-"))
                assertEquals(generated.publicKey,app.identities.headers(false)["X-Age-Public-Key"])
                assertFalse(app.identities.headers(false).containsKey("X-HWID"))
            }
        } finally { if(prior!=null)app.identities.saveAge(prior)else app.identities.clear() }
    }
    @Test fun widgetLayoutsInflateAndControlIntentsRejectForgery() {
        assertNull(ControlIntents.command(app,Intent().setAction("com.qwipvpn.app.CONTROL").putExtra("command","connect")))
        assertEquals("connect",ControlIntents.command(app,ControlIntents.intent(app,"connect")))
        assertNotEquals(ControlIntents.pending(app,"connect",887),ControlIntents.pending(app,"disconnect",887))
        val state=SurfaceSnapshot(phase=TunnelPhase.CONNECTED,serverName="Fixture 🇩🇪",startedAt=android.os.SystemClock.elapsedRealtime()-2000,hasProfiles=true)
        instrumentation.runOnMainSync {
            for(layout in listOf("standard","compact","button","ring","icon")) {
                val rendered=WidgetRenderer.render(app,-1,state,WidgetPreferences(layout=layout),WidgetTheme.presets[3],false).apply(app,FrameLayout(app))
                assertNotNull(rendered.findViewById<View>(R.id.widget_button));assertFalse(rendered.findViewById<View>(R.id.widget_button).hasOnClickListeners())
                for((width,height)in listOf(110 to 90,90 to 70,320 to 170)){
                    val sized=WidgetRenderer.render(app,-1,state,WidgetPreferences(layout=layout),WidgetTheme.presets[3],false,width,height).apply(app,FrameLayout(app)) as android.view.ViewGroup
                    val density=app.resources.displayMetrics.density;val w=(width*density).toInt();val h=(height*density).toInt()
                    sized.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));sized.layout(0,0,w,h)
                    val button=sized.findViewById<View>(R.id.widget_button);val bounds=android.graphics.Rect();button.getDrawingRect(bounds);sized.offsetDescendantRectToMyCoords(button,bounds)
                    assertTrue("Button must fit $layout at $width x $height",bounds.left>=0&&bounds.top>=0&&bounds.right<=w&&bounds.bottom<=h)
                }
            }
        }
        assertNull(WidgetStore(app).photo("../../vault/client.bin"))
    }
    @Test fun actualCoreIndexesNamedCustomDatFiles()=runBlocking<Unit>{
        val generation=app.geoStore.prepare();val baseline=requireNotNull(app.geoStore.active(generation))
        val directory=java.io.File(app.cacheDir,"named-dat-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        try {
            java.io.File(baseline,"geoip.dat").copyTo(java.io.File(directory,"custom_ip.dat"))
            java.io.File(baseline,"geosite.dat").copyTo(java.io.File(directory,"custom_sites.dat"))
            XrayEngine.validateGeo(directory.path,"custom_ip","ip");XrayEngine.validateGeo(directory.path,"custom_sites","domain")
            for(name in listOf("custom_ip","custom_sites"))assertTrue(Json.parseToJsonElement(java.io.File(directory,"$name.json").readText()).jsonObject.getValue("codes").jsonArray.isNotEmpty())
        } finally {directory.deleteRecursively()}
    }
}
