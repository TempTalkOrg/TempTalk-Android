package com.difft.android.selector.engine

import android.net.Uri

import androidx.fragment.app.Fragment

import com.difft.android.selector.config.CustomIntentKey

import java.util.ArrayList

interface CropFileEngine {
    /**
     * Custom crop image engine. Implementers plug the crop result path into the LocalMedia object.
     * When implementing your own crop, assign the [CustomIntentKey] extras in the result Intent.
     */
    fun onStartCrop(
        fragment: Fragment,
        srcUri: Uri,
        destinationUri: Uri,
        dataSource: ArrayList<String>,
        requestCode: Int
    )
}
