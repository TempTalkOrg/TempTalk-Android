package com.difft.android.selector.app

import android.content.Context
import com.difft.android.selector.engine.PictureSelectorEngine

class PictureAppMaster private constructor() : IApp {

    private val app: IApp? = null

    override fun getAppContext(): Context? {
        return app?.getAppContext()
    }

    override fun getPictureSelectorEngine(): PictureSelectorEngine? {
        return app?.getPictureSelectorEngine()
    }

    companion object {
        private var mInstance: PictureAppMaster? = null

        @JvmStatic
        fun getInstance(): PictureAppMaster {
            if (mInstance == null) {
                synchronized(PictureAppMaster::class.java) {
                    if (mInstance == null) {
                        mInstance = PictureAppMaster()
                    }
                }
            }
            return mInstance!!
        }
    }
}
