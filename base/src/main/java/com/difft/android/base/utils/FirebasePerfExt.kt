package com.difft.android.base.utils







inline fun <T> tracedPerf(name: String, attrs: Map<String, Long> = emptyMap(), block: () -> T): T {
    return block()
}
