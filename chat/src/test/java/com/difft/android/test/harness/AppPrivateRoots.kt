package com.difft.android.test.harness

import com.difft.android.base.utils.AppPrivateStorage

/**
 * The app-private storage roots that [AppPrivateStorage] is actually comparing against right now.
 *
 * Production caches those roots for the process lifetime, which is correct on a device — an app's
 * data dir cannot move while it runs. Robolectric, however, hands every test method its own temp
 * data dir, so the roots captured by whichever test called the predicate first no longer match
 * `context.dataDir` in any later method. Building an "app-private" fixture path from `filesDir`
 * therefore yields a path the predicate rejects, in a way that depends on test execution order.
 *
 * Reading the cached value (rather than resetting it, which a `static final` field does not allow)
 * makes those tests order-independent: a path built under [first] is app-private no matter which
 * method won the race to initialise the cache.
 */
object AppPrivateRoots {

    /** The first cached root, forcing initialisation from the current application if still unset. */
    fun first(): String = all().first()

    @Suppress("UNCHECKED_CAST")
    fun all(): List<String> {
        val field = AppPrivateStorage::class.java
            .getDeclaredField("roots\$delegate")
            .apply { isAccessible = true }
        return (field.get(null) as Lazy<List<String>>).value
    }
}
