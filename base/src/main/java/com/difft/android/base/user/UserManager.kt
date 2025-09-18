package com.difft.android.base.user

interface UserManager {
    fun setUserData(userData: UserData, commit: Boolean = false)

    fun getUserData(): UserData?

    fun update(commit: Boolean = false, config: UserData.() -> Unit) {
        val userData = getUserData() ?: UserData()

        val copiedUserData = userData.copy()
        copiedUserData.config()

        if (userData == copiedUserData) {
            return
        } else {
            setUserData(copiedUserData, commit)
        }
    }
}