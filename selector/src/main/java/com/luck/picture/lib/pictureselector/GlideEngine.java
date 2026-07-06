package com.luck.picture.lib.pictureselector;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.integration.webp.decoder.WebpDrawable;
import com.bumptech.glide.integration.webp.decoder.WebpDrawableTransformation;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.luck.picture.lib.engine.ImageEngine;
import com.luck.picture.lib.utils.ActivityCompatHelper;

public class GlideEngine implements ImageEngine {

    /**
     * 加载图片
     *
     * @param context   上下文
     * @param url       资源url
     * @param imageView 图片承载控件
     */
    @Override
    public void loadImage(Context context, String url, ImageView imageView) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return;
        }
        Glide.with(context)
                .load(url)
                .into(imageView);
    }

    @Override
    public void loadImage(Context context, ImageView imageView, String url, int maxWidth, int maxHeight) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return;
        }
        Glide.with(context)
                .load(url)
                .override(maxWidth, maxHeight)
                .into(imageView);
    }

    /**
     * 加载相册目录封面
     *
     * @param context   上下文
     * @param url       图片路径
     * @param imageView 承载图片ImageView
     */
    @Override
    public void loadAlbumCover(Context context, String url, ImageView imageView) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return;
        }
        // Old path: .asBitmap() decoded animated WebP to its first frame only → static cover.
        // Fix: drop .asBitmap() (load as Drawable) + route the crop/corner transform through
        // optionalTransform(WebpDrawable) so the cover animates, mirroring loadGridImage.
        // The 0.5x sizeMultiplier was also dropped — it's orthogonal to the WebpDrawable path
        // (decode size only, not static-vs-animated), removed just to match loadGridImage;
        // covers now decode at the full 180px target (negligible extra memory).
        MultiTransformation<Bitmap> transform = new MultiTransformation<>(new CenterCrop(), new RoundedCorners(8));
        Glide.with(context)
                .load(url)
                .override(180, 180)
                .transform(transform)
                .optionalTransform(WebpDrawable.class, new WebpDrawableTransformation(transform))
                .placeholder(com.luck.picture.lib.R.drawable.ps_image_placeholder)
                .into(imageView);
    }


    /**
     * 加载图片列表图片
     *
     * @param context   上下文
     * @param url       图片路径
     * @param imageView 承载图片ImageView
     */
    @Override
    public void loadGridImage(Context context, String url, ImageView imageView) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return;
        }
        // A bare CenterCrop (BitmapTransformation) on an animated-WebP WebpDrawable is
        // tolerated by Glide 4 but fails the load on Glide 5 → placeholder. Route it through
        // optionalTransform(WebpDrawable) so animated WebP thumbnails render correctly.
        CenterCrop centerCrop = new CenterCrop();
        Glide.with(context)
                .load(url)
                .override(200, 200)
                .transform(centerCrop)
                .optionalTransform(WebpDrawable.class, new WebpDrawableTransformation(centerCrop))
                .placeholder(com.luck.picture.lib.R.drawable.ps_image_placeholder)
                .into(imageView);
    }

    @Override
    public void pauseRequests(Context context) {
        Glide.with(context).pauseRequests();
    }

    @Override
    public void resumeRequests(Context context) {
        Glide.with(context).resumeRequests();
    }

    private GlideEngine() {
    }

    private static final class InstanceHolder {
        static final GlideEngine instance = new GlideEngine();
    }

    public static GlideEngine createGlideEngine() {
        return InstanceHolder.instance;
    }
}
