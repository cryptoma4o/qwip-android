package com.qwipvpn.core

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.BeforeClass

/** Real local HTTPS, including decompression, redirects, conditional caching and cancellation. */
class SubscriptionNetworkTests {
    private fun source(path: String) = Source(url="https://127.0.0.1:${server.address.port}/$path",etag="\"old\"",modified="Wed, 01 Jan 2025 00:00:00 GMT")
    private fun client() = SubscriptionHttp { uri -> (uri.toURL().openConnection() as HttpsURLConnection).apply { sslSocketFactory=tls.socketFactory } }
    @Test fun notModifiedKeepsBodyAbsentAndSendsValidators() = runBlocking<Unit> {
        server.createContext("/conditional") { exchange ->
            assertEquals("\"old\"",exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.responseHeaders.add("ETag","\"old\""); exchange.sendResponseHeaders(304,-1); exchange.close()
        }
        assertNull(client().fetch(source("conditional")).body)
    }
    @Test fun redirectDoesNotForwardOrPersistWrongResourceValidators() = runBlocking<Unit> {
        server.createContext("/redirect") { e -> e.responseHeaders.add("Location","/resource");e.sendResponseHeaders(302,-1);e.close() }
        server.createContext("/resource") { e ->
            assertNull(e.requestHeaders.getFirst("If-None-Match"));assertNull(e.requestHeaders.getFirst("If-Modified-Since"))
            e.responseHeaders.add("ETag","\"redirect-only\"");val data="fixture".toByteArray();e.sendResponseHeaders(200,data.size.toLong());e.responseBody.use { it.write(data) }
        }
        val response=client().fetch(source("redirect"))
        assertEquals("fixture",response.body);assertNull(response.etag);assertNull(response.modified)
    }
    @Test fun insecureRedirectCannotDowngradeSubscriptionRequest() = runBlocking<Unit> {
        server.createContext("/downgrade") { e -> e.responseHeaders.add("Location","http://127.0.0.1:${server.address.port}/resource");e.sendResponseHeaders(302,-1);e.close() }
        assertFails { client().fetch(source("downgrade")) }
    }
    @Test fun gzipLimitAppliesToDecodedBytes() = runBlocking<Unit> {
        val compressed=ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(SubscriptionImporter.MAX_BYTES+1) { 'x'.code.toByte() }) } }.toByteArray()
        assertTrue(compressed.size<100_000)
        server.createContext("/compressed") { e -> e.responseHeaders.add("Content-Encoding","gzip");e.sendResponseHeaders(200,compressed.size.toLong());e.responseBody.use { it.write(compressed) } }
        assertFails { client().fetch(source("compressed")) }
    }
    @Test fun cancelStalledNetworkReadReturnsPromptly() = runBlocking<Unit> {
        val accepted=CountDownLatch(1);val release=CountDownLatch(1)
        server.createContext("/stall") { e -> accepted.countDown();release.await(10,TimeUnit.SECONDS);runCatching { e.sendResponseHeaders(200,-1);e.close() } }
        val fetch=launch { client().fetch(source("stall"));fail("Canceled request must not publish a response") }
        try {
            assertTrue(withContext(Dispatchers.IO) { accepted.await(5,TimeUnit.SECONDS) })
            withTimeout(1_500) { fetch.cancelAndJoin() }
        } finally { release.countDown();fetch.cancel() }
    }
    @Test fun invalidUtf8IsRejectedInsteadOfChangingProviderConfig() = runBlocking<Unit> {
        server.createContext("/utf8") { e -> e.sendResponseHeaders(200,2);e.responseBody.use { it.write(byteArrayOf(0xc3.toByte(),0x28)) } }
        assertFails { client().fetch(source("utf8")) }
    }
    companion object {
        private lateinit var server: HttpsServer
        private lateinit var tls: SSLContext
        private val workers=Executors.newCachedThreadPool { task -> Thread(task,"QWIP-Test-HTTPS").apply { isDaemon=true } }
        @JvmStatic @BeforeClass fun setup() {
            val directory=Files.createTempDirectory("qwip-network-fixture").toFile();val file=directory.resolve("fixture.p12")
            try {
                val keytool=java.io.File(System.getProperty("java.home"),"bin/keytool").path
                val process=ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","2","-storetype","PKCS12","-keystore",file.path,"-storepass","synthetic-fixture","-keypass","synthetic-fixture","-dname","CN=localhost","-ext","SAN=ip:127.0.0.1","-noprompt").redirectErrorStream(true).start()
                process.inputStream.use { it.readBytes() };check(process.waitFor()==0)
                val password="synthetic-fixture".toCharArray()
                val store=KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it,password) } }
                val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,password) }
                val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                tls=SSLContext.getInstance("TLS").apply { init(keys.keyManagers,trust.trustManagers,null) }
                server=HttpsServer.create(InetSocketAddress("127.0.0.1",0),0).apply { httpsConfigurator=HttpsConfigurator(tls);executor=workers;start() }
            } finally { directory.deleteRecursively() }
        }
        @JvmStatic @AfterClass fun teardown() { server.stop(0);workers.shutdownNow() }
    }
}
