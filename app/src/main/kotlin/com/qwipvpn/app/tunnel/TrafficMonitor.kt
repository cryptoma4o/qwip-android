package com.qwipvpn.app.tunnel

import com.qwipvpn.core.ByteTotals
import com.qwipvpn.core.MetricsCounters
import com.qwipvpn.core.MetricsAccess
import java.net.HttpURLConnection
import java.net.URL

object TrafficMonitor {
    fun read(access: MetricsAccess,inbound: String): ByteTotals {
        val connection=URL("http://127.0.0.1/debug/vars").openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress("127.0.0.1",access.port))) as HttpURLConnection
        try {
            connection.connectTimeout=600;connection.readTimeout=600;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Proxy-Authorization","Basic "+java.util.Base64.getEncoder().encodeToString((access.username+":"+access.password).toByteArray()))
            require(connection.responseCode==200)
            val bytes=java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk=ByteArray(8192)
                while(true){val n=input.read(chunk);if(n<0)break;require(bytes.size()+n<=1_048_576);bytes.write(chunk,0,n)}
            }
            return MetricsCounters.parse(bytes.toString("UTF-8"),inbound)
        } finally { connection.disconnect() }
    }
}
