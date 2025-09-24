package com.difft.android.base.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import autodispose2.AutoDispose
import autodispose2.AutoDisposeConverter
import autodispose2.androidx.lifecycle.AndroidLifecycleScopeProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.CompletableTransformer
import io.reactivex.rxjava3.core.ObservableTransformer
import io.reactivex.rxjava3.core.SingleTransformer
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit

object RxUtil {
    fun <T> autoDispose(owner: LifecycleOwner): AutoDisposeConverter<T> =
        AutoDispose.autoDisposable(
            AndroidLifecycleScopeProvider.from(
                owner,
                Lifecycle.Event.ON_DESTROY
            )
        )

    fun <T : Any> getSchedulerComposer(): ObservableTransformer<T, T> =
        ObservableTransformer { upstream ->
            upstream
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }

    fun <T : Any> getSingleSchedulerComposer(): SingleTransformer<T, T> =
        SingleTransformer { upstream ->
            upstream
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }

    fun getCompletableTransformer(): CompletableTransformer =
        CompletableTransformer { upstream ->
            upstream
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
        }

    /**
     * @param timeout 额外时间隔时间
     * @param takeLast 给定时间间隔内事件触发时机，false 给定间隔时间内第一次点击触发；ture 给定时间间隔内最后一次触发
     * */
    fun <T : Any> singleClick(timeout: Long = 0, takeLast: Boolean = false): ObservableTransformer<T, T> =
        ObservableTransformer { upstream ->
            val realTimeout = (500 + timeout).coerceAtLeast(500)
            if (takeLast) {
                upstream.throttleLast(realTimeout, TimeUnit.MILLISECONDS)
            } else {
                upstream.throttleFirst(realTimeout, TimeUnit.MILLISECONDS)
            }
        }
}