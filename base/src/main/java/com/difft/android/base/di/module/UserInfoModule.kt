package com.difft.android.base.di.module

import com.difft.android.base.qualifier.User
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.storage.user.StorageBoundUserManagerImpl
import com.difft.android.base.user.UserManager
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the user-manager interfaces (issue #725, Task 7).
 *
 * **Binding strategy** — same `@Singleton` instance behind BOTH
 * [UserManager] and [StorageBoundUserManager]:
 *
 *  - [StorageBoundUserManagerImpl] is `@Singleton` with an
 *    `@Inject constructor` (Hilt builds it directly — no `@Provides` needed
 *    for the impl class itself).
 *  - [StorageBindings.bindUserManager] and
 *    [StorageBindings.bindStorageBoundUserManager] both `@Binds` the same
 *    impl behind the two interfaces. Hilt's singleton semantics guarantee
 *    reference identity — both lookups return the same object.
 *
 * `SimpleUserManager` was removed in issue #725 Phase A — the R3
 * corruption-recovery legacy SP read now lives inline in
 * `StorageBoundUserManagerImpl.readLegacyUserDataBlob()`.
 */
@Module
@InstallIn(SingletonComponent::class)
object UserInfoModule {

    @User.Uid
    @Provides
    fun provideUserId(userManager: Lazy<UserManager>): String {
        return userManager.get().getUserData()?.account ?: ""
    }
}

/**
 * Companion module wiring the interface bindings via `@Binds`. Separated from
 * [UserInfoModule] because `@Binds` requires an abstract class — they cannot
 * coexist with `@Provides` static methods on an `object`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindings {

    @Binds
    @Singleton
    abstract fun bindUserManager(impl: StorageBoundUserManagerImpl): UserManager

    @Binds
    @Singleton
    abstract fun bindStorageBoundUserManager(impl: StorageBoundUserManagerImpl): StorageBoundUserManager
}
