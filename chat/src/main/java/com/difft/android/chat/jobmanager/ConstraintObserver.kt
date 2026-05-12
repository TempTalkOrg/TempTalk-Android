package com.difft.android.chat.jobmanager

interface ConstraintObserver {

    fun register(notifier: Notifier)

    fun interface Notifier {
        fun onConstraintMet(reason: String)
    }
}
