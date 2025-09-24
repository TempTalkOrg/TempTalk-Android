package com.difft.android.security;

import android.content.Context;

import java.security.cert.X509Certificate;


public class SecurityLib {


    // Used to load the 'security' library on application startup.
    static {
        System.loadLibrary("security");
    }

    /**
     * @param context Application context
     * @return true校验通过 false为校验失败
     */
    public native static boolean checkSign(String sign);

    /**
     * 模拟器检测
     * @param context Application context
     * @return true校验通过 false为校验失败
     */
    public native static boolean checkEmulator();

    /**
     * 系统root检测
     * @return true校验通过 false为校验失败
     */
    public native static boolean checkRoot();

    public static boolean checkApkSign(Context context){
        try {
            X509Certificate x509Certificate = AppInfo.getInstalledApkCert(context);
            if(x509Certificate == null){
                return false;
            }
            byte[] certBytes = x509Certificate.getEncoded();
            String hash = HashUtil.calcDataHash(certBytes, HashUtil.HASH_ALGORITHM_SHA256);
            if(SecurityLib.checkSign(hash)){
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

}