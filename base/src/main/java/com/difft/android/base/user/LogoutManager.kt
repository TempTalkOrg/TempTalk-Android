package com.difft.android.base.user

interface LogoutManager {
    fun doLogout() 
    fun doLogoutWithoutRemoveData() 

    




    fun doMigrationHandoffSignOut()
}