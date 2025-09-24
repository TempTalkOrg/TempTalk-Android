package com.difft.android.base.utils

import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.qualifier.User
import com.difft.android.base.user.UserManager
import com.google.gson.Gson
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
interface GlobalHiltEntryPoint {
    val myId: String
        @User.Uid
        get
    val userManager: UserManager
    val environmentHelper: EnvironmentHelper
    val gson: Gson
    val notificationUtil: IMessageNotificationUtil
    val globalConfigsManager: IGlobalConfigsManager
    val activityProvider: ActivityProvider
}