/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import difft.android.messageserialization.model.Attachment
import com.luck.picture.lib.entity.LocalMedia
import util.logging.Log
import org.thoughtcrime.securesms.mms.PartAuthority
import java.io.IOException
import java.util.Locale

object MediaUtil {
    private val TAG: String = Log.tag(MediaUtil::class.java)

    const val IMAGE_PNG: String = "image/png"
    const val IMAGE_JPEG: String = "image/jpeg"
    const val IMAGE_HEIC: String = "image/heic"
    const val IMAGE_HEIF: String = "image/heif"
    const val IMAGE_AVIF: String = "image/avif"
    const val IMAGE_WEBP: String = "image/webp"
    const val IMAGE_GIF: String = "image/gif"
    const val AUDIO_AAC: String = "audio/aac"
    const val AUDIO_MP4: String = "audio/mp4"
    const val AUDIO_UNSPECIFIED: String = "audio/*"
    const val VIDEO_MP4: String = "video/mp4"
    const val VIDEO_UNSPECIFIED: String = "video/*"
    const val VCARD: String = "text/x-vcard"
    const val LONG_TEXT: String = "text/x-signal-plain"
    const val VIEW_ONCE: String = "application/x-signal-view-once"
    const val UNKNOWN: String = "*/*"
    const val OCTET: String = "application/octet-stream"

    //    public static SlideType getSlideTypeFromContentType(@NonNull String contentType) {
    //        if (isGif(contentType)) {
    //            return SlideType.GIF;
    //        } else if (isImageType(contentType)) {
    //            return SlideType.IMAGE;
    //        } else if (isVideoType(contentType)) {
    //            return SlideType.VIDEO;
    //        } else if (isAudioType(contentType)) {
    //            return SlideType.AUDIO;
    //        } else if (isMms(contentType)) {
    //            return SlideType.MMS;
    //        } else if (isLongTextType(contentType)) {
    //            return SlideType.LONG_TEXT;
    //        } else if (isViewOnceType(contentType)) {
    //            return SlideType.VIEW_ONCE;
    //        } else {
    //            return SlideType.DOCUMENT;
    //        }
    //    }
    //    public static @NonNull Slide getSlideForAttachment(Attachment attachment) {
    //        if (attachment.isSticker()) {
    //            return new StickerSlide(attachment);
    //        }
    //
    //        switch (getSlideTypeFromContentType(attachment.getContentType())) {
    //            case GIF:
    //                return new GifSlide(attachment);
    //            case IMAGE:
    //                return new ImageSlide(attachment);
    //            case VIDEO:
    //                return new VideoSlide(attachment);
    //            case AUDIO:
    //                return new AudioSlide(attachment);
    //            case MMS:
    //                return new MmsSlide(attachment);
    //            case LONG_TEXT:
    //                return new TextSlide(attachment);
    //            case VIEW_ONCE:
    //                return new ViewOnceSlide(attachment);
    //            case DOCUMENT:
    //                return new DocumentSlide(attachment);
    //            default:
    //                throw new AssertionError();
    //        }
    //    }
    fun getMimeType(context: Context, uri: Uri?): String? {
        return getMimeType(context, uri, null)
    }

    fun getMimeType(context: Context, uri: Uri?, fileExtension: String?): String? {
        if (uri == null) return null

        //    if (PartAuthority.isLocalUri(uri)) {
//      return PartAuthority.getAttachmentContentType(context, uri);
//    }
        var type = context.contentResolver.getType(uri)
        if (type == null || isOctetStream(type)) {
            var extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            if (TextUtils.isEmpty(extension) && fileExtension != null) {
                extension = fileExtension
            }
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
            return getCorrectedMimeType(type, fileExtension)
        }

        return getCorrectedMimeType(type)
    }

    fun getExtension(context: Context, uri: Uri?): String? {
        return MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(getMimeType(context, uri))
    }

    private fun safeMimeTypeOverride(originalType: String?, overrideType: String): String? {
        if (MimeTypeMap.getSingleton().hasMimeType(overrideType)) {
            return overrideType
        }
        return originalType
    }

    fun overrideMimeTypeWithExtension(mimeType: String?, fileExtension: String?): String? {
        if (fileExtension == null) {
            return mimeType
        }
        if (fileExtension.lowercase(Locale.getDefault()) == "m4a") {
            return safeMimeTypeOverride(mimeType, AUDIO_MP4)
        }
        return mimeType
    }

    @JvmStatic
    fun getCorrectedMimeType(mimeType: String?): String? {
        return getCorrectedMimeType(mimeType, null)
    }

    fun getCorrectedMimeType(mimeType: String?, fileExtension: String?): String? {
        if (mimeType == null) return null

        return when (mimeType) {
            "image/jpg" -> safeMimeTypeOverride(mimeType, IMAGE_JPEG)
            "audio/mpeg" -> overrideMimeTypeWithExtension(mimeType, fileExtension)
            else -> mimeType
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getMediaSize(context: Context?, uri: Uri?): Long {
        val `in` = PartAuthority.getAttachmentStream(context!!, uri!!) ?: throw IOException("Couldn't obtain input stream.")

        var size: Long = 0
        val buffer = ByteArray(4096)
        var read: Int

        while ((`in`.read(buffer).also { read = it }) != -1) {
            size += read.toLong()
        }
        `in`.close()

        return size
    }

    //    @WorkerThread
    //    public static Pair<Integer, Integer> getDimensions(@NonNull Context context, @Nullable String contentType, @Nullable Uri uri) {
    //        if (uri == null || (!MediaUtil.isImageType(contentType) && !MediaUtil.isVideoType(contentType))) {
    //            return new Pair<>(0, 0);
    //        }
    //
    //        Pair<Integer, Integer> dimens = null;
    //
    //        if (MediaUtil.isGif(contentType)) {
    //            try {
    //                GifDrawable drawable = Glide.with(context)
    //                        .asGif()
    //                        .skipMemoryCache(true)
    //                        .diskCacheStrategy(DiskCacheStrategy.NONE)
    //                        .load(new DecryptableUri(uri))
    //                        .submit()
    //                        .get();
    //                dimens = new Pair<>(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    //            } catch (InterruptedException e) {
    //                Log.w(TAG, "Was unable to complete work for GIF dimensions.", e);
    //            } catch (ExecutionException e) {
    //                Log.w(TAG, "Glide experienced an exception while trying to get GIF dimensions.", e);
    //            }
    //        } else if (MediaUtil.hasVideoThumbnail(context, uri)) {
    //            Bitmap thumbnail = MediaUtil.getVideoThumbnail(context, uri, 1000);
    //
    //            if (thumbnail != null) {
    //                dimens = new Pair<>(thumbnail.getWidth(), thumbnail.getHeight());
    //            }
    //        } else {
    //            InputStream attachmentStream = null;
    //            try {
    //                if (MediaUtil.isJpegType(contentType)) {
    //                    attachmentStream = PartAuthority.getAttachmentStream(context, uri);
    //                    dimens = BitmapUtil.getExifDimensions(new ExifInterface(attachmentStream));
    //                    attachmentStream.close();
    //                    attachmentStream = null;
    //                }
    //                if (dimens == null) {
    //                    attachmentStream = PartAuthority.getAttachmentStream(context, uri);
    //                    dimens = BitmapUtil.getDimensions(attachmentStream);
    //                }
    //            } catch (FileNotFoundException e) {
    //                Log.w(TAG, "Failed to find file when retrieving media dimensions.", e);
    //            } catch (IOException e) {
    //                Log.w(TAG, "Experienced a read error when retrieving media dimensions.", e);
    //            } catch (BitmapDecodingException e) {
    //                Log.w(TAG, "Bitmap decoding error when retrieving dimensions.", e);
    //            } finally {
    //                if (attachmentStream != null) {
    //                    try {
    //                        attachmentStream.close();
    //                    } catch (IOException e) {
    //                        Log.w(TAG, "Failed to close stream after retrieving dimensions.", e);
    //                    }
    //                }
    //            }
    //        }
    //        if (dimens == null) {
    //            dimens = new Pair<>(0, 0);
    //        }
    //        Log.d(TAG, "Dimensions for [" + uri + "] are " + dimens.first + " x " + dimens.second);
    //        return dimens;
    //    }
    fun isMms(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == "application/mms"
    }

    @JvmStatic
    fun isGif(attachment: Attachment): Boolean {
        return isGif(attachment.contentType)
    }

    fun isJpeg(attachment: Attachment): Boolean {
        return isJpegType(attachment.contentType)
    }

    fun isHeic(attachment: Attachment): Boolean {
        return isHeicType(attachment.contentType)
    }

    fun isHeif(attachment: Attachment): Boolean {
        return isHeifType(attachment.contentType)
    }

    @JvmStatic
    fun isImage(attachment: Attachment): Boolean {
        return isImageType(attachment.contentType)
    }

    fun isAudio(attachment: Attachment): Boolean {
        return isAudioType(attachment.contentType)
    }

    @JvmStatic
    fun isVideo(attachment: Attachment): Boolean {
        return isVideoType(attachment.contentType)
    }

    fun isVideo(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' }.startsWith("video/")
    }

    fun isVcard(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == VCARD
    }

    @JvmStatic
    fun isGif(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == "image/gif"
    }

    @JvmStatic
    fun isJpegType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == IMAGE_JPEG
    }

    @JvmStatic
    fun isHeicType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == IMAGE_HEIC
    }

    @JvmStatic
    fun isHeifType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == IMAGE_HEIF
    }

    @JvmStatic
    fun isAvifType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim { it <= ' ' } == IMAGE_AVIF
    }

    fun isFile(attachment: Attachment): Boolean {
        return !isGif(attachment) && !isImage(attachment) && !isAudio(attachment) && !isVideo(attachment)
    }

    fun isTextType(contentType: String?): Boolean {
        return (null != contentType) && contentType.startsWith("text/")
    }

    fun isNonGifVideo(media: LocalMedia): Boolean {
        return isVideo(media.mimeType)
    }

    @JvmStatic
    fun isImageType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }

        return (contentType.startsWith("image/") && contentType != "image/svg+xml") || (contentType == MediaStore.Images.Media.CONTENT_TYPE)
    }

    fun isAudioType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }

        return contentType.startsWith("audio/") || contentType == MediaStore.Audio.Media.CONTENT_TYPE
    }

    @JvmStatic
    fun isVideoType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }

        return contentType.startsWith("video/") || contentType == MediaStore.Video.Media.CONTENT_TYPE
    }

    fun isImageOrVideoType(contentType: String?): Boolean {
        return isImageType(contentType) || isVideoType(contentType)
    }

    fun isStorySupportedType(contentType: String): Boolean {
        return isImageOrVideoType(contentType) && !isGif(contentType)
    }

    @JvmStatic
    fun isImageVideoOrAudioType(contentType: String?): Boolean {
        return isImageOrVideoType(contentType) || isAudioType(contentType)
    }

    fun isImageAndNotGif(contentType: String): Boolean {
        return isImageType(contentType) && !isGif(contentType)
    }

    fun isLongTextType(contentType: String?): Boolean {
        return (null != contentType) && (contentType == LONG_TEXT)
    }

    fun isViewOnceType(contentType: String?): Boolean {
        return (null != contentType) && (contentType == VIEW_ONCE)
    }

    @JvmStatic
    fun isOctetStream(contentType: String?): Boolean {
        return OCTET == contentType
    } //    public static boolean hasVideoThumbnail(@NonNull Context context, @Nullable Uri uri) {
    //        if (uri == null) {
    //            return false;
    //        }
    //
    ////        if (BlobProvider.isAuthority(uri) && MediaUtil.isVideo(BlobProvider.getMimeType(uri)) && Build.VERSION.SDK_INT >= 23) {
    ////            return true;
    ////        }
    //
    //        if (!isSupportedVideoUriScheme(uri.getScheme())) {
    //            return false;
    //        }
    //
    //        if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
    //            return uri.getLastPathSegment().contains("video");
    //        } else if (uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())) {
    //            return true;
    //        } else if (uri.toString().startsWith("file://") &&
    //                MediaUtil.isVideo(URLConnection.guessContentTypeFromName(uri.toString()))) {
    //            return true;
    //        } else return PartAuthority.isAttachmentUri(uri) && MediaUtil.isVideoType(getMimeType(context, uri));
    //    }
    //    @WorkerThread
    //    public static @Nullable Bitmap getVideoThumbnail(@NonNull Context context, @Nullable Uri uri) {
    //        return getVideoThumbnail(context, uri, 1000);
    //    }
    //    @WorkerThread
    //    public static @Nullable Bitmap getVideoThumbnail(@NonNull Context context, @Nullable Uri uri, long timeUs) {
    //        if (uri == null) {
    //            return null;
    //        } else if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
    //            long videoId = Long.parseLong(uri.getLastPathSegment().split(":")[1]);
    //
    //            return MediaStore.Video.Thumbnails.getThumbnail(context.getContentResolver(),
    //                    videoId,
    //                    MediaStore.Images.Thumbnails.MINI_KIND,
    //                    null);
    //        } else if (uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())) {
    //            long videoId = Long.parseLong(uri.getLastPathSegment());
    //
    //            return MediaStore.Video.Thumbnails.getThumbnail(context.getContentResolver(),
    //                    videoId,
    //                    MediaStore.Images.Thumbnails.MINI_KIND,
    //                    null);
    //        } else if (uri.toString().startsWith("file://") &&
    //                MediaUtil.isVideo(URLConnection.guessContentTypeFromName(uri.toString()))) {
    //            return ThumbnailUtils.createVideoThumbnail(uri.toString().replace("file://", ""),
    //                    MediaStore.Video.Thumbnails.MINI_KIND);
    //        }
    ////        else if (Build.VERSION.SDK_INT >= 23 &&
    ////                BlobProvider.isAuthority(uri) &&
    ////                MediaUtil.isVideo(BlobProvider.getMimeType(uri))) {
    ////            try {
    ////                MediaDataSource source = BlobProvider.getInstance().getMediaDataSource(context, uri);
    ////                return extractFrame(source, timeUs);
    ////            } catch (IOException e) {
    ////                Log.w(TAG, "Failed to extract frame for URI: " + uri, e);
    ////            }
    ////        }
    ////        else if (Build.VERSION.SDK_INT >= 23 &&
    ////                PartAuthority.isAttachmentUri(uri) &&
    ////                MediaUtil.isVideoType(PartAuthority.getAttachmentContentType(context, uri))) {
    ////            try {
    ////                AttachmentId attachmentId = PartAuthority.requireAttachmentId(uri);
    ////                MediaDataSource source = SignalDatabase.attachments().mediaDataSourceFor(attachmentId, false);
    ////                return extractFrame(source, timeUs);
    ////            } catch (IOException e) {
    ////                Log.w(TAG, "Failed to extract frame for URI: " + uri, e);
    ////            }
    ////        }
    //
    //        return null;
    //    }
    //    @RequiresApi(23)
    //    private static @Nullable Bitmap extractFrame(@Nullable MediaDataSource dataSource, long timeUs) throws IOException {
    //        if (dataSource == null) {
    //            return null;
    //        }
    //
    //        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
    //
    //        MediaMetadataRetrieverUtil.setDataSource(mediaMetadataRetriever, dataSource);
    //        return mediaMetadataRetriever.getFrameAtTime(timeUs);
    //    }
    //    public static boolean isInstantVideoSupported(Slide slide) {
    //        final Attachment attachment = slide.asAttachment();
    //        final boolean isIncremental = attachment.getIncrementalDigest() != null;
    //        final boolean hasIncrementalMacChunkSizeDefined = attachment.incrementalMacChunkSize > 0;
    //        final boolean contentTypeSupported = isVideoType(slide.getContentType());
    //        return isIncremental && contentTypeSupported && hasIncrementalMacChunkSizeDefined;
    //    }
    //    public static @Nullable String getDiscreteMimeType(@NonNull String mimeType) {
    //        final String[] sections = mimeType.split("/", 2);
    //        return sections.length > 1 ? sections[0] : null;
    //    }
    //    public static class ThumbnailData implements AutoCloseable {
    //
    //        @NonNull
    //        private final Bitmap bitmap;
    //        private final float aspectRatio;
    //
    //        public ThumbnailData(@NonNull Bitmap bitmap) {
    //            this.bitmap = bitmap;
    //            this.aspectRatio = (float) bitmap.getWidth() / (float) bitmap.getHeight();
    //        }
    //
    //        public @NonNull Bitmap getBitmap() {
    //            return bitmap;
    //        }
    //
    //        public float getAspectRatio() {
    //            return aspectRatio;
    //        }
    //
    //        public InputStream toDataStream() {
    //            return BitmapUtil.toCompressedJpeg(bitmap);
    //        }
    //
    //        @Override
    //        public void close() {
    //            bitmap.recycle();
    //        }
    //    }
    //    private static boolean isSupportedVideoUriScheme(@Nullable String scheme) {
    //        return ContentResolver.SCHEME_CONTENT.equals(scheme) ||
    //                ContentResolver.SCHEME_FILE.equals(scheme);
    //    }
    //    public enum SlideType {
    //        GIF,
    //        IMAGE,
    //        VIDEO,
    //        AUDIO,
    //        MMS,
    //        LONG_TEXT,
    //        VIEW_ONCE,
    //        DOCUMENT
    //    }

    fun getMediaWidthAndHeight(filePath: String, mimeType: String): Pair<Int, Int> {
        var width = 0
        var height = 0
        if (isImageType(mimeType)) {
            val options: BitmapFactory.Options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, options)
            width = options.outWidth
            height = options.outHeight
        } else if (isVideoType(mimeType)) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(filePath)
                val bitmap = mmr.frameAtTime
                if (bitmap != null) {
                    width = bitmap.width
                    height = bitmap.height
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                mmr.release()
            }
        }

        return width to height
    }

}
