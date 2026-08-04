package com.difft.android.selector.entity

import java.util.ArrayList

class MediaData {

    /** Public fields are accessed bare from remaining Java (LocalMediaPageLoader) → @JvmField. */
    @JvmField
    var isHasNextMore: Boolean = false

    @JvmField
    var data: ArrayList<LocalMedia>? = null

    constructor()

    constructor(isHasNextMore: Boolean, data: ArrayList<LocalMedia>?) {
        this.isHasNextMore = isHasNextMore
        this.data = data
    }
}
