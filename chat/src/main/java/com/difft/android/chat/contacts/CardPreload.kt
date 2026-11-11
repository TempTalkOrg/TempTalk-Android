package com.difft.android.chat.contacts

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.contacts.contactsdetail.ContactDetailRepository
import com.difft.android.chat.contacts.contactsdetail.ContactDetailUiState
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardDataSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CardPreloads @Inject constructor() {
    private val entries = LinkedHashMap<String, Any>()

    @Synchronized
    fun put(value: Any): String {
        
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        return UUID.randomUUID().toString().also { entries[it] = value }
    }

    @Synchronized
    fun remove(key: String?): Any? = key?.let(entries::remove)

    inline fun <reified T : Any> take(key: String?): T? = remove(key) as? T

    private companion object {
        const val MAX_ENTRIES = 4
    }
}


class CardPreloader @Inject constructor(
    private val contactDetails: ContactDetailRepository,
    private val groupCards: GroupCardDataSource,
) {
    suspend fun contact(input: ContactDetailContract.Input): ContactDetailUiState = contactDetails.load(input)

    suspend fun group(gid: String): GroupCardDataSource.Snapshot = groupCards.snapshot(gid, refresh = false)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CardPreloadEntryPoint {
    fun cardPreloads(): CardPreloads
    fun cardPreloader(): CardPreloader
}

internal fun Context.cardPreloadEntryPoint(): CardPreloadEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, CardPreloadEntryPoint::class.java)


@VisibleForTesting
internal const val CARD_LOCAL_READ_TIMEOUT_MS = 300L

private const val NANOS_PER_MS = 1_000_000L

private val pendingCards = mutableSetOf<Pair<Int, String>>()









@MainThread
internal fun showCardAfterLocalRead(
    owner: LifecycleOwner,
    manager: FragmentManager,
    tag: String,
    preloads: CardPreloads,
    read: suspend () -> Any,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    show: (preloadKey: String?) -> Unit,
) {
    val pending = System.identityHashCode(manager) to tag
    if (!pendingCards.add(pending)) return
    owner.lifecycleScope.launch {
        try {
            val startedAt = System.nanoTime()
            val value = readWithin(this, CARD_LOCAL_READ_TIMEOUT_MS, read, dispatcher)
            L.i { "[CardPreload] card=$tag ready=${value != null} ms=${(System.nanoTime() - startedAt) / NANOS_PER_MS}" }
            owner.withStarted {
                if (!manager.isStateSaved && !manager.isDestroyed) show(value?.let(preloads::put))
            }
        } finally {
            pendingCards.remove(pending)
        }
    }
}





@VisibleForTesting
internal suspend fun <T : Any> readWithin(
    scope: CoroutineScope,
    timeoutMs: Long,
    read: suspend () -> T,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): T? {
    val result = scope.async(dispatcher) {
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[CardPreload] local_read_failed error=${e.stackTraceToString()}" }
            null
        }
    }
    return withTimeoutOrNull(timeoutMs) { result.await() }.also { if (it == null) result.cancel() }
}
