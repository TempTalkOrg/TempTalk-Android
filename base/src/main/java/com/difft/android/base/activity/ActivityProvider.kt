package com.difft.android.base.activity

/**
 * Activity提供者接口，用于解决依赖顺序问题
 * 替代ActivityManner和Class.forName的方式
 */
interface ActivityProvider {
    /**
     * 获取Activity类
     * @param activityType Activity类型
     * @return Activity的Class对象
     */
    fun getActivityClass(activityType: ActivityType): Class<*>
}

/**
 * Activity类型枚举
 */
enum class ActivityType {
    MAIN,
    SEARCH,
    CONTACT_DETAIL,
    L_INCOMING_CALL,
    INDEX,
    CRITICAL_ALERT
}
