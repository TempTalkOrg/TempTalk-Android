package com.difft.android.selector.basic

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.selector.R
import com.difft.android.selector.utils.ActivityCompatHelper

object FragmentInjectManager {
    /** inject fragment */
    @JvmStatic
    fun injectFragment(activity: FragmentActivity, targetFragmentTag: String, targetFragment: Fragment) {
        if (ActivityCompatHelper.checkFragmentNonExits(activity, targetFragmentTag)) {
            activity.supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, targetFragment, targetFragmentTag)
                .addToBackStack(targetFragmentTag)
                .commitAllowingStateLoss()
        }
    }
}
