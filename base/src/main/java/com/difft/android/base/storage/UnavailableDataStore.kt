package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow














internal class UnavailableDataStore<T>(private val empty: T) : DataStore<T> {

    



    override val data: Flow<T> = MutableStateFlow(empty)

    
    override suspend fun updateData(transform: suspend (T) -> T): T = transform(empty)
}
