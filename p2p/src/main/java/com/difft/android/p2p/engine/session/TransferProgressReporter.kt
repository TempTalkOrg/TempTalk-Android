package com.difft.android.p2p.engine.session






interface TransferProgressReporter {

    
    fun stage(stage: String)

    
    fun recordsImported(total: Long)

    
    fun attachmentBytesLanded(delta: Long)

    




    fun bodySessionId(): String?

    








    fun committing(sessionId: String?): Boolean

    
    object None : TransferProgressReporter {
        override fun stage(stage: String) = Unit
        override fun recordsImported(total: Long) = Unit
        override fun attachmentBytesLanded(delta: Long) = Unit
        override fun bodySessionId(): String? = null
        override fun committing(sessionId: String?) = true
    }
}
