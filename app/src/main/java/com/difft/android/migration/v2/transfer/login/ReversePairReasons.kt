package com.difft.android.migration.v2.transfer.login

import com.difft.android.migration.v2.transfer.failureReason
import com.difft.android.p2p.android.client.PrepareUploadRejectedException









internal object ReversePairReasons {

    




    fun of(e: Throwable): String =
        if (e is PrepareUploadRejectedException) e.reason else failureReason(e)
}
