package com.difft.android.network.proxy

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import okhttp3.OkHttpClient
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton









@Singleton
class ProxyConfigProvider @Inject constructor(
    private val userManager: UserManager,
    private val globalConfigsManagerLazy: dagger.Lazy<IGlobalConfigsManager>,
    private val connectionRefresherLazy: dagger.Lazy<IConnectionRefresher>,
    







    private val httpConnectionRegistry: ProxyHttpConnectionRegistry = ProxyHttpConnectionRegistry(),
) {
    





    private val refreshLock = Any()

    






    @Volatile
    private var tunnelSnapshot: TunnelSnapshot? = null
    private val tunnelHostsLock = Any()

    
    private class TunnelSnapshot(
        val chat: List<String>,
        val call: List<String>,
        val set: Set<String>,
    )

    
    @Volatile
    private var savedLink: String? = userManager.getUserData()?.proxyShareLink

    
    @Volatile
    private var enabledFlag: Boolean = userManager.getUserData()?.proxyEnabled ?: false

    







    @Volatile
    private var protectCallIpFlag: Boolean = userManager.getUserData()?.proxyProtectCallIp ?: false

    
    @Volatile
    private var cached: ProxyConfig? = recompute()

    







    @Volatile
    var resolvedProxyAddresses: Set<InetAddress> = emptySet()
        private set

    private val resolvedAddressesLock = Any()

    
    internal fun publishResolvedProxyAddresses(addresses: Set<InetAddress>) {
        if (addresses.isEmpty()) return
        
        
        synchronized(resolvedAddressesLock) {
            resolvedProxyAddresses = resolvedProxyAddresses + addresses
        }
    }

    
    private fun resetResolvedProxyAddresses() {
        synchronized(resolvedAddressesLock) { resolvedProxyAddresses = emptySet() }
    }

    init {
        routingState = buildRoutingState()
        
        
        
        
    }

    










    val current: ProxyConfig?
        get() {
            refreshFromUserDataIfChanged()
            return cached
        }

    






    val currentForCall: ProxyConfig?
        get() {
            refreshFromUserDataIfChanged()
            return if (protectCallIpFlag) cached else null
        }

    
    val isEnabled: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return cached != null
        }

    





    val isEnabledForCall: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return cached != null && protectCallIpFlag
        }

    
    val isProtectCallIpEnabled: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return protectCallIpFlag
        }

    
    val isEnabledByUser: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return enabledFlag
        }

    
    val savedShareLink: String?
        get() {
            refreshFromUserDataIfChanged()
            return savedLink
        }

    






    private fun refreshFromUserDataIfChanged() {
        val ud = userManager.getUserData() ?: return
        val liveLink = ud.proxyShareLink
        val liveEnabled = ud.proxyEnabled
        val liveProtectCall = ud.proxyProtectCallIp
        
        
        
        if (liveLink == savedLink && liveEnabled == enabledFlag && liveProtectCall == protectCallIpFlag) return
        synchronized(refreshLock) {
            
            
            
            
            if (liveLink == savedLink && liveEnabled == enabledFlag && liveProtectCall == protectCallIpFlag) return
            savedLink = liveLink
            enabledFlag = liveEnabled
            protectCallIpFlag = liveProtectCall
            cached = recompute()
            routingState = buildRoutingState()
        }
    }

    
    fun registerHttpClient(client: OkHttpClient) = httpConnectionRegistry.register(client)

    






    fun save(shareLink: String, enabled: Boolean): Boolean {
        val link = shareLink.trim()
        val parsed = ProxyConfig.parse(link)
        if (enabled && parsed == null) {
            L.w { "[Proxy] save rejected: enabled but invalid share link (previous link preserved)" }
            return false  
        }
        val newLink = link.ifBlank { null }
        
        
        val newEnabled = enabled
        
        
        
        
        
        val oldLink = savedLink
        val oldEnabled = enabledFlag
        
        
        
        synchronized(refreshLock) {
            savedLink = newLink
            enabledFlag = newEnabled
            cached = if (newEnabled) parsed else null
            routingState = buildRoutingState()
            userManager.update {
                proxyShareLink = newLink
                proxyEnabled = newEnabled
            }
        }
        L.i { "[Proxy] save enabled=$newEnabled" }
        
        
        
        
        if (oldLink != newLink || oldEnabled != newEnabled) {
            
            
            resetResolvedProxyAddresses()
            
            
            httpConnectionRegistry.evictAll()
            runCatching { connectionRefresherLazy.get().reconnectAfterProxyChange() }
                .onFailure { L.w { "[Proxy] connection refresher failed after save: ${it.message}" } }
        }
        return true
    }

    



    fun setEnabled(enabled: Boolean): Boolean = save(savedLink.orEmpty(), enabled)

    







    fun setProtectCallIp(enabled: Boolean) {
        if (protectCallIpFlag == enabled) return
        synchronized(refreshLock) {
            protectCallIpFlag = enabled
            routingState = buildRoutingState()
            userManager.update {
                proxyProtectCallIp = enabled
            }
        }
        L.i { "[Proxy] protectCallIp=$enabled" }
    }

    



    @Suppress("unused") 
    fun applyFromShareLink(shareLink: String): Boolean = save(shareLink, enabled = true)

    
    fun clear() {
        
        
        
        val hadState = savedLink != null || enabledFlag
        
        
        
        synchronized(refreshLock) {
            savedLink = null
            enabledFlag = false
            protectCallIpFlag = false
            cached = null
            routingState = RoutingState(active = false, quic = false, protectCall = false)
            userManager.update {
                proxyShareLink = null
                proxyEnabled = false
                proxyProtectCallIp = false
            }
        }
        L.i { "[Proxy] cleared" }
        if (hadState) {
            resetResolvedProxyAddresses()
            httpConnectionRegistry.evictAll()
            runCatching { connectionRefresherLazy.get().reconnectAfterProxyChange() }
                .onFailure { L.w { "[Proxy] connection refresher failed after clear: ${it.message}" } }
        }
    }

    





    fun shouldTunnel(host: String): Boolean {
        val snapshot = tunnelHosts()
        if (snapshot.isEmpty()) return true
        val h = host.lowercase().trimEnd('.')
        return snapshot.any { h == it || h.endsWith(".$it") }
    }

    





    private fun tunnelHosts(): Set<String> {
        val gcm = globalConfigsManagerLazy.get()
        val chatDomains = runCatching { gcm.getProxyTunnelChatDomains() }.getOrElse { emptyList() }
        val callDomains = runCatching { gcm.getProxyTunnelCallDomains() }.getOrElse { emptyList() }
        tunnelSnapshot?.let { snap ->
            if (snap.chat === chatDomains && snap.call === callDomains) return snap.set
        }
        return synchronized(tunnelHostsLock) {
            tunnelSnapshot?.let { snap ->
                if (snap.chat === chatDomains && snap.call === callDomains) return@synchronized snap.set
            }
            
            
            
            
            
            
            
            
            val set = if (chatDomains.isEmpty()) {
                emptySet()
            } else {
                computeTunnelHosts(chatDomains, callDomains)
            }
            if (set.isNotEmpty()) tunnelSnapshot = TunnelSnapshot(chatDomains, callDomains, set)
            L.i {
                "[Proxy] tunnel hosts derived: chat=${chatDomains.size} " +
                    "call=${callDomains.size} total=${set.size}"
            }
            set
        }
    }

    private fun recompute(): ProxyConfig? =
        if (enabledFlag) savedLink?.let { ProxyConfig.parse(it) } else null

    
    private fun buildRoutingState(): RoutingState = RoutingState(
        active = cached != null,
        quic = cached?.quicEnabled == true,
        protectCall = protectCallIpFlag,
    )

    companion object {
        




        private data class RoutingState(val active: Boolean, val quic: Boolean, val protectCall: Boolean)

        



        @Volatile
        private var routingState: RoutingState = RoutingState(active = false, quic = false, protectCall = false)

        
        val isProxyActive: Boolean
            get() = routingState.active

        






        val isProxyActiveForCall: Boolean
            get() = routingState.let { it.active && it.protectCall }

        



        val isProxyForCallQuicEnabled: Boolean
            get() = routingState.let { it.active && it.protectCall && it.quic }

        




        val isProxyForCallActiveWithoutQuic: Boolean
            get() = routingState.let { it.active && it.protectCall && !it.quic }

        



        val isProxyQuicEnabled: Boolean
            get() = routingState.quic

        







        val isProxyActiveWithoutQuic: Boolean
            get() = routingState.let { it.active && !it.quic }
    }
}
