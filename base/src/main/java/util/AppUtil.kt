package util

import com.difft.android.base.utils.PackageUtil

/**
 * App / system version helpers.
 */
object AppUtil {

    /** Android system version, e.g. "5.0", "6.0", "7.0". */
    @JvmStatic
    fun getAndroidSystemVersion(): String = android.os.Build.VERSION.RELEASE

    @JvmStatic
    fun getAppVersionName(): String = PackageUtil.getAppVersionName().orEmpty()

    @JvmStatic
    fun getAppBuildVersionCode(): String = PackageUtil.getAppVersionCode().toString()
}
