package com.difft.android.chat.common.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

class SearchInputViewModel(application: Application) : AndroidViewModel(application) {
    private val inputSubject = BehaviorSubject.createDefault("")
    val input: Observable<String> = inputSubject

    fun setInput(input: String?) {
        inputSubject.onNext(input ?: "")
    }
}