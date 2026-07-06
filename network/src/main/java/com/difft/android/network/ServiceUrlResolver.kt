package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.Data

/**
 * Stateless resolver from a global-config [Data] snapshot to a service's
 * candidate hosts + path, aligned with iOS/Desktop's `services` + `domains`
 * model (replacing the legacy `srvs` + `hosts(servTo==chat)` routing).
 *
 * Pure function, no DI: callers (UrlManager / DomainSpeedTestCoordinator) pass
 * the [Data] they already hold.
 */
internal object ServiceUrlResolver {

    // Service names matched against `services[].name` in the global config.
    // Single owner of the `services[].name` contract; callers (UrlManager /
    // DomainSpeedTestCoordinator) reference these instead of redeclaring them.
    const val SERVICE_NAME_CHAT = "chat"
    const val SERVICE_NAME_CALL = "call"
    const val SERVICE_NAME_FILE_SHARING = "fileSharing"
    const val SERVICE_NAME_GIFS = "gifs"

    data class ResolvedService(
        // label→domain resolved real hosts, in the service's configured order.
        val hosts: List<String>,
        // service.path, e.g. "/chat"; may be "" (avatar/livekit).
        val path: String,
    )

    /**
     * Finds the [name] service in [Data.services], maps its domain labels to
     * real hosts via the top-level [Data.domains] (`label → domain`) map, and
     * returns the resolved hosts + path, in the service's configured label order.
     * Returns null when data is null, the service is missing, or no host resolves
     * (caller falls back to its end default). An unmatched label is logged and
     * dropped. Duplicate labels in [Data.domains] resolve last-wins (the server
     * contract is that labels are unique, so this does not arise in practice).
     */
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

    /**
     * Path-only resolution: returns the [name] service's configured path WITHOUT
     * requiring its domain labels to resolve to a host. Used by UrlManager, where
     * the host comes separately from the speed-test coordinator — so a
     * server-configured path is honored even when its host labels mismatch, and
     * the label→host map is not built on the URL/WS hot path. Null when data is
     * null or the service is absent; an empty/blank path is returned as-is for the
     * caller to default.
     */
    fun resolvePath(data: Data?, name: String): String? =
        data?.services?.firstOrNull { it.name == name }?.path
}
