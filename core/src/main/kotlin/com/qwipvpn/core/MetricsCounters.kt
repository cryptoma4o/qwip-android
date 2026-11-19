package com.qwipvpn.core

import kotlinx.serialization.json.*

class MetricsAccess(val port:Int,val username:String,val password:String){
    init{require(port in 1024..65535&&username.matches(Regex("[a-zA-Z0-9_-]{1,64}"))&&password.matches(Regex("[a-zA-Z0-9_-]{16,256}")))}
    override fun toString()="MetricsAccess(port=$port, credentials=redacted)"
}

/** Only the app's TUN inbound is counted; outbound totals would double count traffic. */
object MetricsCounters {
    fun parse(text: String, inboundTag: String): ByteTotals {
        require(text.toByteArray().size <= 1_048_576)
        val counters = Json.parseToJsonElement(text).jsonObject.obj("stats").obj("inbound").obj(inboundTag)
        return ByteTotals(counters.text("uplink").toLongOrNull()?.coerceAtLeast(0) ?: 0, counters.text("downlink").toLongOrNull()?.coerceAtLeast(0) ?: 0)
    }
}
