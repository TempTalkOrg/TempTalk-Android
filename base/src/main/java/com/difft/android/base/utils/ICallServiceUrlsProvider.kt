package com.difft.android.base.utils

/**
 * Snapshot accessor for call-service domain names (FQDNs only, never IP addrs).
 *
 * Cross-module bridge so `:network` (which cannot depend on `:call`) can read the
 * domain portion of the cached `getServiceUrlV2` response. Used by
 * `ProxyConfigProvider` to extend the tunnel-host whitelist with the meeting
 * service's primary + fallback domains.
 *
 * Contract:
 *  - Returns domain strings already normalized to lowercase, trimmed, with
 *    trailing dot stripped, and with blanks dropped. Callers may assume each
 *    entry is a valid FQDN candidate without further sanitisation.
 *  - Returns the empty list when no cached call config is available AND the
 *    bundled assets default cannot be parsed (e.g. corrupted APK).
 *  - MUST NOT block on disk I/O on the hot path. Implementations may read the
 *    in-memory snapshot under their own lock, and may lazily parse the bundled
 *    assets default ONCE on cold start (then cache the parsed result) — neither
 *    operation is allowed to be a recurring cost.
 *
 * Implementations are expected to be `@Singleton`.
 */
interface ICallServiceUrlsProvider {
    fun getCachedServiceUrlsDomains(): List<String>
}
