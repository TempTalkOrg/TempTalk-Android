package com.difft.android.chat.gif

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the GIF browse/search panel. Holds the current tab, paged items, and
 * search debounce. Pagination cursors (offset + next) live in an internal data class
 * (no bare vars). PickGif resolves the original gif to a cache Uri and emits SendGif.
 *
 * M1: SEARCH + TRENDING + send only. Selecting FAVORITES / disabled MOOD tabs is a no-op.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class GifPanelViewModel @Inject constructor(
    private val gifRepository: GifRepository,
    private val gifSendUseCase: GifSendUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(GifPanelContract.State())
    val state: StateFlow<GifPanelContract.State> = _state.asStateFlow()

    private val _effect = Channel<GifPanelContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    /** Internal paging cursor; not part of UI State, but still encapsulated (no bare var). */
    private data class Cursor(val offset: Int = 0, val next: String? = null)
    private val cursor = MutableStateFlow(Cursor())

    private val searchQuery = MutableStateFlow("")

    @Volatile
    private var trendingLoaded = false

    private companion object {
        // Fixed search queries backing the mood tabs (no dedicated server category param yet).
        const val MOOD_HAPPY_QUERY = "happy"
        const val MOOD_SAD_QUERY = "sad"
    }

    init {
        // Debounced search (replaces difft's RxJava debounce, GifSearchActivity.kt:74-82).
        // drop(1) skips the initial "" emission so no trending request fires on VM creation
        // (the VM is created with the input fragment, i.e. on conversation open); the first
        // trending load is deferred to onPanelShown().
        viewModelScope.launch {
            searchQuery.debounce(300).distinctUntilChanged().drop(1).collect { reload(it) }
        }
    }

    /**
     * Initial trending load, triggered when the GIF panel is actually shown (not on VM
     * creation / conversation open). Idempotent: only the first show fetches.
     */
    fun onPanelShown() {
        if (trendingLoaded) return
        trendingLoaded = true
        reload(null)
    }

    fun dispatch(intent: GifPanelContract.Intent) {
        when (intent) {
            is GifPanelContract.Intent.SelectTab -> onSelectTab(intent.tab)
            is GifPanelContract.Intent.Search -> {
                _state.update { it.copy(query = intent.query) }
                searchQuery.value = intent.query
            }
            GifPanelContract.Intent.LoadNextPage -> loadNextPage()
            GifPanelContract.Intent.Refresh -> reload(currentQueryOrNull())
            is GifPanelContract.Intent.PickGif -> pickGif(intent.item)
            is GifPanelContract.Intent.FavoriteGif -> favoriteGif(intent.item)
        }
    }

    private fun onSelectTab(tab: GifPanelContract.GifTab) {
        when (tab) {
            GifPanelContract.GifTab.TRENDING -> {
                if (_state.value.currentTab == tab) return
                _state.update { it.copy(currentTab = tab, query = "") }
                searchQuery.value = ""
                reload(null)
            }
            GifPanelContract.GifTab.SEARCH -> {
                _state.update { it.copy(currentTab = tab) }
            }
            // M3: favorites tab swaps content to FavoriteTabContent (driven by FavoriteViewModel).
            GifPanelContract.GifTab.FAVORITES -> {
                _state.update { it.copy(currentTab = tab) }
            }
            // Mood tabs run a fixed inline search ("happy"/"sad") shown in the same grid.
            GifPanelContract.GifTab.MOOD_HAPPY -> selectMood(tab, MOOD_HAPPY_QUERY)
            GifPanelContract.GifTab.MOOD_SAD -> selectMood(tab, MOOD_SAD_QUERY)
        }
    }

    /** Select a mood tab: switch to it and load its fixed search query inline (so load-more keeps
     *  paginating that query). Does not touch [searchQuery] (the search-dialog debounce input). */
    private fun selectMood(tab: GifPanelContract.GifTab, query: String) {
        if (_state.value.currentTab == tab) return
        _state.update { it.copy(currentTab = tab, query = query) }
        reload(query)
    }

    /** Query for the current browsing context, or null for trending. */
    private fun currentQueryOrNull(): String? =
        _state.value.query.ifEmpty { null }

    /** Reset cursors and load the first page for [query] (null = trending). */
    private fun reload(query: String?) {
        cursor.value = Cursor()
        _state.update { it.copy(items = emptyList(), isLoading = true, emptyResult = false) }
        viewModelScope.launch { fetchPage(query, replace = true) }
    }

    private fun loadNextPage() {
        val s = _state.value
        if (s.isLoading || !s.hasMore) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch { fetchPage(currentQueryOrNull(), replace = false) }
    }

    private suspend fun fetchPage(query: String?, replace: Boolean) {
        val c = cursor.value
        try {
            val page = gifRepository.fetch(query, GifRepository.PAGE_LIMIT, c.offset, c.next)
            val newItems = page.items.mapNotNull { GifUiItem.fromGifData(it) }
            cursor.value = Cursor(offset = c.offset + page.pageCount, next = page.next)
            _state.update { prev ->
                // Dedupe by id when appending: GIPHY trending is a live, shifting feed, so
                // offset-paged requests can return the same gif on adjacent page boundaries.
                // Duplicate ids would crash the LazyGrid (key must be unique), so drop repeats.
                val merged = if (replace) {
                    newItems
                } else {
                    val existingIds = prev.items.mapTo(HashSet()) { it.id }
                    prev.items + newItems.filter { existingIds.add(it.id) }
                }
                prev.copy(
                    items = merged,
                    isLoading = false,
                    hasMore = page.hasMore,
                    emptyResult = merged.isEmpty()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GifPanel] fetch failed query=${query?.length ?: 0}chars: ${e.stackTraceToString()}" }
            _state.update { it.copy(isLoading = false, hasMore = false) }
            _effect.trySend(GifPanelContract.Effect.ShowError(e.message ?: ""))
        }
    }

    private fun pickGif(item: GifUiItem) {
        if (item.webpUrl.isEmpty()) {
            _effect.trySend(GifPanelContract.Effect.ShowError(""))
            return
        }
        viewModelScope.launch {
            try {
                val uri = gifSendUseCase.resolveSendable(
                    GifSendInput.FromUrl(item.webpUrl, item.width, item.height)
                )
                _effect.send(GifPanelContract.Effect.SendGif(uri, item.width, item.height))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GifPanel] resolve send failed: ${e.stackTraceToString()}" }
                _effect.trySend(GifPanelContract.Effect.ShowError(e.message ?: ""))
            }
        }
    }

    /**
     * Long-press add-to-favorites (Issue 5): emit the raw item (no pre-download). The host forwards
     * it to FavoriteViewModel as FavoriteSource.FromRemote — the placeholder + toast are instant and
     * the download + trans-store + CAS PUT run app-scoped in the background.
     */
    private fun favoriteGif(item: GifUiItem) {
        if (item.webpUrl.isEmpty()) {
            _effect.trySend(GifPanelContract.Effect.ShowError(""))
            return
        }
        _effect.trySend(
            GifPanelContract.Effect.FavoriteRemote(item.id, item.webpUrl, item.width, item.height)
        )
    }
}
