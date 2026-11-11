package com.difft.android.base.storage.user

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


internal class OrderedUserWrites(
    private val auth: DataStore<UserAuthData>,
    private val state: DataStore<Preferences>,
    private val dispatcher: CoroutineDispatcher,
) {
    
    private var tail: Job? = null

    fun enqueue(block: suspend () -> Unit): Job {
        val predecessor = tail
        return appScope.launch(dispatcher) {
            predecessor?.join()
            block()
        }.also { tail = it }
    }

    fun enqueueStrict(snapshot: UserData, validSession: () -> Boolean): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        enqueue {
            try {
                check(validSession()) { "Account changed before persistence" }
                
                val projection = UserDataFieldRouter.snapshot(snapshot)
                projection.newAuth?.let { candidate ->
                    auth.updateData { disk -> candidate.copy(migrationV1Completed = disk.migrationV1Completed) }
                }
                state.edit { UserDataFieldRouter.applyAppStateChanges(it, projection.appStateChanges) }
                check(validSession()) { "Account changed during persistence" }
                completion.complete(Unit)
            } catch (e: Exception) { completion.completeExceptionally(e) }
        }
        return completion
    }
}
