package com.difft.android.selector.app

import android.content.Context
import com.difft.android.selector.engine.PictureSelectorEngine

interface IApp {

    fun getAppContext(): Context?

    fun getPictureSelectorEngine(): PictureSelectorEngine?
}
