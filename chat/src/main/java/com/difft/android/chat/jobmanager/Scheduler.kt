package com.difft.android.chat.jobmanager

interface Scheduler {
    fun schedule(delay: Long, constraints: List<@JvmSuppressWildcards Constraint>)

    




    fun cancelAllExcept(keep: Set<Int>)
}
