package com.qwipvpn.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.qwipvpn.core.ServerProbes
import libXray.DialerController
import java.io.IOException
import java.net.InetSocketAddress

/** Probe sockets use the physical network, never an already running VPN or a process-wide binding. */
class ProbeNetwork(context:Context) {
    private val networks=context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var bestPhysical:Network?=null
    private val callback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network){bestPhysical=network}
        override fun onLost(network:Network){if(bestPhysical==network)bestPhysical=null}
    }
    init{
        // One observer owned by the Application, not by individual screens or probe requests.
        if(Build.VERSION.SDK_INT>=31)networks.registerBestMatchingNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback,Handler(Looper.getMainLooper()))
    }
    private fun usable(network:Network)=networks.getNetworkCapabilities(network)?.let{
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }==true
    fun current():Network {
        val active=networks.activeNetwork
        if(active!=null){
            if(usable(active))return active
        }
        bestPhysical?.takeIf(::usable)?.let{return it}
        // Until the first callback, and on Android 9–11, prefer a validated physical network.
        return networks.allNetworks.filter(::usable).sortedByDescending{network->
            val c=networks.getNetworkCapabilities(network)
            (if(c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)100 else 0)+
                (if(c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true)30 else if(c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)20 else 0)
        }.firstOrNull()?:throw IOException("No physical network for probe")
    }
    suspend fun tcp(host:String,port:Int,timeout:Int):Int? {
        val network=current()
        return ServerProbes.tcpLatency(host,port,timeout,socketFactory={network.socketFactory.createSocket()},
            resolve={name,value->InetSocketAddress(network.getByName(name),value)})
    }
    fun dns(network:Network):String {
        val address=networks.getLinkProperties(network)?.dnsServers?.firstOrNull()?.hostAddress?:throw IOException("Physical network has no DNS")
        return if(address.contains(':'))"[$address]:53"else"$address:53"
    }
    fun controller(network:Network)=object:DialerController {
        override fun protectFd(fd:Long):Boolean {
            if(fd !in 0..Int.MAX_VALUE.toLong())return false
            return try{
                // bindSocket marks the shared socket; close only our duplicate descriptor.
                ParcelFileDescriptor.fromFd(fd.toInt()).use{network.bindSocket(it.fileDescriptor)}
                true
            }catch(_:Exception){false}
        }
    }
}
