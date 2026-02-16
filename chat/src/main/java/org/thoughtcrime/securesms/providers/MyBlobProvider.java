package org.thoughtcrime.securesms.providers;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.difft.android.base.log.lumberjack.L;
import com.difft.android.base.utils.FileUtil;

import util.StreamUtil;
import util.concurrent.TTExecutors;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;

public class MyBlobProvider {
    private static final String TAG = L.INSTANCE.tag(MyBlobProvider.class);

    private static final MyBlobProvider INSTANCE = new MyBlobProvider();

    public static MyBlobProvider getInstance() {
        return INSTANCE;
    }

    public BlobBuilder forData(@NonNull byte[] data) {
        return new BlobBuilder(new ByteArrayInputStream(data));
    }

    public BlobBuilder forData(@NonNull InputStream data) {
        return new BlobBuilder(data);
    }

    public synchronized @NonNull InputStream getStream(@NonNull Context context, @NonNull Uri uri) throws IOException {
        File file = new File(uri.getPath());
        if (!file.exists()) {
            throw new IOException("File does not exist for URI: " + uri);
        }
        return new FileInputStream(file);
    }

    public void delete(@NonNull Uri uri) {
        File file = new File(Objects.requireNonNull(uri.getPath()));
        if (file.exists()) {
            file.delete();
        }
    }

    public class BlobBuilder {

        private InputStream data;
        private String id;
        private String mimeType;
        private String fileName;

        private BlobBuilder(@NonNull InputStream data) {
            this.id = UUID.randomUUID().toString();
            this.data = data;
        }

        public BlobBuilder withMimeType(@NonNull String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public BlobBuilder withFileName(@Nullable String fileName) {
            this.fileName = fileName;
            return this;
        }

        @WorkerThread
        public Future<Uri> createForDraftAttachmentAsync(@NonNull Context context)
                throws IOException {
            File outputFile = new File(FileUtil.INSTANCE.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), buildFileName(id));
            OutputStream outputStream = new FileOutputStream(outputFile);

            final Uri uri = buildUri(context);

            return TTExecutors.BOUNDED.submit(() -> {
                try {
                    StreamUtil.copy(data, outputStream);
                    return uri;
                } catch (IOException e) {
                    delete(context, uri);
                    L.w(e, () -> "Error during write!");
                    throw e;
                }
            });
        }


        private @NonNull String buildFileName(@NonNull String id) {
            String suffix = "";
            if (mimeType != null) {
                switch (mimeType) {
                    case "image/jpeg":
                        suffix = ".jpg";
                        break;
                    case "image/png":
                        suffix = ".png";
                        break;
                    case "video/mp4":
                        suffix = ".mp4";
                        break;
                    case "audio/aac":
                        suffix = ".aac";
                        break;
                    default:
                        suffix = ".blob";
                        break;
                }
            }
            if (fileName != null) {
                return fileName;
            } else {
                return id + suffix;
            }
        }

        private @NonNull Uri buildUri(@NonNull Context context) {
            return Uri.fromFile(new File(FileUtil.INSTANCE.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), buildFileName(id)));
        }

//        private File getOrCreateDirectory(@NonNull Context context, @NonNull String directory) {
//            return new File(FileUtil.INSTANCE.getFilePath(FileUtil.FILE_DIR_ATTACHMENT));
//        }

        private void delete(@NonNull Context context, @NonNull Uri uri) {
            File file = new File(uri.getPath());
            if (file.exists()) {
                file.delete();
            }
        }
    }
}