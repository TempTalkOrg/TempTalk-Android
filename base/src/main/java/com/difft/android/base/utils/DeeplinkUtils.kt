package com.difft.android.base.utils

import android.net.Uri
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.UUID

data class LinkDataEntity(
    var category: Int,
    val gid: String?,
    val uid: String?,
    var uri: Uri? = null,
    val uniqueId: String = UUID.randomUUID().toString()
) {
    companion object {
        const val LINK_CATEGORY = "LINK_CATEGORY"

        const val CATEGORY_PUSH = 1
        const val CATEGORY_MESSAGE = 2
        const val CATEGORY_SCHEME = 3
    }
}

object DeeplinkUtils {

    private val mDeeplinkSubject = BehaviorSubject.create<LinkDataEntity>()
    fun emitDeeplink(data: LinkDataEntity) = mDeeplinkSubject.onNext(data)

    val deeplink: Observable<LinkDataEntity> = mDeeplinkSubject
        .filter { lastProcessedLinkId != it.uniqueId }
        .doOnNext { lastProcessedLinkId = it.uniqueId }

    private var lastProcessedLinkId: String? = null
}

