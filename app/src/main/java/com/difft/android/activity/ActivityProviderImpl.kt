package com.difft.android.activity

import com.difft.android.MainActivity
import com.difft.android.IndexActivity
import com.difft.android.search.SearchActivity
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.call.LIncomingCallActivity
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.call.CriticalAlertActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ActivityProvider的实现类
 * 使用Dagger Hilt进行依赖注入，解决依赖顺序问题
 * 直接引用Activity类，避免使用Class.forName
 */
@Singleton
class ActivityProviderImpl @Inject constructor() : ActivityProvider {

    override fun getActivityClass(activityType: ActivityType): Class<*> {
        return when (activityType) {
            ActivityType.MAIN -> MainActivity::class.java
            ActivityType.INDEX -> IndexActivity::class.java
            ActivityType.SEARCH -> SearchActivity::class.java
            ActivityType.CONTACT_DETAIL -> ContactDetailActivity::class.java
            ActivityType.L_INCOMING_CALL -> LIncomingCallActivity::class.java
            ActivityType.CRITICAL_ALERT -> CriticalAlertActivity::class.java
        }
    }
}
