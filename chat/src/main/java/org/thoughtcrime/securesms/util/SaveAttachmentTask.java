package org.thoughtcrime.securesms.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.difft.android.base.log.lumberjack.L;
import com.difft.android.chat.R;
import com.kongzue.dialogx.dialogs.MessageDialog;
import com.kongzue.dialogx.dialogs.PopTip;
import com.kongzue.dialogx.interfaces.OnDialogButtonClickListener;

import util.MapUtil;
import util.StreamUtil;
import util.logging.Log;
import org.signal.libsignal.protocol.util.Pair;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.providers.MyBlobProvider;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SaveAttachmentTask extends ProgressDialogAsyncTask<SaveAttachmentTask.Attachment, Void, Pair<Integer, SaveAttachmentTask.Attachment>> {
    private static final String TAG = Log.tag(SaveAttachmentTask.class);

    static final int SUCCESS = 0;
    private static final int FAILURE = 1;
    private static final int WRITE_ACCESS_FAILURE = 2;

    private final WeakReference<Context> contextReference;

    private final int attachmentCount;

    private final Map<Uri, Set<String>> batchOperationNameCache = new HashMap<>();

    public SaveAttachmentTask(Context context) {
        this(context, 1);
    }

    public SaveAttachmentTask(Context context, int count) {
        super(context, context.getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_attachments_to_sd_card, count, count));
        this.contextReference = new WeakReference<>(context);
        this.attachmentCount = count;
    }

    @Override
    protected Pair<Integer, Attachment> doInBackground(Attachment... attachments) {
        if (attachments == null || attachments.length == 0) {
            throw new AssertionError("must pass in at least one attachment");
        }

        try {
            Context context = contextReference.get();

            if (!StorageUtil.canWriteToMediaStore()) {
                return new Pair<>(WRITE_ACCESS_FAILURE, null);
            }

            if (context == null) {
                return new Pair<>(FAILURE, null);
            }

            for (Attachment attachment : attachments) {
                if (attachment != null) {
                    String directory = saveAttachment(context, attachment);
                    if (directory == null) {
                        return new Pair<>(FAILURE, null);
                    }
                }
            }

            if (attachments.length > 1) {
                return new Pair<>(SUCCESS, null);
            } else {
                return new Pair<>(SUCCESS, attachments[0]);
            }
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
            return new Pair<>(FAILURE, null);
        }
    }

    private @Nullable String saveAttachment(Context context, Attachment attachment) throws IOException {
        String contentType = Objects.requireNonNull(MediaUtil.getCorrectedMimeType(attachment.contentType));
        String fileName = attachment.fileName;

        if (fileName == null) {
            fileName = generateOutputFileName(contentType, attachment.date);
        }

        fileName = sanitizeOutputFileName(fileName);

        CreateMediaUriResult result = createMediaUri(getMediaStoreContentUriForType(contentType), contentType, fileName);
        ContentValues updateValues = new ContentValues();

        final Uri mediaUri = result.mediaUri;

        if (mediaUri == null) {
            return null;
        }

        try (InputStream inputStream = PartAuthority.getAttachmentStream(context, attachment.uri)) {

            if (inputStream == null) {
                return null;
            }

            if (Objects.equals(result.outputUri.getScheme(), ContentResolver.SCHEME_FILE)) {
                try (OutputStream outputStream = new FileOutputStream(mediaUri.getPath())) {
                    StreamUtil.copy(inputStream, outputStream);
                    MediaScannerConnection.scanFile(context, new String[]{mediaUri.getPath()}, new String[]{contentType}, null);
                }
            } else {
                try (OutputStream outputStream = context.getContentResolver().openOutputStream(mediaUri, "w")) {
                    long total = StreamUtil.copy(inputStream, outputStream);
                    if (total > 0) {
                        updateValues.put(MediaStore.MediaColumns.SIZE, total);
                    }
                }
            }
            if (attachment.shouldDeleteOriginalFile) {
                MyBlobProvider.getInstance().delete(attachment.uri);
            }
        }

        if (Build.VERSION.SDK_INT > 28) {
            updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
        }

        if (updateValues.size() > 0) {
            getContext().getContentResolver().update(mediaUri, updateValues, null, null);
        }

        return result.outputUri.getLastPathSegment();
    }

    private @NonNull Uri getMediaStoreContentUriForType(@NonNull String contentType) {
        if (contentType.startsWith("video/")) {
            return StorageUtil.getVideoUri();
        } else if (contentType.startsWith("audio/")) {
            return StorageUtil.getAudioUri();
        } else if (contentType.startsWith("image/")) {
            return StorageUtil.getImageUri();
        } else {
            return StorageUtil.getDownloadUri();
        }
    }

    private @Nullable File ensureExternalPath(@Nullable File path) {
        if (path != null && path.exists()) {
            return path;
        }

        if (path == null) {
            File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documents.exists() || documents.mkdirs()) {
                return documents;
            } else {
                return null;
            }
        }

        if (path.mkdirs()) {
            return path;
        } else {
            return null;
        }
    }

    /**
     * Returns a path to a shared media (or documents) directory for the type of the file.
     * <p>
     * Note that this method attempts to create a directory if the path returned from
     * Environment object does not exist yet. The attempt may fail in which case it attempts
     * to return the default "Document" path. It finally returns null if it also fails.
     * Otherwise it returns the absolute path to the directory.
     *
     * @param contentType a MIME type of a file
     * @return an absolute path to a directory or null
     */
    private @Nullable String getExternalPathForType(String contentType) {
        File storage = null;
        if (contentType.startsWith("video/")) {
            storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        } else if (contentType.startsWith("audio/")) {
            storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        } else if (contentType.startsWith("image/")) {
            storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        }

        storage = ensureExternalPath(storage);
        if (storage == null) {
            return null;
        }

        return storage.getAbsolutePath();
    }

    private String generateOutputFileName(@NonNull String contentType, long timestamp) {
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        String extension = mimeTypeMap.getExtensionFromMimeType(contentType);
        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");
        String base = "signal-" + dateFormatter.format(timestamp);

        if (extension == null) extension = "attach";

        return base + "." + extension;
    }

    private String sanitizeOutputFileName(@NonNull String fileName) {
        return new File(fileName).getName();
    }

    private @NonNull CreateMediaUriResult createMediaUri(@NonNull Uri outputUri, @NonNull String contentType, @NonNull String fileName)
            throws IOException {
        String[] fileParts = getFileNameParts(fileName);
        String base = fileParts[0];
        String extension = fileParts[1];
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);

        if (MediaUtil.isOctetStream(mimeType) && MediaUtil.isImageVideoOrAudioType(contentType)) {
            Log.d(TAG, "MimeTypeMap returned octet stream for media, changing to provided content type [" + contentType + "] instead.");
            mimeType = contentType;
        }

        if (MediaUtil.isOctetStream(mimeType)) {
            if (outputUri.equals(StorageUtil.getAudioUri())) {
                mimeType = "audio/*";
            } else if (outputUri.equals(StorageUtil.getVideoUri())) {
                mimeType = "video/*";
            } else if (outputUri.equals(StorageUtil.getImageUri())) {
                mimeType = "image/*";
            }
        }

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        contentValues.put(MediaStore.MediaColumns.DATE_ADDED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
        contentValues.put(MediaStore.MediaColumns.DATE_MODIFIED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));

        if (Build.VERSION.SDK_INT > 28) {
            int i = 0;
            String displayName = fileName;

            while (pathInCache(outputUri, displayName) || displayNameTaken(outputUri, displayName)) {
                displayName = base + "-" + (++i) + "." + extension;
            }

            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1);
            putInCache(outputUri, displayName);
        } else if (Objects.equals(outputUri.getScheme(), ContentResolver.SCHEME_FILE)) {
            File outputDirectory = new File(outputUri.getPath());
            File outputFile = new File(outputDirectory, base + "." + extension);

            int i = 0;
            while (pathInCache(outputUri, outputFile.getPath()) || outputFile.exists()) {
                outputFile = new File(outputDirectory, base + "-" + (++i) + "." + extension);
            }

            if (outputFile.isHidden()) {
                throw new IOException("Specified name would not be visible");
            }

            putInCache(outputUri, outputFile.getPath());
            return new CreateMediaUriResult(outputUri, Uri.fromFile(outputFile));
        } else {
            String dir = getExternalPathForType(contentType);
            if (dir == null) {
                throw new IOException(String.format(Locale.US, "Path for type: %s was not available", contentType));
            }

            String outputFileName = fileName;
            String dataPath = String.format("%s/%s", dir, outputFileName);
            int i = 0;
            while (pathInCache(outputUri, dataPath) || pathTaken(outputUri, dataPath)) {
                Log.d(TAG, "The content exists. Rename and check again.");
                outputFileName = base + "-" + (++i) + "." + extension;
                dataPath = String.format("%s/%s", dir, outputFileName);
            }
            putInCache(outputUri, outputFileName);
            contentValues.put(MediaStore.MediaColumns.DATA, dataPath);
        }

        try {
            return new CreateMediaUriResult(outputUri, getContext().getContentResolver().insert(outputUri, contentValues));
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException || e.getCause() instanceof IllegalArgumentException) {
                Log.w(TAG, "Unable to create uri in " + outputUri + " with mimeType [" + mimeType + "]");
                return new CreateMediaUriResult(StorageUtil.getDownloadUri(), getContext().getContentResolver().insert(StorageUtil.getDownloadUri(), contentValues));
            } else {
                throw e;
            }
        }
    }

    private void putInCache(@NonNull Uri outputUri, @NonNull String dataPath) {
        Set<String> pathSet = MapUtil.getOrDefault(batchOperationNameCache, outputUri, new HashSet<>());
        if (!pathSet.add(dataPath)) {
            throw new IllegalStateException("Path already used in data set.");
        }

        batchOperationNameCache.put(outputUri, pathSet);
    }

    private boolean pathInCache(@NonNull Uri outputUri, @NonNull String dataPath) {
        Set<String> pathSet = batchOperationNameCache.get(outputUri);
        if (pathSet == null) {
            return false;
        }

        return pathSet.contains(dataPath);
    }

    private boolean pathTaken(@NonNull Uri outputUri, @NonNull String dataPath) throws IOException {
        try (Cursor cursor = getContext().getContentResolver().query(outputUri,
                new String[]{MediaStore.MediaColumns.DATA},
                MediaStore.MediaColumns.DATA + " = ?",
                new String[]{dataPath},
                null)) {
            if (cursor == null) {
                throw new IOException("Something is wrong with the filename to save");
            }
            return cursor.moveToFirst();
        }
    }

    private boolean displayNameTaken(@NonNull Uri outputUri, @NonNull String displayName) throws IOException {
        try (Cursor cursor = getContext().getContentResolver().query(outputUri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                new String[]{displayName},
                null)) {
            if (cursor == null) {
                throw new IOException("Something is wrong with the displayName to save");
            }

            return cursor.moveToFirst();
        }
    }

    private String[] getFileNameParts(String fileName) {
        String[] result = new String[2];
        String[] tokens = fileName.split("\\.(?=[^\\.]+$)");

        result[0] = tokens[0];

        if (tokens.length > 1) {
            result[1] = tokens[1];
        } else {
            result[1] = "";
        }

        return result;
    }

    @Override
    protected void onPostExecute(final Pair<Integer, Attachment> result) {
        super.onPostExecute(result);
        final Context context = contextReference.get();
        if (context == null) return;
        Attachment attachment = result.second();
        switch (result.first()) {
            case FAILURE:
                if (attachment != null) {
                    L.w(() -> "Failed to save attachment to storage:" + attachment.uri);
                } else {
                    L.w(() -> "Failed to save attachment to storage, null attachment");
                }
                PopTip.show(context.getResources().getQuantityText(R.plurals.ConversationFragment_error_while_saving_attachments_to_sd_card, attachmentCount));
                break;
            case SUCCESS:
                if (attachment != null) {
                    if (attachment.shouldShowToast) {
                        L.i(() -> "Success to save attachment to storage:" + attachment.uri);
                        if (attachment.contentType.startsWith("video/") || attachment.contentType.startsWith("image/")) {
                            PopTip.show(R.string.SaveAttachmentTask_saved_to_album);
                        } else if (attachment.contentType.startsWith("audio/")) {
                            PopTip.show(R.string.SaveAttachmentTask_saved_to_audio);
                        } else {
                            PopTip.show(R.string.SaveAttachmentTask_saved_to_downloads);
                        }
                    }
                } else {
                    PopTip.show(R.string.SaveAttachmentTask_saved_to_downloads);
                }
                break;
            case WRITE_ACCESS_FAILURE:
                PopTip.show(R.string.ConversationFragment_unable_to_write_to_sd_card_exclamation);
                break;
        }
    }

    public static class Attachment {
        public Uri uri;
        public String fileName;
        public String contentType;
        public long date;
        public boolean shouldDeleteOriginalFile;
        public boolean shouldShowToast;


        public Attachment(@NonNull Uri uri,
                          @NonNull String contentType,
                          long date,
                          @Nullable String fileName,
                          boolean shouldDeleteOriginalFile,
                          boolean shouldShowToast) {
            if (date < 0) {
                throw new AssertionError("uri, content type, and date must all be specified");
            }
            this.uri = uri;
            this.fileName = fileName;
            this.contentType = contentType;
            this.date = date;
            this.shouldDeleteOriginalFile = shouldDeleteOriginalFile;
            this.shouldShowToast = shouldShowToast;
        }
    }

    private static class CreateMediaUriResult {
        final Uri outputUri;
        final Uri mediaUri;

        private CreateMediaUriResult(Uri outputUri, Uri mediaUri) {
            this.outputUri = outputUri;
            this.mediaUri = mediaUri;
        }
    }

    public static void showWarningDialog(Context context, OnDialogButtonClickListener<MessageDialog> onAcceptListener) {
        showWarningDialog(context, onAcceptListener, 1);
    }

    public static void showWarningDialog(Context context, OnDialogButtonClickListener<MessageDialog> onAcceptListener, int count) {
        MessageDialog.show(
                context.getString(R.string.ConversationFragment_save_to_sd_card),
                context.getResources().getQuantityString(R.plurals.ConversationFragment_saving_n_media_to_storage_warning, count, count),
                context.getString(R.string.chat_dialog_ok),
                context.getString(R.string.chat_dialog_cancel)
        ).setOkButton(onAcceptListener);
    }
}

