package com.difft.android.call.session

import com.difft.android.call.network.NetworkQualityView
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the call session publishes for the UI. Read-only; implemented by [CallSessionUiState].
 * The session never references the ViewModel, the Activity or the UI controller: the UI binds to
 * this interface, not the reverse.
 */
interface CallSessionUiSource {
    /** Active-speaker highlighting is allowed. False on Reconnecting and after the 3 s silence watchdog. */
    val speakingEnabled: StateFlow<Boolean>

    /** A remote participant is sharing the screen. Sole writer: `RoomEventDispatcher`. */
    val isShareScreening: StateFlow<Boolean>

    /** Speech-countdown text. Sole writer: the `TimerManager` countdown tick armed by `RoomEventDispatcher`. */
    val countDownDurationStr: StateFlow<String>

    /** Renderer re-bind generation, bumped when resubscription settles after a reconnect. */
    val reconnectCount: StateFlow<Int>

    /**
     * Room-wide weak-network verdict. Both suppression rules are already applied by
     * [com.difft.android.call.network.NetworkQualityTracker], so the UI must never re-implement
     * them, and must never branch on [NetworkQualityView.suppressed]: the snapshot is already empty
     * when that flag is true. Sole writer: `NetworkQualityCoordinator`, on main.
     */
    val networkQuality: StateFlow<NetworkQualityView>

    /** Group critical-alert flag as last reported by the server for this call's group. */
    val groupCriticalAlert: StateFlow<Boolean>

    /**
     * One-shot UI output. Events fired before the first collector (the first call page) are kept, up
     * to 64, and delivered to it first, in order; after that an event fired with no collector is
     * dropped. Toasts are never kept.
     */
    val events: SharedFlow<CallSessionUiEvent>
}
