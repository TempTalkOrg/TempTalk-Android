package com.difft.android.call.network

import android.os.Looper
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.BuildConfig
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.session.CallSessionUiState
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

















internal class NetworkQualityCoordinator(
    private val scope: CoroutineScope,
    private val roomState: StateFlow<Room.State>,
    








    private val mediaSendIssue: StateFlow<MediaSendIssueState>,
    private val localQualityProvider: () -> ConnectionQuality,
    private val remoteQualityProvider: () -> Map<String, ConnectionQuality>,
    private val uiState: CallSessionUiState,
    




    private val nowProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val tracker = NetworkQualityTracker()
    private var stateJob: Job? = null
    private var issueJob: Job? = null
    private var tickJob: Job? = null

    
    private var roomConnected = false
    private var linkRecovering = false

    





    private var lastSuppressed: Boolean? = null

    
    fun start() {
        assertOnMain()
        if (stateJob != null) return
        
        
        roomConnected = roomState.value == Room.State.CONNECTED
        linkRecovering = mediaSendIssue.value == MediaSendIssueState.CONNECTION_RECOVERING
        stateJob = scope.launch(Dispatchers.Main.immediate) {
            roomState.collect { state ->
                roomConnected = state == Room.State.CONNECTED
                onSuppressionInput(state, mediaSendIssue.value)
            }
        }
        issueJob = scope.launch(Dispatchers.Main.immediate) {
            mediaSendIssue.collect { issue ->
                linkRecovering = issue == MediaSendIssueState.CONNECTION_RECOVERING
                onSuppressionInput(roomState.value, issue)
            }
        }
    }

    



    fun stop() {
        assertOnMain()
        stateJob?.cancel()
        stateJob = null
        issueJob?.cancel()
        issueJob = null
        stopTicking()
        lastSuppressed = null
        roomConnected = false
        linkRecovering = false
        tracker.reset()
        publish()
    }

    







    fun onQualityChanged(identity: String?, quality: ConnectionQuality, isLocal: Boolean) {
        assertOnMain()
        val key = if (isLocal) LOCAL_KEY else identity ?: return
        val level = quality.toNetworkQualityLevel()
        val now = nowProvider()
        if (tracker.onQualityChanged(key, level, isLocal, now)) {
            L.i { "[Call] NetworkQuality raw ${if (isLocal) "local" else identity} level=$level" }
        }
        if (tracker.evaluate(now)) publishWithLog()
    }

    
    fun onParticipantLeft(identity: String?) {
        assertOnMain()
        tracker.onParticipantLeft(identity ?: return)
        publish()
    }

    




    private fun onSuppressionInput(state: Room.State, issue: MediaSendIssueState) {
        val suppressed = !roomConnected || linkRecovering
        if (suppressed == lastSuppressed) return
        lastSuppressed = suppressed
        val now = nowProvider()
        tracker.onSuppressedChanged(isSuppressed = suppressed, now = now)
        L.i { "[Call] NetworkQuality suppressed=$suppressed roomState=$state issue=$issue" }
        if (!suppressed) {
            reseed(now)
            startTicking()
        } else {
            
            
            
            stopTicking()
        }
        publish()
    }

    








    private fun reseed(now: Long) {
        tracker.onQualityChanged(
            identity = LOCAL_KEY,
            level = localQualityProvider().toNetworkQualityLevel(),
            isLocal = true,
            now = now,
        )
        
        
        val remotes = remoteQualityProvider()
        remotes.forEach { (identity, quality) ->
            tracker.onQualityChanged(identity, quality.toNetworkQualityLevel(), isLocal = false, now)
        }
        
        
        tracker.retainRemotes(remotes.keys)
        L.i { "[Call] NetworkQuality reseed remotes=${remotes.size}" }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                delay(TICK_INTERVAL_MS)
                
                if (tracker.evaluate(nowProvider())) publishWithLog()
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun publish() = uiState.setNetworkQuality(tracker.view())

    



    private fun publishWithLog() {
        val view = tracker.view()
        uiState.setNetworkQuality(view)
        L.i { "[Call] NetworkQuality published local=${view.local} badRemotes=${view.badRemoteIdentities.size}" }
    }

    private fun assertOnMain() {
        if (!BuildConfig.DEBUG) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "[Call] NetworkQualityCoordinator must be driven from the main dispatcher (tracker is lock-free)"
        }
    }

    companion object {
        
        const val TICK_INTERVAL_MS = 500L

        



        private const val LOCAL_KEY = "__local__"

        
        fun create(
            scope: CoroutineScope,
            room: Room,
            uiState: CallSessionUiState,
            mediaSendIssue: StateFlow<MediaSendIssueState>,
        ) =
            NetworkQualityCoordinator(
                scope = scope,
                roomState = room::state.flow,
                
                
                mediaSendIssue = mediaSendIssue,
                localQualityProvider = { room.localParticipant.connectionQuality },
                remoteQualityProvider = {
                    room.remoteParticipants.values
                        .mapNotNull { participant ->
                            participant.identity?.value?.let { it to participant.connectionQuality }
                        }
                        .toMap()
                },
                uiState = uiState,
            )
    }
}
