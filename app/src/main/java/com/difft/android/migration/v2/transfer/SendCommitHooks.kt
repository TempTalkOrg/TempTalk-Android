package com.difft.android.migration.v2.transfer





interface SendCommitHooks {

    
    suspend fun beforeFinalize()

    



    fun onEndMarker() = Unit

    



    fun onAbortAnswer(answer: String?)
}
