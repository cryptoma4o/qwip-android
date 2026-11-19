package com.qwipvpn.app.engine

import com.qwipvpn.core.RawJson
import com.qwipvpn.core.Profile
import com.qwipvpn.core.ProxyProbeConfiguration
import kotlinx.serialization.json.*
import libXray.LibXray
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single JNI/Go runtime per process. Never expose native errors or requests. */
object XrayEngine {
    private val probes=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(4),{task->Thread(task,"QWIP-Native-Probes").apply{isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    @Synchronized fun call(method: String, payload: JsonObject = buildJsonObject {}): JsonElement {
        val request = buildJsonObject { put("apiVersion",3); put("method",method); put("payload",payload) }.toString()
        require(request.toByteArray().size <= 16 * 1024 * 1024)
        val raw = LibXray.invoke(request)
        require(raw.toByteArray().size <= 16 * 1024 * 1024)
        val reply = Json.parseToJsonElement(raw).jsonObject
        check(reply["success"]?.jsonPrimitive?.booleanOrNull == true) { "Core operation failed" }
        return reply["data"] ?: JsonNull
    }
    fun version() = call("xrayVersion").jsonObject["version"]?.jsonPrimitive?.content ?: "Xray"
    fun convertLink(text: String): String {
        val data = call("convertShareLinksToXrayJson",buildJsonObject { put("text",text) })
        return data.toString().also { require(RawJson.members(it)["outbounds"] != null) }
    }
    fun run(config: String) { call("runXray",buildJsonObject { put("xrayJson",config) }) }
    fun stop() { call("stopXray") }
    fun running() = call("getXrayState").jsonObject["running"]?.jsonPrimitive?.booleanOrNull == true
    fun validateGeo(path: String, name: String, type: String) {
        call("countGeoData", buildJsonObject { put("datDir",path); put("name",name); put("geoType",type) })
    }
    fun validateServer(config: String) {
        val fields = RawJson.members(config)
        val minimal = RawJson.obj("outbounds" to fields.getValue("outbounds"), "log" to """{"access":"none","error":"none","loglevel":"none"}""")
        call("testXray", buildJsonObject { put("xrayJson", minimal) })
    }
    fun probe(profiles: List<Profile>, timeout: Int, url: String, network:ProbeNetwork?=null): List<Int?> {
        require(profiles.size in 1..5 && timeout in 1..15)
        val physical=network?.current()
        try{
        if(network!=null&&physical!=null){
            val controller=network.controller(physical)
            LibXray.registerDialerController(controller)
            LibXray.registerListenerController(controller)
            LibXray.setDNS(controller,network.dns(physical))
        }
        val data = call("pingBatch", buildJsonObject {
            put("timeout",timeout); put("url",url); putJsonArray("configs") {
                profiles.forEach { profile ->
                    val target=runCatching{ProxyProbeConfiguration.from(profile)}.getOrNull()
                    add(buildJsonObject { put("xrayJson",target?.configuration?:"{}");target?.let{put("outboundTag",it.outboundTag)} })
                }
            }
        }).jsonObject.getValue("results").jsonArray
        require(data.size == profiles.size)
        return data.map { item -> item.jsonObject.let { if (it["success"]?.jsonPrimitive?.boolean == true) it.getValue("delay").jsonPrimitive.int.takeIf{it>=0} else null } }
        }finally{if(network!=null)LibXray.resetDNS()}
    }
    suspend fun probeAsync(profiles:List<Profile>,timeout:Int,url:String,network:ProbeNetwork?=null):List<Int?> = suspendCancellableCoroutine { continuation ->
        try{
            val future=probes.submit{try{val result=probe(profiles,timeout,url,network);if(continuation.isActive)continuation.resume(result)}catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}}
            continuation.invokeOnCancellation{future.cancel(true);probes.purge()}
        }catch(e:RejectedExecutionException){continuation.resumeWithException(e)}
    }
    fun decodeSubscription(text: String, secret: String?): String = call("decodeSubscriptionText", buildJsonObject {
        put("text", text); if (!secret.isNullOrBlank()) putJsonObject("age") { put("secretKey", secret) }
    }).jsonObject.getValue("text").jsonPrimitive.content
    fun ageKeyPair(hybrid: Boolean = false): Pair<String, String> = call("generateAgeKeyPair", buildJsonObject { put("keyType", if (hybrid) "hybrid" else "x25519") }).jsonObject.let {
        it.getValue("secretKey").jsonPrimitive.content to it.getValue("publicKey").jsonPrimitive.content
    }
    fun shareLink(config: String): String = call("convertXrayJsonToShareLinks", buildJsonObject { put("xrayJson",config) }).jsonObject.getValue("links").jsonPrimitive.content
    fun freePort(): Int = call("getFreePorts", buildJsonObject { put("count",1) }).jsonObject.getValue("ports").jsonArray.single().jsonPrimitive.int
}
