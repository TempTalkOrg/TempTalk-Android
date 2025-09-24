package com.difft.android.network.viewmodel

import com.difft.android.network.NetworkException

enum class Status {
    SUCCESS,
    ERROR,
    LOADING
}

data class Resource<out T>(val status: Status, val data: T?, val exception: NetworkException?) {

    companion object {

        fun <T> success(data: T? = null): Resource<T> {
            return Resource(Status.SUCCESS, data, null)
        }

        fun <T> error(exception: NetworkException, data: T? = null): Resource<T> {
            return Resource(Status.ERROR, data, exception)
        }

        fun <T> loading(data: T? = null): Resource<T> {
            return Resource(Status.LOADING, data, null)
        }
    }
}
