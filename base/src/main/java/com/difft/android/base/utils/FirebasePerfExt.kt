package com.difft.android.base.utils

/**
 * F-Droid build: Firebase Performance is unavailable, so this is a pass-through wrapper.
 *
 * The block runs unchanged and its result is returned; no trace is recorded. Kept as a
 * no-op (rather than removed) so call sites stay identical across distribution branches.
 */
inline fun <T> tracedPerf(name: String, attrs: Map<String, Long> = emptyMap(), block: () -> T): T {
    return block()
}
