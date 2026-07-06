package com.luck.picture.lib.utils;

import android.os.Build;

import androidx.annotation.ChecksSdkIntAtLeast;

/**
 * @author：luck
 * @date：2019-07-17 15:12
 * @describe：Android Sdk版本判断
 */
public class SdkVersionUtils {

    /**
     * 判断是否是低于Android LOLLIPOP版本
     */
    public static boolean isMinM() {
        // minSdk 26 >= M (23): never below M.
        return false;
    }

    /**
     * 判断是否是Android O版本
     */
    public static boolean isO() {
        // minSdk 26 == O: always true.
        return true;
    }


    /**
     * 判断是否是Android N版本
     */
    public static boolean isMaxN() {
        // minSdk 26 >= N (24): always true.
        return true;
    }


    /**
     * 判断是否是Android N版本
     */
    public static boolean isN() {
        // minSdk 26 > N (24): SDK_INT can never equal N.
        return false;
    }

    /**
     * 判断是否是Android P版本
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    public static boolean isP() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /**
     * 判断是否是Android Q版本
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    public static boolean isQ() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /**
     * 判断是否是Android R版本
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    public static boolean isR() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * 判断是否是Android TIRAMISU版本
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    public static boolean isTIRAMISU() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * 判断是否是Android UPSIDE_DOWN_CAKE版本
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static boolean isUPSIDE_DOWN_CAKE() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }
}
