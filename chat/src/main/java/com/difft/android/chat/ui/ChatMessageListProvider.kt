package com.difft.android.chat.ui

/**
 * 提供ChatMessageListFragment实例的接口
 * 用于解决在不同Activity中获取ChatMessageListFragment的问题
 */
interface ChatMessageListProvider {
    /**
     * 获取当前Activity中的ChatMessageListFragment实例
     * @return ChatMessageListFragment实例，如果不存在则返回null
     */
    fun getChatMessageListFragment(): ChatMessageListFragment?
}
