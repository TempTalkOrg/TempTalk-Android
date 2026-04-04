/*
 * Copyright 2023 LiveKit, Inc.
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

package com.difft.android.call.ui

import androidx.compose.ui.layout.LayoutCoordinates
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.VideoSinkVisibility

/**
 * Compose 布局可见性跟踪，用于 adaptive streaming。
 * 基于 LayoutCoordinates 判断是否 attached 且尺寸非零来计算可见性。
 */
open class ComposeVisibility : VideoSinkVisibility() {
    private var coordinates: LayoutCoordinates? = null

    private var lastVisible = isVisible()
    private var lastSize = size()
    override fun isVisible(): Boolean {
        return (coordinates?.isAttached == true &&
            coordinates?.size?.width != 0 &&
            coordinates?.size?.height != 0)
    }

    override fun size(): Track.Dimensions {
        val width = coordinates?.size?.width ?: 0
        val height = coordinates?.size?.height ?: 0
        return Track.Dimensions(width, height)
    }

    // Note, LayoutCoordinates are mutable and may be reused.
    fun onGloballyPositioned(layoutCoordinates: LayoutCoordinates) {
        coordinates = layoutCoordinates
        val visible = isVisible()
        val size = size()

        if (lastVisible != visible || lastSize != size) {
            notifyChanged()
        }

        lastVisible = visible
        lastSize = size
    }

    fun onDispose() {
        if (coordinates == null) {
            return
        }
        coordinates = null
        notifyChanged()
    }
}

/**
 * 屏幕共享专用的可见性实现，始终报告可见。
 *
 * 屏幕共享不需要 adaptive streaming 的可见性管理（用户始终需要看到流），
 * 但仍需通过布局尺寸让服务端选择合适的分辨率。
 *
 * 解决的问题：
 * - RemoteTrackPublication.track setter 在 renderer attach 前调用 handleVisibilityChanged(false)
 *   导致 disabled=true 被发送给服务端
 * - Compose 重组期间 remove/add renderer 导致 sinkVisibilityMap 短暂为空，触发 disabled=true 抖动
 */
class ScreenShareVisibility : ComposeVisibility() {
    override fun isVisible(): Boolean = true
}
