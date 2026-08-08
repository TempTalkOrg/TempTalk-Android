package com.difft.android.selector.config

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/**
 * Keys mirror the UCrop library's UCrop class keys — do not rename when using the
 * bundled crop library.
 */
object Crop {
    const val REQUEST_EDIT_CROP = 696

    const val REQUEST_CROP = 69

    const val RESULT_CROP_ERROR = 96

    private const val EXTRA_PREFIX = "com.yalantis.ucrop"
    const val EXTRA_OUTPUT_CROP_ASPECT_RATIO = "$EXTRA_PREFIX.CropAspectRatio"
    const val EXTRA_OUTPUT_IMAGE_WIDTH = "$EXTRA_PREFIX.ImageWidth"
    const val EXTRA_OUTPUT_IMAGE_HEIGHT = "$EXTRA_PREFIX.ImageHeight"
    const val EXTRA_OUTPUT_OFFSET_X = "$EXTRA_PREFIX.OffsetX"
    const val EXTRA_OUTPUT_OFFSET_Y = "$EXTRA_PREFIX.OffsetY"
    const val EXTRA_ERROR = "$EXTRA_PREFIX.Error"

    @JvmStatic
    fun getOutput(intent: Intent): Uri? {
        var outputUri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        if (outputUri == null) {
            outputUri = intent.getParcelableExtra(CustomIntentKey.EXTRA_OUTPUT_URI)
        }
        return outputUri
    }

    @JvmStatic
    fun getOutputCustomExtraData(intent: Intent): String? {
        return intent.getStringExtra(CustomIntentKey.EXTRA_CUSTOM_EXTRA_DATA)
    }

    @JvmStatic
    fun getOutputImageWidth(intent: Intent): Int {
        return intent.getIntExtra(EXTRA_OUTPUT_IMAGE_WIDTH, -1)
    }

    @JvmStatic
    fun getOutputImageHeight(intent: Intent): Int {
        return intent.getIntExtra(EXTRA_OUTPUT_IMAGE_HEIGHT, -1)
    }

    @JvmStatic
    fun getOutputCropAspectRatio(intent: Intent): Float {
        return intent.getFloatExtra(EXTRA_OUTPUT_CROP_ASPECT_RATIO, 0f)
    }

    @JvmStatic
    fun getOutputImageOffsetX(intent: Intent): Int {
        return intent.getIntExtra(EXTRA_OUTPUT_OFFSET_X, 0)
    }

    @JvmStatic
    fun getOutputImageOffsetY(intent: Intent): Int {
        return intent.getIntExtra(EXTRA_OUTPUT_OFFSET_Y, 0)
    }

    @JvmStatic
    fun getError(result: Intent): Throwable? {
        return result.getSerializableExtra(EXTRA_ERROR) as Throwable?
    }
}
