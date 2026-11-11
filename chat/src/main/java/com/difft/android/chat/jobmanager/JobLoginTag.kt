package com.difft.android.chat.jobmanager






fun interface JobLoginTag {

    
    fun current(): String?

    



    fun loaded(): Boolean = true

    



    suspend fun awaitFirstLoad(): String? = current()

    companion object {
        
        val NONE: JobLoginTag = JobLoginTag { null }
    }
}
