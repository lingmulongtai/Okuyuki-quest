package com.okuyuki.quest.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.okuyuki.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NearbyPhone(val name: String, val endpoint: Endpoint)

class PhoneDiscovery(context: Context) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("okuyuki-discovery").apply { setReferenceCounted(false) }
    private val _phones = MutableStateFlow<List<NearbyPhone>>(emptyList())
    val phones = _phones.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var active = false
    private val resolving = java.util.ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) { _error.value = "自動検出できません。スマホのアドレスを入力してください"; close() }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) { synchronized(resolving) { resolving.add(info); resolveNext() } }
        override fun onServiceLost(info: NsdServiceInfo) { _phones.value = _phones.value.filter { it.name != info.serviceName } }
    }
    fun start() {
        if (active) return
        _error.value = null; _phones.value = emptyList(); active = true
        try { lock.acquire(); nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { _error.value = "自動検出できません。スマホのアドレスを入力してください"; close() }
    }
    @Suppress("DEPRECATION") private fun resolveNext() {
        if (busy || !active || resolving.isEmpty()) return
        busy = true
        nsd.resolveService(resolving.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { synchronized(resolving) { busy = false; resolveNext() } }
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (active) Endpoint.parse("${info.host?.hostAddress}:${info.port}")?.let { endpoint ->
                    _phones.value = (_phones.value.filter { it.name != info.serviceName } + NearbyPhone(info.serviceName, endpoint))
                }
                synchronized(resolving) { busy = false; resolveNext() }
            }
        })
    }
    override fun close() {
        if (active) runCatching { nsd.stopServiceDiscovery(listener) }
        active = false
        synchronized(resolving) { resolving.clear(); busy = false }
        if (lock.isHeld) lock.release()
    }
}
