package com.difft.android.network







enum class ChatService(
    
    internal val configName: String?,
    
    internal val defaultPath: String,
) {
    CHAT(ServiceUrlResolver.SERVICE_NAME_CHAT, "/chat"),
    CALL(ServiceUrlResolver.SERVICE_NAME_CALL, "/call"),
    FILE_SHARING(ServiceUrlResolver.SERVICE_NAME_FILE_SHARING, "/fileshare"),

    
    DEFAULT(null, ""),
}






internal data class RoutedHost(val host: String, val service: ChatService)
