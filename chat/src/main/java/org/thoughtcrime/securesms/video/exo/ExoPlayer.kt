package org.thoughtcrime.securesms.video.exo

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

fun ExoPlayer.configureForVideoPlayback() {
  repeatMode = Player.REPEAT_MODE_OFF
  volume = 1f
}
