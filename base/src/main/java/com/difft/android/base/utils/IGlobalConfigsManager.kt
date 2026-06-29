package com.difft.android.base.utils

import android.content.Context
import com.difft.android.base.user.NewGlobalConfig

interface IGlobalConfigsManager {
    fun getAndSaveGlobalConfigs(context: Context)
    fun getNewGlobalConfigs(): NewGlobalConfig?
    fun updateMostUseEmoji(emoji: String)
    fun getMostUseEmojis(): List<String>

    /**
     * Chat-module proxy tunnel domains, read from `data.proxy.tunnelDomains.chat`.
     *
     * Live-preferred with per-dimension embedded fallback: returns the live/disk
     * config's list when non-empty, otherwise the bundled assets default. While
     * the proxy is active these are the ONLY hosts the chat HTTP/WS layer
     * ([com.difft.android.network.UrlManager]) is forced onto, and they form the
     * chat half of the relay `ssl_preread` tunnel whitelist — so URL resolution
     * and the whitelist share a single source of truth. Already normalized
     * (lowercase, trimmed, trailing dot stripped, blanks/dupes dropped). Returns
     * an empty list when neither source carries the block.
     */
    fun getProxyTunnelChatDomains(): List<String>

    /**
     * Call-module proxy tunnel domains, read from `data.proxy.tunnelDomains.call`.
     *
     * Same live-preferred + embedded-fallback and normalization contract as
     * [getProxyTunnelChatDomains]. While the proxy is active these domains are
     * synthesized into the call-service connection config
     * ([com.difft.android.call.manager.CallServiceUrlManager]) and form the call
     * half of the tunnel whitelist.
     */
    fun getProxyTunnelCallDomains(): List<String>
}