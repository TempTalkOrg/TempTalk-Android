package com.difft.android.selector.engine

import androidx.fragment.app.Fragment

import com.difft.android.selector.entity.LocalMedia

import java.util.ArrayList

@Deprecated("Please use CropFileEngine")
interface CropEngine {
    fun onStartCrop(
        fragment: Fragment,
        currentLocalMedia: LocalMedia,
        dataSource: ArrayList<LocalMedia>,
        requestCode: Int
    )
}
