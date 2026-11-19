package com.qwipvpn.app.tunnel

import android.os.Bundle
import com.qwipvpn.core.*

object TunnelProtocol {
    const val REGISTER = 1
    const val UNREGISTER = 2
    const val STATE = 3
    const val DISCONNECT = 4
    const val START = "com.qwipvpn.app.START"
    const val STOP = "com.qwipvpn.app.STOP"
    const val AUTO = "com.qwipvpn.app.AUTO"
    const val REQUEST_ID = "request-id"
    fun encode(state: TunnelState) = Bundle().apply {
        putString("phase",state.phase.name); putString("profile",state.profileId)
        putString("title",state.title); putLong("started",state.startedAt); putString("error",state.error)
        putLong("sent",state.sent); putLong("received",state.received); putLong("send-rate",state.sendRate); putLong("receive-rate",state.receiveRate)
    }
    fun decode(bundle: Bundle) = TunnelState(
        runCatching { TunnelPhase.valueOf(bundle.getString("phase") ?: "DISCONNECTED") }.getOrDefault(TunnelPhase.ERROR),
        bundle.getString("profile"),bundle.getString("title").orEmpty(),bundle.getLong("started"),bundle.getString("error"),
        bundle.getLong("sent"),bundle.getLong("received"),bundle.getLong("send-rate"),bundle.getLong("receive-rate"))
}
