package com.difft.android.chat.ui

import android.view.ViewGroup
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.message.ChatMessage

/**
 * 内容绑定器
 *
 * 用于绑定消息的内容部分（文本、图片、语音等）
 * 替代原来的 MessageContentAdapter，更简洁直接
 *
 * 这是一个函数式接口，可以用 lambda 或对象实现
 */
fun interface ContentBinder {
    /**
     * 绑定内容到 contentFrame
     *
     * @param contentFrame 内容容器（已经 inflate 了对应的布局）
     * @param message 消息数据
     * @param contactorCache 联系人缓存实例（页面级）
     */
    fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: MessageContactsCacheUtil)
}
