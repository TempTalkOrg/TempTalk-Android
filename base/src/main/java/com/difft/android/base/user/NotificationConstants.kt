package com.difft.android.base.user

/**
 * 通知内容显示类型枚举
 * 用于管理通知的显示方式
 */
enum class NotificationContentDisplayType(val value: Int) {
    /**
     * 显示名字和内容
     */
    NAME_AND_CONTENT(0),

    /**
     * 只显示名字
     */
    NAME_ONLY(1),

    /**
     * 没有名字或者内容
     */
    NO_NAME_OR_CONTENT(2);
}

/**
 * 全局通知开关类型枚举
 * 用于管理通知的开关状态
 */
enum class GlobalNotificationType(val value: Int) {
    /**
     * 所有通知
     */
    ALL(0),

    /**
     * 仅@通知
     */
    MENTION(1),

    /**
     * 关闭通知
     */
    OFF(2);
}
