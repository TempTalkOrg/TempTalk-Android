/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.video.videoconverter.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.io.IOException

object MediaCodecCompat {
  private const val TAG = "MediaDataSourceCompat"

  const val MEDIA_FORMAT_KEY_MAX_BIT_RATE = "max-bitrate"

  
  const val MEDIA_FORMAT_KEY_CODEC_SPECIFIC_DATA_0 = "csd-0"
  const val MEDIA_FORMAT_KEY_CODEC_SPECIFIC_DATA_1 = "csd-1"
  const val MEDIA_FORMAT_KEY_CODEC_SPECIFIC_DATA_2 = "csd-2"

  @JvmStatic
  fun findDecoder(inputFormat: MediaFormat): Pair<MediaCodec, MediaFormat> {
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val decoderName: String? = codecs.findDecoderForFormat(inputFormat)
    if (decoderName != null) {
      return Pair(MediaCodec.createByCodecName(decoderName), inputFormat)
    }

    val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
    if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION == mimeType) {
      return if (Build.VERSION.SDK_INT >= 29) {
        findBackupDecoderForDolbyVision(MediaFormat(inputFormat)) ?: throw IOException("Can't create decoder for $mimeType!")
      } else {
        findBackupDecoderForDolbyVision(inputFormat) ?: throw IOException("Can't create decoder for $mimeType!")
      }
    } else if (mimeType != null) {
      try {
        val decoder = MediaCodec.createDecoderByType(mimeType)
        return Pair(decoder, inputFormat)
      } catch (iae: IllegalArgumentException) {
        throw IOException("Can't create decoder for $mimeType, which is not a valid MIME type.", iae)
      }
    }

    throw IOException("Can't create decoder for $mimeType!")
  }

  













  private fun findBackupDecoderForDolbyVision(mediaFormat: MediaFormat): Pair<MediaCodec, MediaFormat>? {
    if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION != mediaFormat.getString(MediaFormat.KEY_MIME)) {
      throw IllegalStateException("Must supply Dolby Vision MediaFormat!")
    }

    return try {
      when (mediaFormat.getInteger(MediaFormat.KEY_PROFILE)) {
        CodecProfileLevel.DolbyVisionProfileDvheDtr,
        CodecProfileLevel.DolbyVisionProfileDvheSt -> {
          
          mediaFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC)
          mediaFormat.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
          mediaFormat.setBaseCodecLevelFromDolbyVisionLevel()
          return findDecoder(mediaFormat)
        }

        CodecProfileLevel.DolbyVisionProfileDvavSe -> {
          
          mediaFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC)
          mediaFormat.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
          mediaFormat.setBaseCodecLevelFromDolbyVisionLevel()
          return findDecoder(mediaFormat)
        }

        else -> return null
      }
    } catch (npe: NullPointerException) {
      null
    }
  }

  private fun MediaFormat.setBaseCodecLevelFromDolbyVisionLevel(): Boolean {
    val mimeType = this.getString(MediaFormat.KEY_MIME) ?: return false
    try {
      val codecLevel = this.getInteger(MediaFormat.KEY_LEVEL)
      when (mimeType) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> {
          val mapDvLevelToAvcLevel = mapDvLevelToAvcLevel(codecLevel) ?: return false
          this.setInteger(MediaFormat.KEY_LEVEL, mapDvLevelToAvcLevel)
        }
        MediaFormat.MIMETYPE_VIDEO_HEVC -> {
          val mapDvLevelToAvcLevel = mapDvLevelToHevcLevel(codecLevel) ?: return false
          this.setInteger(MediaFormat.KEY_LEVEL, mapDvLevelToAvcLevel)
        }
      }
    } catch (npe: NullPointerException) {
      L.d { "$TAG Could not update codec level in media format." }
      return false
    }
    return true
  }

  private fun mapDvLevelToHevcLevel(level: Int) = when (level) {
    CodecProfileLevel.DolbyVisionLevelHd24,
    CodecProfileLevel.DolbyVisionLevelHd30 -> CodecProfileLevel.HEVCHighTierLevel31
    CodecProfileLevel.DolbyVisionLevelFhd24,
    CodecProfileLevel.DolbyVisionLevelFhd30 -> CodecProfileLevel.HEVCHighTierLevel4
    CodecProfileLevel.DolbyVisionLevelFhd60 -> CodecProfileLevel.HEVCHighTierLevel41
    CodecProfileLevel.DolbyVisionLevelUhd24,
    CodecProfileLevel.DolbyVisionLevelUhd30 -> CodecProfileLevel.HEVCHighTierLevel5
    CodecProfileLevel.DolbyVisionLevelUhd48,
    CodecProfileLevel.DolbyVisionLevelUhd60 -> CodecProfileLevel.HEVCHighTierLevel51
    CodecProfileLevel.DolbyVisionLevel8k60 -> CodecProfileLevel.HEVCHighTierLevel61
    else -> null
  }

  private fun mapDvLevelToAvcLevel(level: Int) = when (level) {
    CodecProfileLevel.DolbyVisionLevelHd24,
    CodecProfileLevel.DolbyVisionLevelHd30 -> CodecProfileLevel.AVCLevel31
    CodecProfileLevel.DolbyVisionLevelFhd24,
    CodecProfileLevel.DolbyVisionLevelFhd30 -> CodecProfileLevel.AVCLevel4
    CodecProfileLevel.DolbyVisionLevelFhd60 -> CodecProfileLevel.AVCLevel42
    CodecProfileLevel.DolbyVisionLevelUhd24 -> CodecProfileLevel.AVCLevel51
    CodecProfileLevel.DolbyVisionLevelUhd30 -> CodecProfileLevel.AVCLevel52
    CodecProfileLevel.DolbyVisionLevelUhd48,
    CodecProfileLevel.DolbyVisionLevelUhd60,
    CodecProfileLevel.DolbyVisionLevel8k60 -> avcLevel6()
    else -> null
  }

  private fun avcLevel6() = if (Build.VERSION.SDK_INT >= 29) {
    CodecProfileLevel.AVCLevel62
  } else {
    null
  }
}
