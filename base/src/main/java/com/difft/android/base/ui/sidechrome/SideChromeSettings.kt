package com.difft.android.base.ui.sidechrome

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting






enum class NavigationPlacement { DEFAULT, RIGHT }





object SideChromeSettings {

    private const val PREF_NAME = "side_chrome_prefs"
    private const val KEY_PLACEMENT = "placement"

    @Volatile
    private var cached: NavigationPlacement? = null

    @VisibleForTesting
    internal var prefsProvider: (Context) -> SharedPreferences = { it.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    fun placement(context: Context): NavigationPlacement {
        cached?.let { return it }
        val stored = prefsProvider(context).getString(KEY_PLACEMENT, null)
        return (NavigationPlacement.entries.firstOrNull { it.name == stored } ?: NavigationPlacement.RIGHT).also { cached = it }
    }

    
    fun setPlacement(context: Context, placement: NavigationPlacement) {
        prefsProvider(context).edit().putString(KEY_PLACEMENT, placement.name).commit()
        cached = placement
    }

    @VisibleForTesting
    internal fun resetForTest() {
        cached = null
    }
}
