package com.qwipvpn.app.data

import android.content.Context
import com.qwipvpn.app.engine.XrayEngine
import com.qwipvpn.core.SubscriptionHttp
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A generation is published only after BOTH files verify and load in the actual core. */
class GeoStore(private val context: Context) {
    private val resolutionLock = Mutex()
    private val root = File(context.noBackupFilesDir,"geo").apply { mkdirs() }
    private val lock by lazy { Json.parseToJsonElement(context.assets.open("GeoData.lock.json").bufferedReader().use { it.readText() }).jsonArray }
    fun active(generation: String?): File? {
        if (generation == null || runCatching { UUID.fromString(generation) }.isFailure) return null
        return File(root,generation).takeIf { File(it,"ready").isFile && File(it,"geoip.dat").isFile && File(it,"geosite.dat").isFile }
    }
    suspend fun tags(generation: String?, name: String): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
        val directory = active(generation) ?: error("Missing GeoData")
        val index = File(directory, "$name.json"); require(index.length() in 1..4_194_304)
        val objectJson = Json.parseToJsonElement(index.readText()).jsonObject
        objectJson["codes"]?.jsonArray.orEmpty().map { it.jsonObject.let { value -> value.getValue("code").jsonPrimitive.content to value.getValue("ruleCount").jsonPrimitive.int } }
    }
    /** The caller stops the VPN before deleting previous generations. */
    suspend fun resetToBundled():String=withContext(Dispatchers.IO){resolutionLock.withLock{
        val generation=prepare();root.listFiles()?.filter{it.name!=generation}?.forEach{it.deleteRecursively()};generation
    }}
    suspend fun prepare(download: Boolean = false, urls: List<String>? = null, extras: List<com.qwipvpn.core.GeoSource> = emptyList()): String = withContext(Dispatchers.IO) {
        val generation = UUID.randomUUID().toString()
        val dir = File(root,generation).apply { mkdirs() }
        try {
            lock.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                val entry = item.jsonObject
                val name = entry.getValue("name").jsonPrimitive.content
                require(name in listOf("geoip","geosite"))
                val file = File(dir,"$name.dat")
                if (download) download(urls?.get(index) ?: entry.getValue("downloadURL").jsonPrimitive.content,file)
                else context.assets.open("geo/$name.dat").use { input -> file.outputStream().use { input.copyTo(it) } }
                if (urls == null || !download) {
                    val hash = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input -> val chunk = ByteArray(65536); while (true) { val n = input.read(chunk); if (n < 0) break; hash.update(chunk,0,n) } }
                    require(hash.digest().joinToString("") { "%02x".format(it) } == entry.getValue("sha256").jsonPrimitive.content)
                }
                XrayEngine.validateGeo(dir.path,name,if (name == "geoip") "ip" else "domain")
            }
            downloadExtras(dir, extras)
            currentCoroutineContext().ensureActive()
            File(dir,"ready").writeText("1")
            generation
        } catch (failure: Exception) { dir.deleteRecursively(); throw failure }
    }
    private suspend fun downloadExtras(dir: File, extras: List<com.qwipvpn.core.GeoSource>) {
        require(extras.size <= 12 && extras.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == extras.size)
        for (extra in extras) {
            extra.validate(); download(extra.url, File(dir,"${extra.name}.dat"))
            XrayEngine.validateGeo(dir.path,extra.name,extra.kind)
        }
    }
    suspend fun ensureBase(generation:String?,extras:List<com.qwipvpn.core.GeoSource>):File=withContext(Dispatchers.IO){
        resolutionLock.withLock{
            extras.forEach(com.qwipvpn.core.GeoSource::validate)
            val base=active(generation)?:active(prepare())?:error("Missing GeoData")
            if(extras.all{File(base,"${it.name}.dat").isFile})return@withLock base
            val key=com.qwipvpn.core.SubscriptionImporter.sha256(base.name+extras.joinToString{it.name+it.url+it.kind})
            val pointer=File(root,"extras-$key")
            active(runCatching{pointer.readText()}.getOrNull())?.let{return@withLock it}
            val next=prepareExtras(base.name,extras);val atomic=android.util.AtomicFile(pointer);val stream=atomic.startWrite()
            try{stream.write(next.toByteArray());atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
            requireNotNull(active(next))
        }
    }
    suspend fun prepareExtras(generation: String?, extras: List<com.qwipvpn.core.GeoSource>): String = withContext(Dispatchers.IO) {
        val baseline=active(generation)?:active(prepare())?:error("Missing GeoData")
        val id=UUID.randomUUID().toString();val dir=File(root,id).apply{mkdirs()}
        try {
            for(name in listOf("geoip","geosite"))for(extension in listOf("dat","json"))File(baseline,"$name.$extension").takeIf(File::isFile)?.copyTo(File(dir,"$name.$extension"))
            downloadExtras(dir,extras);currentCoroutineContext().ensureActive();File(dir,"ready").writeText("1");id
        } catch(failure:Exception){dir.deleteRecursively();throw failure}
    }
    /** Routing downloads are staged and core-validated before an immutable generation is used. */
    suspend fun resolve(generation: String?, routing: com.qwipvpn.core.RoutingProfile?): File = withContext(Dispatchers.IO) {
        resolutionLock.withLock {
            val baseline = active(generation) ?: active(prepare()) ?: error("Missing GeoData")
            if (routing == null || routing.geoIpUrl == null && routing.geoSiteUrl == null) return@withLock baseline
            val key = com.qwipvpn.core.SubscriptionImporter.sha256(listOf(routing.geoIpUrl, routing.geoSiteUrl, baseline.name).joinToString("\n"))
            val pointer = File(root, "routing-$key")
            active(runCatching { pointer.readText() }.getOrNull())?.let { return@withLock it }
            val id = UUID.randomUUID().toString(); val dir = File(root, id).apply { mkdirs() }
            try {
                baseline.listFiles()?.filter { it.extension in setOf("dat","json") && it.nameWithoutExtension !in setOf("geoip","geosite") }?.forEach { it.copyTo(File(dir,it.name)) }
                for ((name, url) in listOf("geoip" to routing.geoIpUrl, "geosite" to routing.geoSiteUrl)) {
                    currentCoroutineContext().ensureActive()
                    if (url == null) File(baseline, "$name.dat").copyTo(File(dir, "$name.dat")) else download(url, File(dir, "$name.dat"))
                    XrayEngine.validateGeo(dir.path, name, if (name == "geoip") "ip" else "domain")
                }
                currentCoroutineContext().ensureActive(); File(dir, "ready").writeText("1")
                val atomic = android.util.AtomicFile(pointer); val stream = atomic.startWrite()
                try { stream.write(id.toByteArray()); atomic.finishWrite(stream) } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
                dir
            } catch (failure: Exception) { dir.deleteRecursively(); throw failure }
        }
    }
    private suspend fun download(text: String, file: File) = coroutineScope {
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = async(Dispatchers.IO) {
            val deadline = System.nanoTime()+60_000_000_000
            var uri = SubscriptionHttp.validateUrl(text)
            for (redirect in 0..8) {
                ensureActive()
                val conn = uri.toURL().openConnection() as HttpURLConnection
                connection.set(conn)
                try {
                    ensureActive()
                    conn.instanceFollowRedirects = false; conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                    val status = conn.responseCode
                    if (status in listOf(301,302,303,307,308)) {
                        require(redirect < 8); uri = SubscriptionHttp.validateUrl(uri.resolve(conn.getHeaderField("Location") ?: error("No redirect")).toString()); continue
                    }
                    require(status == 200 && (conn.contentLengthLong < 0 || conn.contentLengthLong <= 64 * 1024 * 1024))
                    conn.inputStream.use { input -> file.outputStream().use { out ->
                        val bytes = ByteArray(65536); var total = 0
                        while (true) { ensureActive(); require(System.nanoTime() < deadline); val n = input.read(bytes); if (n < 0) break; total += n; require(total <= 64 * 1024 * 1024); out.write(bytes,0,n) }
                    } }
                    return@async
                } finally { connection.compareAndSet(conn,null); conn.disconnect() }
            }
            error("Redirect limit")
        }
        try { withTimeout(61_000) { worker.await() } } finally { connection.getAndSet(null)?.disconnect(); worker.cancel() }
    }
}
