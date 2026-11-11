package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.Data









internal object ServiceUrlResolver {

    
    
    
    const val SERVICE_NAME_CHAT = "chat"
    const val SERVICE_NAME_CALL = "call"
    const val SERVICE_NAME_FILE_SHARING = "fileSharing"
    const val SERVICE_NAME_GIFS = "gifs"

    data class ResolvedService(
        
        val hosts: List<String>,
        
        val path: String,
    )

    








    fun resolve(data: Data?, name: String): ResolvedService? {
        data ?: return null
        val svc = data.services?.firstOrNull { it.name == name } ?: return null
        val labelToHost = data.domains?.associateBy({ it.label }, { it.domain }) ?: emptyMap()
        val hosts = svc.domains?.mapNotNull { label ->
            labelToHost[label]?.takeIf { it.isNotBlank() }
                ?: run {
                    L.w { "[Net] ServiceUrlResolver unmatched label=$label service=$name" }
                    null
                }
        }.orEmpty()
        if (hosts.isEmpty()) return null
        return ResolvedService(hosts = hosts, path = svc.path.orEmpty())
    }

    








    fun resolvePath(data: Data?, name: String): String? =
        data?.services?.firstOrNull { it.name == name }?.path
}
