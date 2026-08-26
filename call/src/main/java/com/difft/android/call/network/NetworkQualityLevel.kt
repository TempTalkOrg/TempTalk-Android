package com.difft.android.call.network

/**
 * Weak-network verdict tier. Severity ladder per the cross-platform spec:
 * excellent 0 < good 1 < bad 2 — the hysteresis direction is decided by [severity],
 * not by an "on/off" flag.
 *
 * Only [BAD] is rendered on Android (the design covers the bad tier only). [GOOD] is kept because
 * the hysteresis rules and the shared cross-platform test contract are defined over all three
 * tiers on every platform: `EXCELLENT -> GOOD` also has to survive the worsening delay, and
 * `BAD -> GOOD` must land on GOOD rather than collapsing to EXCELLENT. Dropping the tier here
 * would make the Android verdict diverge from iOS/Desktop on jitter absorption.
 */
enum class NetworkQualityLevel(val severity: Int) {
    EXCELLENT(0),
    GOOD(1),
    BAD(2),
}
