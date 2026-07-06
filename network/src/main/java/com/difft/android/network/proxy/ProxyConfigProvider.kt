package com.difft.android.network.proxy

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import okhttp3.OkHttpClient
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the currently active [ProxyConfig].
 *
 * Read on every connection attempt by [ProxyTunnelSocketFactory] / [ProxyTunnelDns]
 * (via [current]), so enabling/disabling the proxy takes effect at runtime
 * without rebuilding any OkHttp client. Persisted as the raw share-link string
 * and re-parsed on process start.
 *
 * Persisted state (`proxyShareLink` + `proxyEnabled`) lives in `UserAuthData` via
 * [UserManager] — the same encrypted `secure_user.pb` DataStore (Tink AEAD over
 * kotlinx-proto) used for `baseAuth` / `microToken` / identity keys. No bespoke
 * SharedPreferences file or Keystore wrapper: the share-link's TURN
 * `static-auth-secret` (when present) gets the same at-rest protection as
 * everything else in the auth-half DataStore.
 *
 * Mutations via [save] / [clear] also trigger [IConnectionRefresher.reconnectAfterProxyChange]
 * so the IM WebSocket picks up the new routing immediately. Without that, OkHttp
 * keeps the existing TCP+TLS socket alive — a user who toggles "Use proxy" ON
 * would continue to leak IM signaling through direct DNS until the socket dies
 * organically. The trigger fires only when user-visible state actually changes
 * (i.e. not on no-op saves and not on the cold-start refresh path).
 */
@Singleton
class ProxyConfigProvider @Inject constructor(
    private val userManager: UserManager,
    private val globalConfigsManagerLazy: dagger.Lazy<IGlobalConfigsManager>,
    private val connectionRefresherLazy: dagger.Lazy<IConnectionRefresher>,
    /**
     * Registry of proxy-aware HTTP clients (see [ProxyHttpConnectionRegistry]).
     * On a real proxy state change [save] / [clear] evict every registered client's
     * connection pool so HTTP API requests stop reusing the stale tunnel/direct
     * socket — the HTTP counterpart of [IConnectionRefresher.reconnectAfterProxyChange]
     * for the IM WebSocket. Defaulted so existing unit tests construct the provider
     * with the three core collaborators only.
     */
    private val httpConnectionRegistry: ProxyHttpConnectionRegistry = ProxyHttpConnectionRegistry(),
) {
    /**
     * Guards multi-field writes to the share-link / enabled / cached / routingState
     * state so concurrent readers cannot observe a torn snapshot (e.g. [savedLink]
     * updated while [cached] still has the old value). Wraps the write blocks in
     * [refreshFromUserDataIfChanged], [save], and [clear]. Read paths use the cheap
     * volatile dirty-check at the top of [refreshFromUserDataIfChanged]; the lock is
     * only acquired on a change, so it's uncontended in the steady state.
     *
     * Separate from [tunnelHostsLock] — that one protects the derivation
     * of the tunnel-host snapshot ([tunnelSnapshot]), this one protects the proxy
     * share-link/enabled state. They MUST stay separate; merging them would couple
     * a hot read path (tunnel-host lookup on every connection) to share-link
     * writes. [tunnelHostsLock] is never acquired from inside [refreshLock]
     * (verified by inspection — neither [save], [clear], nor
     * [refreshFromUserDataIfChanged] calls [tunnelHosts] or [shouldTunnel]).
     */
    private val refreshLock = Any()

    /**
     * Cached tunnel-host whitelist snapshot, keyed on the identity of the source
     * domain lists it was derived from. The whitelist is the union of the proxy
     * chat domains ∪ proxy call domains, both read from `proxy.tunnelDomains`
     * via [IGlobalConfigsManager] (live-preferred + embedded fallback).
     *
     * [IGlobalConfigsManager.getProxyTunnelChatDomains] /
     * [IGlobalConfigsManager.getProxyTunnelCallDomains] return the SAME (`===`)
     * list instances while the underlying config is unchanged, and DIFFERENT
     * instances after a live config refresh. So [tunnelHosts] compares both
     * source lists by reference on the hot path: same refs → return the cached
     * [TunnelSnapshot.set] lock-free; changed refs → recompute the union under
     * [tunnelHostsLock]. This makes a LIVE update to `proxy.tunnelDomains`
     * propagate to the whitelist without an app restart, while keeping the
     * steady-state [shouldTunnel] read allocation-free.
     *
     * `null` until first computed; an EMPTY union is NOT cached so the
     * fail-closed self-heal in [tunnelHosts] still applies. Published via
     * `@Volatile`.
     */
    @Volatile
    private var tunnelSnapshot: TunnelSnapshot? = null
    private val tunnelHostsLock = Any()

    /** Cached whitelist + the source list refs it was derived from (see [tunnelSnapshot]). */
    private class TunnelSnapshot(
        val chat: List<String>,
        val call: List<String>,
        val set: Set<String>,
    )

    /** Raw share link the user entered, persisted even while the proxy is OFF. */
    @Volatile
    private var savedLink: String? = userManager.getUserData()?.proxyShareLink

    /** User's on/off intent, independent of whether [savedLink] is valid. */
    @Volatile
    private var enabledFlag: Boolean = userManager.getUserData()?.proxyEnabled ?: false

    /**
     * User's "Protect IP address in calls" intent. Gated by [enabledFlag]: only
     * meaningful while the proxy is active. When OFF the call/meeting plane routes
     * DIRECT (real IP exposed to the SFU) even though the IM plane still tunnels;
     * when ON the call plane joins the tunnel (see [currentForCall]). Independent of
     * whether [savedLink] parses — the settings UI keeps the toggle's value while
     * the proxy is off and surfaces it again when re-enabled.
     */
    @Volatile
    private var protectCallIpFlag: Boolean = userManager.getUserData()?.proxyProtectCallIp ?: false

    /** Active config = parse([savedLink]) only when [enabledFlag]; else null. */
    @Volatile
    private var cached: ProxyConfig? = recompute()

    /**
     * The proxy IPs that [ProxyTunnelDns.lookup] resolved for tunneled origins.
     * Published by the DNS layer and read by [TlsTunnelSocket] so the tunnel/direct
     * decision uses the SAME resolution OkHttp built the route from — instead of
     * re-resolving `config.host` at connect time, which could disagree (DNS
     * round-robin / TTL) and fail open by sending the inner SNI direct without the
     * outer pinned TLS.
     *
     * This is a UNION that only grows within a proxy config, never an overwrite:
     * under concurrent tunneled connections, a `lookup` for origin B must not evict
     * the proxy IP that origin A's already-built route will `connect` to. Overwriting
     * could drop A's IP between its `lookup` and `connect` (DNS round-robin hands the
     * two lookups different subsets), flipping A to the direct branch — the exact
     * fail-open this snapshot exists to prevent. It is reset on config change (see
     * [resetResolvedProxyAddresses]) so a decommissioned proxy's IPs don't linger.
     */
    @Volatile
    var resolvedProxyAddresses: Set<InetAddress> = emptySet()
        private set

    private val resolvedAddressesLock = Any()

    /** DNS layer publishes the proxy IPs it just handed OkHttp for a tunneled origin. */
    internal fun publishResolvedProxyAddresses(addresses: Set<InetAddress>) {
        if (addresses.isEmpty()) return
        // Atomic union: a lock-free read-modify-write would lose a concurrent
        // publisher's additions, reintroducing the fail-open this guards against.
        synchronized(resolvedAddressesLock) {
            resolvedProxyAddresses = resolvedProxyAddresses + addresses
        }
    }

    /** Drops accumulated proxy IPs when the proxy config changes (link/enabled). */
    private fun resetResolvedProxyAddresses() {
        synchronized(resolvedAddressesLock) { resolvedProxyAddresses = emptySet() }
    }

    init {
        routingState = buildRoutingState()
        // [tunnelSnapshot] is derived lazily on the first [shouldTunnel] call
        // (proxy-active path only), so construction stays cheap and off the
        // config-read path. It re-derives automatically when the source
        // `proxy.tunnelDomains` lists change reference after a live config refresh.
    }

    /**
     * Active proxy config, or null when the proxy is disabled. Hot path — keep cheap.
     *
     * Also, self-refreshes from [UserManager] when the live `UserData.proxyShareLink`
     * differs from the cached [savedLink]. This handles the cold-start ordering
     * where [ProxyConfigProvider] is constructed before `StorageBoundUserManager.warmUp()`
     * has populated the in-memory snapshot — in that case both fields start at
     * defaults (null / false) and must be refreshed once warmUp completes.
     * Steady-state writes through [save] / [clear] also update the in-memory
     * snapshot synchronously, so this check is essentially free after warmUp.
     */
    val current: ProxyConfig?
        get() {
            refreshFromUserDataIfChanged()
            return cached
        }

    /**
     * Active proxy config for the CALL/meeting plane, or null when calls must go
     * DIRECT. Returns [current] only while "Protect IP address in calls" is ON;
     * otherwise null even when the proxy is active, so the call signaling socket
     * factory / DNS ([ProxyTunnelSocketFactory] / [ProxyTunnelDns] in call scope)
     * fall back to a direct connection while the IM plane keeps tunneling.
     */
    val currentForCall: ProxyConfig?
        get() {
            refreshFromUserDataIfChanged()
            return if (protectCallIpFlag) cached else null
        }

    /** Whether the proxy is currently active (enabled AND the saved link is valid). */
    val isEnabled: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return cached != null
        }

    /**
     * Whether the proxy is active AND routes the call/meeting plane through the
     * tunnel ("Protect IP address in calls" ON). The call module reads this in
     * place of [isEnabled] so toggling the protection off restores direct calls
     * while the IM plane stays tunneled.
     */
    val isEnabledForCall: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return cached != null && protectCallIpFlag
        }

    /** The user's "Protect IP address in calls" intent (independent of proxy on/off). */
    val isProtectCallIpEnabled: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return protectCallIpFlag
        }

    /** The user's on/off toggle intent (may be true while the saved link is invalid). */
    val isEnabledByUser: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return enabledFlag
        }

    /** The last entered share link, shown in the settings UI even when disabled. */
    val savedShareLink: String?
        get() {
            refreshFromUserDataIfChanged()
            return savedLink
        }

    /**
     * Re-reads `UserData.proxyShareLink` / `UserData.proxyEnabled` from [userManager]
     * and refreshes the [savedLink] / [enabledFlag] / [cached] / [routingState]
     * fields when either differs from what we cached. Single volatile load +
     * nullable-string compare + boolean compare on the hot path; only re-parses
     * the share link when the source string actually changed.
     */
    private fun refreshFromUserDataIfChanged() {
        val ud = userManager.getUserData() ?: return
        val liveLink = ud.proxyShareLink
        val liveEnabled = ud.proxyEnabled
        val liveProtectCall = ud.proxyProtectCallIp
        // Cheap volatile dirty-check before taking the lock. Steady-state callers
        // (post-save / post-warm-up) see savedLink/enabledFlag already match and
        // bail without contending on refreshLock.
        if (liveLink == savedLink && liveEnabled == enabledFlag && liveProtectCall == protectCallIpFlag) return
        synchronized(refreshLock) {
            // Re-check inside the lock — another caller may have raced ahead and
            // published the same update. Without the re-check, two concurrent
            // refreshes could each compute recompute() and race on the final
            // field write order (bounded, but messy).
            if (liveLink == savedLink && liveEnabled == enabledFlag && liveProtectCall == protectCallIpFlag) return
            savedLink = liveLink
            enabledFlag = liveEnabled
            protectCallIpFlag = liveProtectCall
            cached = recompute()
            routingState = buildRoutingState()
        }
    }

    /**
     * Registers a proxy-aware HTTP client so its OkHttp connection pool is evicted
     * on the next proxy state change. Called by each [com.difft.android.network.ChativeHttpClient]
     * at construction — they already hold this provider, so no extra DI wiring is
     * needed at the 7 client construction sites.
     */
    fun registerHttpClient(client: OkHttpClient) = httpConnectionRegistry.register(client)

    /**
     * Persists the share link and the on/off intent together (the Save action of
     * the settings screen). Returns true when the resulting state is consistent —
     * i.e. when disabled, or when enabled with a parseable link. Returns false
     * (and does NOT enable routing) when [enabled] is true but [shareLink] is
     * invalid, so the UI can surface an error while keeping the typed text.
     */
    fun save(shareLink: String, enabled: Boolean): Boolean {
        val link = shareLink.trim()
        val parsed = ProxyConfig.parse(link)
        if (enabled && parsed == null) {
            L.w { "[Proxy] save rejected: enabled but invalid share link (previous link preserved)" }
            return false  // Do NOT mutate savedLink or persist anything.
        }
        val newLink = link.ifBlank { null }
        // After the early-return above, `enabled=true` implies `parsed != null`;
        // when `enabled=false` the parse result is irrelevant. So `newEnabled = enabled`.
        val newEnabled = enabled
        // Snapshot old state BEFORE mutation so we can decide whether the
        // user-visible routing actually changed (and therefore whether the IM
        // WS needs a forced reconnect). Read outside the lock — these are
        // volatile fields; we only need a point-in-time read, and a concurrent
        // writer would have its own state-change decision.
        val oldLink = savedLink
        val oldEnabled = enabledFlag
        // Multi-volatile write block + UserManager update guarded by refreshLock.
        // CRITICAL: userManager.update MUST live inside this lock. Without it, a
        // reader hitting refreshFromUserDataIfChanged() between the lock release
        // and the userManager.update in-memory commit would observe live UserData
        // == OLD while our @Volatile cache == NEW, then enter the lock and write
        // OLD back — silently reverting this save. UserManager has no inverse
        // dependency on ProxyConfigProvider, so the lock ordering
        // refreshLock → UserManager-internal-lock is acyclic.
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
        // Fire the WS reconnect ONLY when the user-visible config actually changed,
        // to avoid pointlessly dropping connections on no-op saves (e.g. settings
        // screen "Save" tapped without edits). Fire-and-forget — never block the
        // caller, and never propagate a failure out of the refresher.
        if (oldLink != newLink || oldEnabled != newEnabled) {
            // Proxy target may have changed — drop stale resolved IPs so the next
            // lookup repopulates against the new host.
            resetResolvedProxyAddresses()
            // Drop pooled HTTP keep-alive connections so API requests stop reusing
            // the stale tunnel/direct socket (the WS equivalent is the refresher below).
            httpConnectionRegistry.evictAll()
            runCatching { connectionRefresherLazy.get().reconnectAfterProxyChange() }
                .onFailure { L.w { "[Proxy] connection refresher failed after save: ${it.message}" } }
        }
        return true
    }

    /**
     * Flips the on/off toggle using the already-saved link. Returns false when
     * enabling but no valid link is saved.
     */
    fun setEnabled(enabled: Boolean): Boolean = save(savedLink.orEmpty(), enabled)

    /**
     * Persists the "Protect IP address in calls" intent and refreshes [routingState]
     * so the call plane's routing decision ([currentForCall] / [isProxyActiveForCall])
     * picks it up on the next call connection. Independent of the IM plane: the IM
     * WebSocket / HTTP clients are NOT reconnected here — only call connections (built
     * fresh per session) read this flag, and the settings screen blocks the toggle
     * during an active call, so there is no live call socket to evict.
     */
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

    /**
     * Applies a `ytp://config?d=...` share link and enables the proxy. Returns
     * true when parsed and activated, false when the link is invalid.
     */
    @Suppress("unused") // public API for programmatic share-link application
    fun applyFromShareLink(shareLink: String): Boolean = save(shareLink, enabled = true)

    /** Disables the proxy and clears ALL persisted state (link + flag). */
    fun clear() {
        // Snapshot pre-clear state so we can skip the WS reconnect when there
        // was nothing to clear (e.g. clear() called twice in a row, or before
        // any save() ever happened — no routing changed, no socket to drop).
        val hadState = savedLink != null || enabledFlag
        // Same lock as save() / refreshFromUserDataIfChanged(). userManager.update
        // is INSIDE the lock for the same reason documented in save(): prevent
        // a reader from observing stale UserData and reverting the cleared cache.
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

    /**
     * Whether `host` is in the tunnel-host whitelist (lock-free read of an
     * immutable [Set] after the one-time lazy derivation).
     *
     * Match semantics: `h == entry || h.endsWith(".$entry")` over a normalized
     * (lowercase, [String.trimEnd] with a trailing dot) form of `host`. Per-FQDN —
     * mirrors the server relay's per-FQDN `ssl_preread` whitelist. The whitelist
     * is the union of the proxy chat domains and the proxy call domains, both from
     * `proxy.tunnelDomains` (live-preferred + embedded fallback, see [tunnelHosts]).
     *
     * Fail-closed: if the derivation yields an EMPTY whitelist (the chat
     * dimension is missing — see [tunnelHosts]), tunnel EVERY host instead of
     * letting it fall through to a direct (IP-leaking) connection. This is only
     * reached while the proxy is active, so over-tunnelling fails safe — a host
     * the relay does not accept just fails to connect, it never leaks the real IP.
     */
    fun shouldTunnel(host: String): Boolean {
        val snapshot = tunnelHosts()
        if (snapshot.isEmpty()) return true
        val h = host.lowercase().trimEnd('.')
        return snapshot.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Returns the tunnel-host whitelist (proxy chat domains ∪ proxy call domains).
     * Reads both source lists from [IGlobalConfigsManager] (`proxy.tunnelDomains`,
     * live-preferred + embedded fallback, each cached so the read is O(1)) and
     * compares them by reference against [tunnelSnapshot]: unchanged refs return
     * the cached set lock-free; changed refs recompute the union under
     * [tunnelHostsLock] (double-checked). A live config refresh swaps the source
     * list instances, so the whitelist re-derives without an app restart.
     *
     * Each source read is guarded by `runCatching` so a fault in one provider
     * cannot mask the other's contribution.
     *
     * An EMPTY result is NOT cached: it would otherwise pin the whitelist to
     * empty until the next config change. While empty, [shouldTunnel] fails
     * closed (tunnels everything) so privacy holds regardless of whether the
     * source recovers.
     */
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
            // Privacy anchor on the CHAT dimension: UrlManager.getBestHost /
            // getAllHostsRanked only fall back to the non-whitelisted
            // `protocol.defaultHost` when the proxy chat domain set is EMPTY (when
            // it is non-empty they always return one of its members). A call-only
            // partial whitelist would leave that fallback host outside the set, so
            // it would route DIRECT and leak the real IP. Treat a missing chat
            // dimension as an underivable whitelist (empty) so [shouldTunnel] fails
            // closed and tunnels everything, including the fallback host.
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

    /** Builds the published [RoutingState] from the current cached config + protect flag. */
    private fun buildRoutingState(): RoutingState = RoutingState(
        active = cached != null,
        quic = cached?.quicEnabled == true,
        protectCall = protectCallIpFlag,
    )

    companion object {
        /**
         * Immutable (active, quic) pair published as ONE `@Volatile` reference so a
         * lock-free reader can never observe a torn combination (e.g. active=true
         * while quic is still the stale value). The two flags were previously two
         * separate volatiles written non-atomically inside [refreshLock]; readers
         * don't take the lock, so the pair could tear. The torn read failed safe
         * (forced WSS), but a single-store publish removes the hazard outright.
         *
         * NOTE: atomicity holds only for a SINGLE read of [routingState]. A caller
         * that needs both flags for one decision must read the reference once, not
         * call [isProxyActive] and [isProxyQuicEnabled] separately.
         */
        private data class RoutingState(val active: Boolean, val quic: Boolean, val protectCall: Boolean)

        /**
         * Process-level, DI-free mirror of [isEnabled] + `current?.quicEnabled`. Lets
         * modules that cannot inject this @Singleton (e.g. the call engine deciding to
         * force WSS over QUIC — see the self-hosted-proxy design §9) cheaply read the
         * proxy state. Kept in sync with [current] on init / apply / clear.
         */
        @Volatile
        private var routingState: RoutingState = RoutingState(active = false, quic = false, protectCall = false)

        /** Whether the proxy is active for routing (enabled AND saved link valid). */
        val isProxyActive: Boolean
            get() = routingState.active

        /**
         * Whether the CALL/meeting plane routes through the proxy: proxy active AND
         * "Protect IP address in calls" ON. The call module gates its proxy logic on
         * this (instead of [isProxyActive]) so the user can keep the IM plane tunneled
         * while letting calls connect directly. Reads [routingState] EXACTLY ONCE so
         * the (active, protectCall) pair is evaluated atomically.
         */
        val isProxyActiveForCall: Boolean
            get() = routingState.let { it.active && it.protectCall }

        /**
         * QUIC-over-proxy is usable for calls: proxy active for calls AND the relay
         * advertises a QUIC relay (`q`). Call-plane counterpart of [isProxyQuicEnabled].
         */
        val isProxyForCallQuicEnabled: Boolean
            get() = routingState.let { it.active && it.protectCall && it.quic }

        /**
         * The call plane is proxied but the relay advertises NO QUIC relay — QUIC
         * signaling must be forced off for calls. Call-plane counterpart of
         * [isProxyActiveWithoutQuic]; single atomic read of [routingState].
         */
        val isProxyForCallActiveWithoutQuic: Boolean
            get() = routingState.let { it.active && it.protectCall && !it.quic }

        /**
         * Mirror of `current?.quicEnabled` (share-code `q`). True only when proxy is
         * active AND the operator runs a MASQUE-lite QUIC relay, so the call engine
         * may keep QUIC signaling on (tunneled). See design §9.6.
         */
        val isProxyQuicEnabled: Boolean
            get() = routingState.quic

        /**
         * True when the proxy is active but advertises NO QUIC relay — i.e. QUIC
         * signaling must be forced off (the tunnel is TCP-only, so QUIC/UDP would
         * hit a dead path). Reads [routingState] EXACTLY ONCE so the (active, quic)
         * pair is evaluated atomically: callers that need both flags for a single
         * decision must use this instead of combining [isProxyActive] and
         * [isProxyQuicEnabled], whose separate loads can straddle a state change.
         */
        val isProxyActiveWithoutQuic: Boolean
            get() = routingState.let { it.active && !it.quic }
    }
}
