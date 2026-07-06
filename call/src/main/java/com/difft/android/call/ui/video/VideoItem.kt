/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.difft.android.call.ui.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * 全量重连"退订 → 重订阅"空窗内，实时 track 短暂为 null；连接已恢复后给重订阅
 * 一个宽限窗口投递新 track，超时仍无则视为真正移除（unpublish/离开）。
 */
private const val RECONNECT_HOLD_GRACE_MS = 5_000L

/**
 * 计算实际用于渲染的 video track，内含"粘性 last track"（仅远端）逻辑。
 *
 * 粘性 last track：全量重连重订阅期 SDK 会先丢掉旧订阅、再投递一个**新的** RemoteVideoTrack 对象。
 * 若直接放任 videoTrack 变 null，调用处的 `if (videoTrack != null)` 门控会卸载整个 VideoRenderer
 * 子树（release → 黑屏，最后一帧丢失）。保留上一个非空 track 让 renderer 持续挂载、EGL surface 冻结
 * 最后一帧；仅在真正移除（关摄像头/离开/通话结束，或连接已恢复后宽限期内新 track 仍未到达）时才放弃。
 *
 * 宽限窗口仅在「刚从 RECONNECTING 恢复」时武装：连接全程稳定（CONNECTED）时远端主动关摄像头/停止
 * 共享属于正常 unpublish，应立即清掉冻结帧，避免残留画面（UX + 隐私）。
 *
 * 本端摄像头为本地采集、与网络无关，不需要冻结：始终用实时 track，避免卡在重连前的采集帧。
 */
@Composable
private fun rememberDisplayVideoTrack(
    participant: Participant,
    sourceType: Track.Source,
    liveVideoTrack: VideoTrack?,
    roomState: Room.State,
    isLocalParticipant: Boolean,
): VideoTrack? {
    var stickyVideoTrack by remember(participant, sourceType) { mutableStateOf<VideoTrack?>(null) }
    var graceArmed by remember(participant, sourceType) { mutableStateOf(false) }
    LaunchedEffect(roomState) {
        if (roomState == Room.State.RECONNECTING) graceArmed = true
    }
    LaunchedEffect(liveVideoTrack, roomState, isLocalParticipant) {
        when {
            liveVideoTrack != null -> {
                stickyVideoTrack = liveVideoTrack
                // 仅在稳定态（CONNECTED）拿到 track 才解除武装。RECONNECTING 时 SDK 可能尚未丢订阅、
                // track 仍非空，此时不能解除，否则后续 track 掉为 null 再恢复 CONNECTED 会误走立即清除
                // 分支，卸载 renderer 产生黑屏闪烁。
                if (roomState == Room.State.CONNECTED) graceArmed = false
            }
            isLocalParticipant -> stickyVideoTrack = null
            roomState == Room.State.CONNECTED && graceArmed -> {
                // 重连恢复后的空窗：宽限期内等待新 track 投递，超时仍无则视为真正移除。
                delay(RECONNECT_HOLD_GRACE_MS)
                stickyVideoTrack = null
                graceArmed = false
            }
            roomState == Room.State.CONNECTED -> {
                // 稳定态下的 null：正常 unpublish，立即清掉冻结帧。
                stickyVideoTrack = null
            }
            // RECONNECTING / CONNECTING：保持冻结的最后一帧，不卸载 renderer
        }
    }
    return if (isLocalParticipant) liveVideoTrack else (liveVideoTrack ?: stickyVideoTrack)
}

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTrackPublications].
 */
@Composable
fun VideoItemTrackSelector(
    coroutineScope: CoroutineScope,
    room: Room,
    participant: Participant,
    sourceType: Track.Source, // Track.Source.SCREEN_SHARE or Track.Source.CAMERA
    modifier: Modifier = Modifier,
    scaleType: ScaleType = ScaleType.Fill,
    viewType: ViewType,
    draggable: Boolean = true,
    reconnectCount: Int = 0,
) {
    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }

    // Find video stream by sourceType to show
    val videoPub by remember(sourceType) { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == sourceType } } }

    val isLocalParticipant = room.localParticipant == participant

    // 使用已采集的快照而非直接读 live mutable state，保持与触发重组的数据源一致。
    val liveVideoTrack = videoTrackMap.firstOrNull { it.first.subscribed && it.first.source == sourceType }?.second as? VideoTrack

    val roomState by room::state.flow.collectAsState(initial = room.state)
    val videoTrack = rememberDisplayVideoTrack(participant, sourceType, liveVideoTrack, roomState, isLocalParticipant)

    var videoMuted by remember { mutableStateOf(false) }
    var cameraFacingFront by remember { mutableStateOf(false) }
    var cameraUnmuteKey by remember { mutableIntStateOf(0) }

    // monitor muted state; on camera mute→unmute transition, bump key to force VideoRenderer recreation
    LaunchedEffect(videoPub) {
        val pub = videoPub ?: return@LaunchedEffect
        var previousMuted = pub.muted
        pub::muted.flow.collect { muted ->
            videoMuted = muted
            if (previousMuted && !muted && sourceType == Track.Source.CAMERA) {
                cameraUnmuteKey++
            }
            previousMuted = muted
        }
    }

    // monitor camera facing for local participant
    LaunchedEffect(participant, videoTrack) {
        if (room.localParticipant == participant && videoTrack as? LocalVideoTrack != null) {
            videoTrack::options.flow.collect { options ->
                cameraFacingFront = options.position == CameraPosition.FRONT
            }
        }
    }

    if (videoTrack != null && (sourceType == Track.Source.CAMERA || !videoMuted)) {
        // 远端：以稳定维度为 key，renderer 跨 track 替换存活，不因新 track 对象或重连销毁重建；
        // track 的 old→new 解绑/重绑由 VideoRenderer 内部 `update` → setupVideoIfNeeded 原地完成。
        // 本端：把 reconnectCount 纳入 key，重连后重建 renderer 以重新拿到本地采集帧（与历史可用
        // 行为一致），避免本端卡在旧采集帧。cameraUnmuteKey 保留：mute→unmute 仍强制重建。
        val localReconnectKey = if (isLocalParticipant) reconnectCount else 0
        key (participant, sourceType, cameraUnmuteKey, localReconnectKey) {
            VideoRenderer(
                coroutineScope = coroutineScope,
                modifier = modifier,
                room = room,
                videoTrack = videoTrack,
                sourceType = sourceType,
                mirror = room.localParticipant == participant && cameraFacingFront,
                scaleType = scaleType,
                viewType = viewType,
                draggable = draggable,
                reconnectGeneration = reconnectCount,
            )
        }
    } else if (sourceType == Track.Source.SCREEN_SHARE) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(132.dp)
                    .height(47.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.tabler_aspect_ratio),
                    contentDescription = "screen_sharing_placeholder",
                    contentScale = ContentScale.None,
                    modifier = Modifier
                        .padding(0.83333.dp)
                        .width(20.dp)
                        .height(20.dp)
                )

                Text(
                    text = ResUtils.getString(R.string.call_screen_sharing_placeholder),
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = DifftTheme.colors.textTertiary,
                    )
                )
            }
        }
    }
}
