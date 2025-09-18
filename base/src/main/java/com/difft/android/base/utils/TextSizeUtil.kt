package com.difft.android.base.utils

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

object TextSizeUtil {

    const val TEXT_SIZE_DEFAULT = 0
    const val TEXT_SIZE_LAGER = 1

    private val mTextSizeSubject = BehaviorSubject.create<Int>()
    fun emitTextSizeChange(textSize: Int) = mTextSizeSubject.onNext(textSize)
    val textSizeChange: Observable<Int> = mTextSizeSubject

    fun isLager(): Boolean {
        return globalServices.userManager.getUserData()?.textSize == TEXT_SIZE_LAGER
    }

    fun updateTextSize(textSize: Int) {
        globalServices.userManager.update {
            this.textSize = textSize
        }
        emitTextSizeChange(textSize)
    }
}

