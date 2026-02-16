package com.difft.android.security;

import com.difft.android.base.log.lumberjack.L;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class AppInfo {

    public static X509Certificate getInstalledApkCert(Context context) {

        X509Certificate cert = null;
        try {
            // 获取PackageManager实例
            PackageManager pm = context.getPackageManager();
            // 获取当前应用的PackageInfo，其中包含签名证书等信息
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            // 获取签名数组
            Signature[] signatures = packageInfo.signatures;
            // 通常只有一个签名证书，取第一个
            Signature signature = signatures[0];
            // 将签名证书转换为X509证书对象
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream stream = new ByteArrayInputStream(signature.toByteArray());
            cert = (X509Certificate) cf.generateCertificate(stream);
        } catch (PackageManager.NameNotFoundException | CertificateException e) {
            L.w(e, () -> "[AppInfo] getSigningCertificate error");
        }
        return cert;
    }
}
