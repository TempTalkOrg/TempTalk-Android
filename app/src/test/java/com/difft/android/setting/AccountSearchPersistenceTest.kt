package com.difft.android.setting

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.user.StorageBoundUserManagerImpl
import com.difft.android.base.user.UserData
import com.difft.android.network.BaseResponse
import com.difft.android.setting.repo.AccountMutationRepository
import com.difft.android.setting.repo.AccountMutationResult
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSearchPersistenceTest {
    @get:Rule(order = 0) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule()

    @Test fun `server accepted false persists zero over previous one and survives reload`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "account-search-${System.nanoTime()}.preferences_pb")
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val state = PreferenceDataStoreFactory.create(scope = diskScope, produceFile = { file })
            val auth = object : DataStore<UserAuthData> {
                private val value = MutableStateFlow(UserAuthData.EMPTY)
                override val data = value
                override suspend fun updateData(transform: suspend (UserAuthData) -> UserAuthData): UserAuthData =
                    transform(value.value).also { value.value = it }
            }
            val users = StorageBoundUserManagerImpl(auth, state, context, Gson())
            users.setUserData(UserData(account = "test-user-001", baseAuth = "session", searchByCustomUid = 1), commit = true)
            assertEquals(1, state.data.first()[AppStateKeys.SEARCH_BY_CUSTOM_UID])
            val settings = mockk<SettingRepo>()
            coEvery { settings.setProfile(searchByCustomUid = 0) } returns BaseResponse(1, 0, null, null)
            val repository = AccountMutationRepository(settings, users, mockk(), mockk(), backgroundScope)
            assertIs<AccountMutationResult.Applied>(repository.setSearch(false))
            assertEquals(0, state.data.first()[AppStateKeys.SEARCH_BY_CUSTOM_UID])
            val restarted = StorageBoundUserManagerImpl(auth, state, context, Gson())
            restarted.warmUp()
            assertEquals(0, restarted.getUserData()?.searchByCustomUid)
        } finally { diskScope.cancel(); file.delete() }
    }
}
