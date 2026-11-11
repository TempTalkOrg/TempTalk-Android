package com.difft.android.chat.translate.backend

import com.difft.android.chat.translate.ModelId











interface TranslateBackend {

    
    val isSupported: Boolean

    





    suspend fun identifyLanguage(text: String): String?

    





    suspend fun translate(text: String, sourceCode: String, targetCode: String): String

    suspend fun isModelDownloaded(model: ModelId): Boolean

    





    suspend fun downloadModel(model: ModelId)

    



    suspend fun warmUp(sourceCode: String, targetCode: String)

    
    fun releaseClients()
}


class TranslateBackendException(
    val kind: Kind,
    cause: Throwable? = null,
) : Exception("translate backend failed: $kind", cause) {

    enum class Kind {
        NETWORK,
        NO_SPACE,
        DOWNLOAD_MANAGER_DISABLED,

        




        CANCELLED,

        
        UNSUPPORTED,

        OTHER,
    }
}
