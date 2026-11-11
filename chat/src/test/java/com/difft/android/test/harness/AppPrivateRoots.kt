package com.difft.android.test.harness

import com.difft.android.base.utils.AppPrivateStorage







object AppPrivateRoots {

    
    fun first(): String = all().first()

    @Suppress("UNCHECKED_CAST")
    fun all(): List<String> {
        val field = AppPrivateStorage::class.java
            .getDeclaredField("roots\$delegate")
            .apply { isAccessible = true }
        return (field.get(null) as Lazy<List<String>>).value
    }
}
