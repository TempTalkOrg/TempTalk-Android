package com.difft.android.base.storage.user

import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserManager







interface StorageBoundUserManager : UserManager {

    





    suspend fun commitStrict(expectedAccount: String, change: com.difft.android.base.user.UserData.() -> Unit)


    










    suspend fun updateAuth(block: UserAuthData.() -> UserAuthData)

    












    suspend fun <T> updateAppState(key: Preferences.Key<T>, value: T)

    




    suspend fun warmUp()

    




    suspend fun clearAuthOnly()

    




    suspend fun clearAll()
}
