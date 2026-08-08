package com.difft.android.chat.util

import java.util.LinkedHashMap

class LRUCache<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>() {

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
}
