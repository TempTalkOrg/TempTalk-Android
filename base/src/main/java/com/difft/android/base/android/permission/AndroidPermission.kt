package com.difft.android.base.android.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

object AndroidPermission {
    enum class Result {
        Granted, Denied, Ignored
    }

    fun requestPermissionsResult(
        activity: AppCompatActivity,
        vararg permissions: String
    ): Single<Map<String, Result>> {
        val permissionsGranted =
            permissions.filter { havePermission(activity, it).blockingGet() }.toSet()
        val permissionsNotGranted = permissions.toSet() - permissionsGranted

        val permissionsUserIgnore = permissionsNotGranted
            .filter { isUserIgnoredPermission(activity, it).blockingGet() }
        val permissionsLeft = permissionsNotGranted - permissionsUserIgnore.toSet()

        val result = mutableMapOf<String, Result>()
        permissionsGranted.forEach {
            result[it] = Result.Granted
        }
        permissionsUserIgnore.forEach {
            result[it] = Result.Ignored
        }

        return requestPermissions(activity, *permissionsLeft.toTypedArray())
            .map {
                it.forEach { (k, v) ->
                    result[k] = if (v) Result.Granted else Result.Denied
                }
                result
            }
    }

    fun requestPermissions(
        activity: AppCompatActivity,
        vararg permissions: String
    ): Single<Map<String, Boolean>> {
        return Single.create { emitter ->
            @Suppress("UNCHECKED_CAST")
            activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { isGranted ->
                emitter.onSuccess(isGranted)
            }
                .launch(permissions as Array<String>)
        }
    }

    fun requestPermission(
        activity: AppCompatActivity,
        permission: String
    ): Single<Boolean> = requestPermissions(activity, permission)
        .map { isGranted -> isGranted.getOrElse(permission) { false } }


    fun havePermission(
        context: Context,
        permission: String
    ): Single<Boolean> = Single.defer {
        val isGranted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED

        Single.just(isGranted)
    }

    fun havePermissions(
        context: Context,
        vararg permissions: String
    ): Single<Boolean> = Single.defer {
        Observable.fromArray(*permissions)
            .flatMapSingle { permission -> havePermission(context, permission) }
            .toList()
            .map { results -> results.all { it } }
    }

    fun isUserIgnoredPermission(activity: AppCompatActivity, permission: String): Single<Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Single.just(false)
        }

        return Single.just(activity.shouldShowRequestPermissionRationale(permission))
    }

    fun isUserIgnoredPermissions(
        activity: AppCompatActivity,
        vararg permission: String
    ): Single<Boolean> {
        return Observable.fromArray(*permission)
            .flatMapSingle { isUserIgnoredPermission(activity, it) }
            .toList()
            .map { results -> results.all { it } }
    }

}