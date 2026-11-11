package com.difft.android.selector.engine

import android.net.Uri

import androidx.fragment.app.Fragment

import com.difft.android.selector.config.CustomIntentKey

import java.util.ArrayList

interface CropFileEngine {
    



    fun onStartCrop(
        fragment: Fragment,
        srcUri: Uri,
        destinationUri: Uri,
        dataSource: ArrayList<String>,
        requestCode: Int
    )
}
