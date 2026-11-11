package util

import com.difft.android.base.utils.PackageUtil




object AppUtil {

    
    @JvmStatic
    fun getAndroidSystemVersion(): String = android.os.Build.VERSION.RELEASE

    @JvmStatic
    fun getAppVersionName(): String = PackageUtil.getAppVersionName().orEmpty()

    @JvmStatic
    fun getAppBuildVersionCode(): String = PackageUtil.getAppVersionCode().toString()
}
