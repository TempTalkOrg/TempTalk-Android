package com.difft.android.base.activity

import androidx.annotation.MainThread





interface NotificationPopupGate {

    



    @MainThread
    fun isMainActivityHostingRecovery(): Boolean

    



    @MainThread
    fun inPlacePopupBlocker(): String?
}
