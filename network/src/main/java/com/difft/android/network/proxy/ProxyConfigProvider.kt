package com.difft.android.network.proxy

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ICallServiceUrlsProvider
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val callServiceUrlsProviderLazy: dagger.Lazy<ICallServiceUrlsProvider>,
    private val connectionRefresherLazy: dagger.Lazy<IConnectionRefresher>,
) {
    private val recomputeLock = Any()

    /**
     * Guards multi-volatile writes to the share-link / enabled / cached / activeForRouting
     * state so concurrent readers cannot observe a torn snapshot (e.g. [savedLink]
     * updated while [cached] still has the old value). Wraps the write blocks in
     * [refreshFromUserDataIfChanged], [save], and [clear]. Read paths use the cheap
     * volatile dirty-check at the top of [refreshFromUserDataIfChanged]; the lock is
     * only acquired on a change, so it's uncontended in the steady state.
     *
     * Separate from [recomputeLock] — that one protects the tunnel-host snapshot
     * ([tunnelHostSet]), this one protects the proxy share-link/enabled state.
     * They MUST stay separate; merging them would couple a hot read path
     * (tunnel-host lookup on every connection) to share-link writes. Lock
     * ordering: when both must be held, [refreshLock] is acquired first.
     * [recomputeLock] is never acquired from inside [refreshLock] (verified by
     * inspection — neither [save], [clear], nor [refreshFromUserDataIfChanged]
     * calls [recomputeTunnelHosts] or [shouldTunnel]).
     */
    private val refreshLock = Any()

    /**
     * Immutable snapshot of host suffixes to tunnel. Replaced atomically by
     * [recomputeTunnelHosts]; readers ([shouldTunnel]) do a single volatile
     * load. Initialized to [HARDCODED_BASELINE] so `shouldTunnel` never returns
     * false for the floor hosts even between construction and the first
     * recompute.
     */
    @Volatile
    private var tunnelHostSet: Set<String> = HARDCODED_BASELINE

    /** Raw share link the user entered, persisted even while the proxy is OFF. */
    @Volatile
    private var savedLink: String? = userManager.getUserData()?.proxyShareLink

    /** User's on/off intent, independent of whether [savedLink] is valid. */
    @Volatile
    private var enabledFlag: Boolean = userManager.getUserData()?.proxyEnabled ?: false

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
        activeForRouting = cached != null
        // tunnelHostSet was already initialized to HARDCODED_BASELINE at the field
        // initializer. Log the baseline-only state synchronously to make startup
        // observable, then dispatch the full recompute to appScope/Dispatchers.IO
        // so any potential runBlocking(IO) inside the manager .get() chain cannot
        // block whatever thread instantiated us. The construction thread is not
        // guaranteed to be off the main thread — Hilt may resolve ProxyConfigProvider
        // eagerly from any of the 6 HTTP-client @Provides sites in
        // ChativeHttpClientModule, and StoragePreloader is a runtime assumption,
        // not a compile-time precondition. See design §5.
        L.i { "[Proxy] tunnel hosts initial (baseline only): total=${tunnelHostSet.size}" }
        appScope.launch(Dispatchers.IO) { recomputeTunnelHosts() }
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

    /** Whether the proxy is currently active (enabled AND the saved link is valid). */
    val isEnabled: Boolean
        get() {
            refreshFromUserDataIfChanged()
            return cached != null
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
     * and refreshes the [savedLink] / [enabledFlag] / [cached] / [activeForRouting]
     * fields when either differs from what we cached. Single volatile load +
     * nullable-string compare + boolean compare on the hot path; only re-parses
     * the share link when the source string actually changed.
     */
    private fun refreshFromUserDataIfChanged() {
        val ud = userManager.getUserData() ?: return
        val liveLink = ud.proxyShareLink
        val liveEnabled = ud.proxyEnabled
        // Cheap volatile dirty-check before taking the lock. Steady-state callers
        // (post-save / post-warm-up) see savedLink/enabledFlag already match and
        // bail without contending on refreshLock.
        if (liveLink == savedLink && liveEnabled == enabledFlag) return
        synchronized(refreshLock) {
            // Re-check inside the lock — another caller may have raced ahead and
            // published the same update. Without the re-check, two concurrent
            // refreshes could each compute recompute() and race on the final
            // four-field write order (bounded, but messy).
            if (liveLink == savedLink && liveEnabled == enabledFlag) return
            savedLink = liveLink
            enabledFlag = liveEnabled
            cached = recompute()
            activeForRouting = cached != null
        }
    }

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
            activeForRouting = cached != null
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
            runCatching { connectionRefresherLazy.get().reconnectAfterProxyChange() }
                .onFailure { L.w { "[Proxy] connection refresher failed after save: ${it.message}" } }
        }
        return true
    }

    /**
     * Flips the on/off toggle using the already-saved link. Returns false when
     * enabling but no valid link is saved.
     */
    @Suppress("unused") // public API kept for settings UI toggle paths
    fun setEnabled(enabled: Boolean): Boolean = save(savedLink.orEmpty(), enabled)

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
            cached = null
            activeForRouting = false
            userManager.update {
                proxyShareLink = null
                proxyEnabled = false
            }
        }
        L.i { "[Proxy] cleared" }
        if (hadState) {
            resetResolvedProxyAddresses()
            runCatching { connectionRefresherLazy.get().reconnectAfterProxyChange() }
                .onFailure { L.w { "[Proxy] connection refresher failed after clear: ${it.message}" } }
        }
    }

    /**
     * Whether `host` is in the tunnel-host whitelist (lock-free read of an
     * immutable [Set]).
     *
     * Match semantics: `h == entry || h.endsWith(".$entry")` over a normalized
     * (lowercase, [String.trimEnd] with a trailing dot) form of `host`. Per-FQDN —
     * mirrors the server relay's per-FQDN `ssl_preread` whitelist. The whitelist
     * is the union of self-cert hosts from the global config (`certType=self`),
     * domains from the cached `getServiceUrlV2` response, and a small hardcoded
     * baseline. See [recomputeTunnelHosts].
     */
    fun shouldTunnel(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        val snapshot = tunnelHostSet
        return snapshot.any { h == it || h.endsWith(".$it") }
    }

    /** Push hook — invoked by `GlobalConfigsManager` after a successful refresh. */
    fun onGlobalConfigChanged() = recomputeTunnelHosts()

    /** Push hook — invoked by `CallServiceUrlManager` after `mergeAndPersist()`. */
    fun onCallServiceUrlsChanged() = recomputeTunnelHosts()

    private fun recompute(): ProxyConfig? =
        if (enabledFlag) savedLink?.let { ProxyConfig.parse(it) } else null

    /**
     * Recomputes [tunnelHostSet] from the three sources and replaces the field
     * atomically. Serialized under [recomputeLock] so two concurrent push hooks
     * cannot tear the set-construction sequence; the replacement itself is a
     * single reference assignment under the lock, published via `@Volatile`.
     *
     * Each manager call is guarded by `runCatching` so a fault in one provider
     * (e.g. DataStore I/O exception, assets-parse failure) cannot mask the
     * other provider's contribution to the new set.
     */
    private fun recomputeTunnelHosts() {
        synchronized(recomputeLock) {
            val gc = runCatching { globalConfigsManagerLazy.get().getNewGlobalConfigs() }.getOrNull()
            val callDomains = runCatching {
                callServiceUrlsProviderLazy.get().getCachedServiceUrlsDomains()
            }.getOrElse { emptyList() }

            val globalHosts = extractGlobalSelfCertHosts(gc)
            val newSet = computeTunnelHosts(globalHosts, callDomains, HARDCODED_BASELINE)
            tunnelHostSet = newSet

            L.i {
                "[Proxy] tunnel hosts recomputed: globalSelf=${globalHosts.size} " +
                    "call=${callDomains.size} baseline=${HARDCODED_BASELINE.size} " +
                    "total=${newSet.size}"
            }
        }
    }

    // internal for testing — unit tests in :network can call this directly
    // without going through the manager mocks.
    internal fun extractGlobalSelfCertHosts(gc: NewGlobalConfig?): List<String> {
        val data = gc?.data ?: return emptyList()
        val hostNames = data.hosts.orEmpty()
            .asSequence()
            .filter { it.certType.equals("self", ignoreCase = true) }
            .mapNotNull { it.name }
        val domainNames = data.domains.orEmpty()
            .asSequence()
            .filter { it.certType.equals("self", ignoreCase = true) }
            .mapNotNull { it.domain }
        return (hostNames + domainNames).toList()
    }

    companion object {
        /**
         * Last-resort tunnel hosts when both cached configs AND assets parsing
         * yield nothing. Conservative: retains all five previously-hardcoded
         * suffixes — the bundled `default_global_config.json` does NOT list
         * `chative.online` or `chative.ninja` under `certType=self`, so without
         * these two baseline entries a fresh install before the first successful
         * live `getNewGlobalConfigs` fetch would silently bypass the tunnel for
         * any active `*.chative.online` / `*.chative.ninja` TempTalk origin.
         * Follow-up cleanup task: see design §11.
         */
        private val HARDCODED_BASELINE: Set<String> = setOf(
            "chative.im",
            "temptalk.net",
            "ablivekit.org",
            "chative.online",
            "chative.ninja",
        )

        /**
         * Process-level, DI-free mirror of [isEnabled]. Lets modules that cannot
         * inject this @Singleton (e.g. the call engine deciding to force WSS over
         * QUIC — see the self-hosted-proxy design §9) cheaply read the proxy state.
         * Kept in sync with [current] on init / apply / clear.
         */
        @Volatile
        private var activeForRouting: Boolean = false

        val isProxyActive: Boolean
            get() = activeForRouting
    }
}
