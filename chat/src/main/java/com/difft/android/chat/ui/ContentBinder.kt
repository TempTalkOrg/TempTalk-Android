package com.difft.android.chat.ui

import android.view.ViewGroup
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.message.ChatMessage

/**
 * 内容绑定器
 *
 * 用于绑定消息的内容部分（文本、图片、语音等）
 * 替代原来的 MessageContentAdapter，更简洁直接
 */
interface ContentBinder {
    /**
     * 绑定内容到 contentFrame
     *
     * @param contentFrame 内容容器（已经 inflate 了对应的布局）
     * @param message 消息数据
     * @param contactorCache 联系人缓存实例（页面级）
     * @param shouldSaveToPhotos 是否自动保存到相册（已计算好的最终值，考虑了会话级和全局设置）
     * @param containerWidth 容器宽度（用于精确计算图片等内容尺寸，0 表示使用 displayMetrics）
     */
    fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int)
}
