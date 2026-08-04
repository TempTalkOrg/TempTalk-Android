package com.difft.android.selector.engine

import android.content.Context
import android.net.Uri

import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener

import java.util.ArrayList

interface CompressFileEngine {
    /**
     * Custom compression engine. Implementers plug the compressed path into the LocalMedia object.
     */
    fun onStartCompress(context: Context, source: ArrayList<Uri>, call: OnKeyValueResultCallbackListener)
}
