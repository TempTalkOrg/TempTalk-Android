package com.difft.android.base.activity

import androidx.annotation.MainThread


fun interface AppUnlockListener {
    @MainThread
    fun onAppUnlocked()
}
