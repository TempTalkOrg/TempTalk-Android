package com.luck.picture.lib.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.luck.picture.lib.basic.PictureFileProvider;
import com.luck.picture.lib.config.PictureMimeType;

import java.io.File;

/**
 * @author：luck
 * @date：2023/3/25 6:21 下午
 * @describe：IntentUtils
 */
public class IntentUtils {

    public static void startSystemPlayerVideo(Context context, String path) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        boolean isParseUri = PictureMimeType.isContent(path) || PictureMimeType.isHasHttp(path);
        Uri data;
        if (SdkVersionUtils.isQ()) {
            data = isParseUri ? Uri.parse(path) : Uri.fromFile(new File(path));
        } else {
            // isMaxN() (SDK_INT >= N/24) is always true at minSdk 26, so the final
            // raw-Uri.fromFile else branch was dead; below is the API 26-28 path.
            data = isParseUri ? Uri.parse(path) : PictureFileProvider.getUriForFile(context, context.getPackageName() + ".luckProvider", new File(path));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(data, "video/*");
        context.startActivity(intent);
    }
}
