package com.difft.android.selector.basic

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.entity.LocalMedia
import java.lang.ref.SoftReference

class PictureSelector private constructor(activity: Activity?, fragment: Fragment?) {

    private val mActivity = SoftReference(activity)
    private val mFragment = SoftReference(fragment)

    private constructor(activity: Activity?) : this(activity, null)

    private constructor(fragment: Fragment) : this(fragment.activity, fragment)

    /**
     * @param chooseMode Select the type of images you want, all or images or video or audio
     * Use [com.difft.android.selector.config.SelectMimeType]
     */
    fun openGallery(chooseMode: Int): PictureSelectionModel {
        return PictureSelectionModel(this, chooseMode)
    }

    /** Preview mode to preview images or videos or audio */
    fun openPreview(): PictureSelectionPreviewModel {
        return PictureSelectionPreviewModel(this)
    }

    /** @return Activity. */
    internal fun getActivity(): Activity? = mActivity.get()

    /** @return Fragment. */
    internal fun getFragment(): Fragment? = mFragment.get()

    companion object {
        /** Start PictureSelector for context. */
        @JvmStatic
        fun create(context: Context): PictureSelector = PictureSelector(context as Activity)

        /** Start PictureSelector for Activity. */
        @JvmStatic
        fun create(activity: AppCompatActivity): PictureSelector = PictureSelector(activity)

        /** Start PictureSelector for Activity. */
        @JvmStatic
        fun create(activity: FragmentActivity): PictureSelector = PictureSelector(activity)

        /** Start PictureSelector for Fragment. */
        @JvmStatic
        fun create(fragment: Fragment): PictureSelector = PictureSelector(fragment)

        /** set result */
        @JvmStatic
        fun putIntentResult(data: ArrayList<LocalMedia>): Intent =
            Intent().putParcelableArrayListExtra(PictureConfig.EXTRA_RESULT_SELECTION, data)

        /** @return get Selector LocalMedia */
        @JvmStatic
        fun obtainSelectorList(intent: Intent?): ArrayList<LocalMedia> {
            if (intent == null) {
                return ArrayList()
            }
            val result = intent.getParcelableArrayListExtra<LocalMedia>(PictureConfig.EXTRA_RESULT_SELECTION)
            return result ?: ArrayList()
        }
    }
}
