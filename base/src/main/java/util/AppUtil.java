package util;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.difft.android.base.utils.PackageUtil;

import org.jetbrains.annotations.NotNull;

public final class AppUtil {

  private AppUtil() {}

  /**
   * Restarts the application. Should generally only be used for internal tools.
   */
  public static void restart(@NonNull Context context) {
    String packageName   = context.getPackageName();
    Intent defaultIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

    defaultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    context.startActivity(defaultIntent);
    Runtime.getRuntime().exit(0);
  }

  /**
   * Returns the version of the Android system. 5.0 , 6.0 , 7.0
   * @return
   */
    @NotNull
    public static String getAndroidSystemVersion() {
          return android.os.Build.VERSION.RELEASE;
    }

  @NotNull
  public static String getAppVersionName() {
    return PackageUtil.INSTANCE.getAppVersionName();
  }


  @NotNull
  public static String getAppBuildVersionCode() {
    return PackageUtil.INSTANCE.getAppVersionCode()+"";
  }
}
