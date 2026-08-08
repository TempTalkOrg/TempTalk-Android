package com.difft.android.chat.mediasend

import android.net.Uri

/**
 * A page that sits in the media send flow.
 */
interface MediaSendPageFragment {

    fun getUri(): Uri

    fun setUri(uri: Uri)

    fun saveState(): Any?

    fun restoreState(state: Any)

    fun notifyHidden()
}
