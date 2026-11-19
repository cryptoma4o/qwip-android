package com.qwipvpn.app.tunnel

import android.content.*
import android.os.*
import com.qwipvpn.core.*
import com.qwipvpn.app.QWIPApplication
import com.qwipvpn.app.R
import kotlinx.coroutines.flow.*

class TunnelController(private val context: Context) {
    private val diagnostics get() = (context.applicationContext as QWIPApplication).diagnostics
    private val _state = MutableStateFlow(TunnelState())
    val state = _state.asStateFlow()
    private var remote: Messenger? = null
    private var bound = false
    private var stopRequested=false
    private val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == TunnelProtocol.STATE) {
                val incoming=TunnelProtocol.decode(message.data)
                if(stopRequested&&incoming.phase !in listOf(TunnelPhase.DISCONNECTED,TunnelPhase.DISCONNECTING))return
                if(stopRequested&&incoming.phase==TunnelPhase.DISCONNECTED){stopRequested=false;_state.value=incoming;return}
                if (!(incoming.phase==TunnelPhase.DISCONNECTED && _state.value.phase==TunnelPhase.ERROR)) _state.value=incoming
            }
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = Messenger(binder)
            runCatching { remote?.send(Message.obtain(null,TunnelProtocol.REGISTER).apply { replyTo = receiver }) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            diagnostics.record(SupportEventKind.SERVICE_UNAVAILABLE,phase=_state.value.phase)
            remote = null
            _state.value = if (_state.value.phase in listOf(TunnelPhase.DISCONNECTING,TunnelPhase.DISCONNECTED)) TunnelState()
                else _state.value.copy(phase=TunnelPhase.ERROR,error=context.getString(R.string.connect_failed))
        }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name); if (bound) { context.unbindService(this); bound = false }; bind() }
    }
    fun bind() { if (!bound) bound = context.bindService(Intent(context,QWIPVpnService::class.java),connection,Context.BIND_AUTO_CREATE) }
    fun start(requestId: String) {
        stopRequested=false
        bind()
        _state.value = TunnelState(TunnelPhase.CONNECTING)
        try { context.startForegroundService(Intent(context,QWIPVpnService::class.java).setAction(TunnelProtocol.START).putExtra(TunnelProtocol.REQUEST_ID,requestId)) }
        catch (_: Exception) { diagnostics.record(SupportEventKind.SERVICE_UNAVAILABLE); _state.value = TunnelState(TunnelPhase.ERROR,error=context.getString(R.string.connect_failed)); throw IllegalStateException("VPN service unavailable") }
    }
    fun stop() {
        stopRequested=true
        diagnostics.record(SupportEventKind.DISCONNECT_REQUESTED,phase=_state.value.phase,network=diagnostics.network())
        _state.value = _state.value.copy(phase = TunnelPhase.DISCONNECTING,error = null)
        try {
            if (remote != null) remote?.send(Message.obtain(null,TunnelProtocol.DISCONNECT))
            else context.startService(Intent(context,QWIPVpnService::class.java).setAction(TunnelProtocol.STOP))
        } catch (_: Exception) { _state.value = TunnelState(TunnelPhase.ERROR,error=context.getString(R.string.connect_failed)) }
    }
    fun close() {
        runCatching { remote?.send(Message.obtain(null,TunnelProtocol.UNREGISTER).apply { replyTo = receiver }) }
        if (bound) context.unbindService(connection)
        bound = false; remote = null
    }
}
