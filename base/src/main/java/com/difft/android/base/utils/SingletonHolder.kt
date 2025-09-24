package com.difft.android.base.utils


open class SingletonHolder<out T : Any, in A>(private val creator: (A) -> T) {

    private var instance: T? = null

    fun getInstance(arg: A): T =
        instance ?: synchronized(this) {
            instance ?: creator(arg).apply {
                instance = this
            }
        }
}