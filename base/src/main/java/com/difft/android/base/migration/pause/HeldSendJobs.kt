package com.difft.android.base.migration.pause








fun interface HeldSendJobs {
    



    suspend fun dropAll(reason: String, keepLogin: String?)
}
