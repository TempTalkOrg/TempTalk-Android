package com.luck.picture.lib.pictureselector;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.luck.picture.lib.engine.CropFileEngine;
import com.luck.picture.lib.style.PictureSelectorStyle;
import com.luck.picture.lib.style.SelectMainStyle;
import com.luck.picture.lib.style.TitleBarStyle;
import com.luck.picture.lib.utils.StyleUtils;
import com.yalantis.ucrop.UCrop;
import com.yalantis.ucrop.UCropImageEngine;

import java.util.ArrayList;

public class ImageFileCropEngine implements CropFileEngine {

    private PictureSelectorStyle selectorStyle;
    private Context context;

    public ImageFileCropEngine(Context context, PictureSelectorStyle selectorStyle) {
        this.selectorStyle = selectorStyle;
        this.context = context;
    }

    @Override
    public void onStartCrop(Fragment fragment, Uri srcUri, Uri destinationUri, ArrayList<String> dataSource, int requestCode) {
        // 防止在 Android 9 等设备上因 dataSource 为空导致 UCropMultipleActivity 崩溃
        if (dataSource == null || dataSource.isEmpty() || srcUri == null || destinationUri == null) {
            return;
        }
        UCrop.Options options = buildOptions();
        UCrop uCrop = UCrop.of(srcUri, destinationUri, dataSource);
        uCrop.withOptions(options);
        uCrop.setImageEngine(new UCropImageEngine() {
            @Override
            public void loadImage(Context context, String url, ImageView imageView) {
                if (!ImageLoaderUtils.assertValidRequest(context)) {
                    return;
                }
                Glide.with(context).load(url).override(180, 180).into(imageView);
            }

            @Override
            public void loadImage(Context context, Uri url, int maxWidth, int maxHeight, OnCallbackListener<Bitmap> call) {
                Glide.with(context).asBitmap().load(url).override(maxWidth, maxHeight).into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        if (call != null) {
                            call.onCall(resource);
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        if (call != null) {
                            call.onCall(null);
                        }
                    }
                });
            }
        });
        uCrop.start(fragment.requireActivity(), fragment, requestCode);
    }

    /**
     * 配制UCrop，可根据需求自我扩展
     *
     * @return
     */
    private UCrop.Options buildOptions() {
        UCrop.Options options = new UCrop.Options();
        options.setCircleDimmedLayer(true);
        options.isCropDragSmoothToCenter(false);
        options.isForbidSkipMultipleCrop(true);
        options.setHideBottomControls(true);
        options.setMaxScaleMultiplier(100);

        // uCrop reads colors as hard ARGB values (window.setStatusBarColor / toolbar paint).
        // It does NOT auto-switch on theme change. Resolve dark/light explicitly so the
        // crop screen matches the app's current night mode.
        int nightMask = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = nightMask == Configuration.UI_MODE_NIGHT_YES;

        int barColor = ContextCompat.getColor(context, com.difft.android.base.R.color.bg);
        int textColor = ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary);

        options.setRootViewBackgroundColor(barColor);
        options.setStatusBarColor(barColor);
        options.setToolbarColor(barColor);
        options.setToolbarWidgetColor(textColor);
        // status bar icons: dark mode → light icons (false); light mode → dark icons (true).
        options.isDarkStatusBarBlack(!isDark);

        // Allow selectorStyle to override status/toolbar color when set explicitly.
        if (selectorStyle != null && selectorStyle.getSelectMainStyle().getStatusBarColor() != 0) {
            SelectMainStyle mainStyle = selectorStyle.getSelectMainStyle();
            int statusBarColor = mainStyle.getStatusBarColor();
            if (StyleUtils.checkStyleValidity(statusBarColor)) {
                options.setStatusBarColor(statusBarColor);
                options.setToolbarColor(statusBarColor);
                options.isDarkStatusBarBlack(mainStyle.isDarkStatusBarBlack());
            }
            TitleBarStyle titleBarStyle = selectorStyle.getTitleBarStyle();
            if (StyleUtils.checkStyleValidity(titleBarStyle.getTitleTextColor())) {
                options.setToolbarWidgetColor(titleBarStyle.getTitleTextColor());
            }
        }

        return options;
    }
}
