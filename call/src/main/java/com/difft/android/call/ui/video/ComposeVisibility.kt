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

package com.difft.android.call.ui.video

import androidx.compose.ui.layout.LayoutCoordinates
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.VideoSinkVisibility






open class ComposeVisibility : VideoSinkVisibility() {
    private var coordinates: LayoutCoordinates? = null
    private var disposed = false

    private var lastVisible = isVisible()
    private var lastSize = size()

    



    var forceHidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lastVisible = isVisible()
            notifyChanged()
        }

    override fun isVisible(): Boolean {
        if (disposed || forceHidden) return false
        val coords = coordinates ?: return true
        return (coords.isAttached &&
            coords.size.width != 0 &&
            coords.size.height != 0)
    }

    override fun size(): Track.Dimensions {
        val width = coordinates?.size?.width ?: 0
        val height = coordinates?.size?.height ?: 0
        return Track.Dimensions(width, height)
    }

    
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
        if (disposed) return
        disposed = true
        coordinates = null
        notifyChanged()
    }
}






class ScreenShareVisibility : ComposeVisibility() {
    override fun isVisible(): Boolean = true
}
