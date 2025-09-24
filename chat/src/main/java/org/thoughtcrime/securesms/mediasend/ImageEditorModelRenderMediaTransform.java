package org.thoughtcrime.securesms.mediasend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.difft.android.base.log.lumberjack.L;
import com.difft.android.base.utils.FileUtil;
import com.luck.picture.lib.entity.LocalMedia;

import util.StreamUtil;
import util.logging.Log;
import org.signal.imageeditor.core.model.EditorModel;
import org.thoughtcrime.securesms.fonts.FontTypefaceProvider;
import org.thoughtcrime.securesms.mms.SentMediaQuality;
import org.thoughtcrime.securesms.providers.MyBlobProvider;
import org.thoughtcrime.securesms.util.MediaUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

import top.zibin.luban.Luban;

public final class ImageEditorModelRenderMediaTransform implements MediaTransform {

    private static final String TAG = Log.tag(ImageEditorModelRenderMediaTransform.class);

    @Nullable
    private final EditorModel modelToRender;
    @Nullable
    private final Point size;
    private final SentMediaQuality sentMediaQuality;


    public ImageEditorModelRenderMediaTransform(@Nullable EditorModel modelToRender, @Nullable Point size, SentMediaQuality sentMediaQuality) {
        this.modelToRender = modelToRender;
        this.size = size;
        this.sentMediaQuality = sentMediaQuality;
    }

    @WorkerThread
    @Override
    public @NonNull LocalMedia transform(@NonNull Context context, @NonNull LocalMedia media) {
        Bitmap bitmap = null;
        ByteArrayOutputStream outputStream = null;
        try {
            if (modelToRender != null) { //先生成编辑后的图片
                outputStream = new ByteArrayOutputStream();
                bitmap = modelToRender.render(context, size, new FontTypefaceProvider());
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);

                Uri uri = MyBlobProvider.getInstance()
                        .forData(outputStream.toByteArray())
                        .withMimeType(MediaUtil.IMAGE_JPEG)
                        .withFileName(media.getFileName())
                        .createForDraftAttachmentAsync(context).get();

                media.setRealPath(uri.getPath());
                media.setMimeType(MediaUtil.IMAGE_JPEG);
                media.setWidth(bitmap.getWidth());
                media.setHeight(bitmap.getHeight());
                media.setSize(outputStream.size());
            }
        } catch (Exception e) {
            L.w(() -> "Failed to render image. Using base image." + e);
        } finally {
            if (null != bitmap) {
                bitmap.recycle();
            }
            StreamUtil.close(outputStream);
        }

        try {
            if (sentMediaQuality == SentMediaQuality.STANDARD) { //需要被压缩
                List<File> file = Luban.with(context)
                        .load(media.getRealPath())
                        .ignoreBy(100)
                        .setTargetDir(FileUtil.INSTANCE.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY))
                        .setRenameListener(filePath -> media.getFileName())
                        .get();

                if (file != null && !file.isEmpty()) {
                    media.setRealPath(file.get(0).getPath());
                }
            }
        } catch (IOException e) {
            L.w(() -> "Failed to compress image. Using base image." + e);
        }

        return media;
    }
}
