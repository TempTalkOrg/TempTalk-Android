package com.difft.android.base.user

interface LogoutManager {
    fun doLogout() //退出时删除用户数据（如正常退出或者删除账号）
    fun doLogoutWithoutRemoveData() //退出时不删除用户数据（如账号在其他设备登录）

    /**
     * Local-only sign-out after a same-device credential handoff: same field set and teardown as
     * [doLogoutWithoutRemoveData], without the "you were signed out" notification and without a
     * server call. Chat data, attachments and `app_state` are kept, so the handoff markers survive.
     */
    fun doMigrationHandoffSignOut()
}