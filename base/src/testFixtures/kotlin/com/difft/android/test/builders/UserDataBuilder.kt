package com.difft.android.test.builders

import com.difft.android.base.user.UserData

fun buildUserData(
    account: String? = "test-user-001",
    password: String? = "test-password",
    baseAuth: String? = "dGVzdC11c2VyLTAwMTp0ZXN0LXBhc3N3b3Jk",
    email: String? = "test@temptalk.org",
    phoneNumber: String? = null,
    customUid: String? = null
): UserData = UserData(
    account = account,
    password = password,
    baseAuth = baseAuth,
    email = email,
    phoneNumber = phoneNumber,
    customUid = customUid
)
