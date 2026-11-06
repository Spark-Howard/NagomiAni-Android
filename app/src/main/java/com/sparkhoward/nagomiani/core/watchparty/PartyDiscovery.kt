package com.sparkhoward.nagomiani.core.watchparty

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 局域网里发现的一个「一起看」房间（房主手机通过 NSD/mDNS 广播） */
data class DiscoveredRoom(
    val serviceName: String,
    val roomCode: String,
    val hostNick: String,
    val host: String,
    val port: Int,
)

/**
 * NSD（mDNS/Bonjour）广播与发现：房主开局注册 `_nagomiparty._tcp.` 服务（TXT 带 房间码/昵称），
 * 同一网络的客人自动列出附近房间、点一下即入房——零输入。注册/发现都走系统 NsdManager，
 * 无需任何权限；resolve 失败的条目自动跳过（mDNS 缓存竞态，下一轮发现会补上）。
 */
class PartyDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _rooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val rooms: StateFlow<List<DiscoveredRoom>> = _rooms.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** 房主：注册服务（service name 带房间码便于人眼核对） */
    fun register(roomCode: String, port: Int, nick: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "$SERVICE_PREFIX $roomCode"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("room", roomCode)
            setAttribute("nick", nick.take(24))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun unregister() {
        registrationListener?.let { listener ->
            runCatching { nsd.unregisterService(listener) }
            registrationListener = null
        }
    }

    /** 客人：开始扫描附近房间（重复调用安全；结果经去重合入 [rooms]） */
    fun startDiscovering() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.startsWith(SERVICE_TYPE_PREFIX) && serviceInfo.serviceName.startsWith(SERVICE_PREFIX)) {
                    resolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _rooms.value = _rooms.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovering() {
        discoveryListener?.let { listener ->
            runCatching { nsd.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
        _rooms.value = emptyList()
    }

    /** resolve 出 host/port 后合入列表；失败的（正在解析/缓存竞态）跳过，等下轮服务发现 */
    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(resolved: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(resolved: NsdServiceInfo) = addResolved(resolved)
        }
        runCatching {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, listener)
        }
    }

    @Suppress("DEPRECATION")
    private fun addResolved(info: NsdServiceInfo) {
        val room = DiscoveredRoom(
            serviceName = info.serviceName,
            roomCode = info.attributes["room"]?.toString(Charsets.UTF_8) ?: info.serviceName.takeLast(6),
            hostNick = info.attributes["nick"]?.toString(Charsets.UTF_8) ?: "房主",
            host = info.host?.hostAddress ?: "",
            port = info.port,
        )
        if (room.host.isBlank()) return
        _rooms.value = (_rooms.value.filterNot { it.serviceName == room.serviceName } + room)
            .sortedBy { it.roomCode }
    }

    fun shutdown() {
        unregister()
        stopDiscovering()
    }

    companion object {
        private const val SERVICE_TYPE = "_nagomiparty._tcp."
        private const val SERVICE_TYPE_PREFIX = "_nagomiparty._tcp"
        private const val SERVICE_PREFIX = "Nagomi一起看"
    }
}
