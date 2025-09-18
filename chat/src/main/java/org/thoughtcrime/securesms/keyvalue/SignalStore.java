package org.thoughtcrime.securesms.keyvalue;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.thoughtcrime.securesms.database.KeyValueDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;

/**
 * Simple, encrypted key-value store.
 */
public final class SignalStore {

    private KeyValueStore store;

    private final AccountValues accountValues;
    private final MiscellaneousValues misc;
    private final RemoteConfigValues remoteConfigValues;
    private final EmojiValues emojiValues;
    private final ImageEditorValues imageEditorValues;

    private static volatile SignalStore instance;

    private static @NonNull
    SignalStore getInstance() {
        if (instance == null) {
            synchronized (SignalStore.class) {
                if (instance == null) {
                    instance = new SignalStore(new KeyValueStore(KeyValueDatabase.getInstance(ApplicationDependencies.getApplication())));
                }
            }
        }

        return instance;
    }

    private SignalStore(@NonNull KeyValueStore store) {
        this.store = store;
        this.accountValues = new AccountValues(store);
        this.misc = new MiscellaneousValues(store);
        this.remoteConfigValues = new RemoteConfigValues(store);
        this.emojiValues = new EmojiValues(store);
        this.imageEditorValues = new ImageEditorValues(store);
    }

    /**
     * Forces the store to re-fetch all of it's data from the database.
     * Should only be used for testing!
     */
    @VisibleForTesting
    public static void resetCache() {
        getInstance().store.resetCache();
    }

    /**
     * Restoring a backup changes the underlying disk values, so the cache needs to be reset.
     */
    public static void onPostBackupRestore() {
        getInstance().store.resetCache();
    }

    public static @NonNull
    AccountValues account() {
        return getInstance().accountValues;
    }

    public static void blockUntilAllWritesFinished() {
        getStore().blockUntilAllWritesFinished();
    }

    public static @NonNull
    MiscellaneousValues misc() {
        return getInstance().misc;
    }

    private static @NonNull
    KeyValueStore getStore() {
        return getInstance().store;
    }

    /**
     * Allows you to set a custom KeyValueStore to read from. Only for testing!
     */
    @VisibleForTesting
    public static void inject(@NonNull KeyValueStore store) {
        instance = new SignalStore(store);
    }

    public static @NonNull
    RemoteConfigValues remoteConfigValues() {
        return getInstance().remoteConfigValues;
    }

    public static @NonNull EmojiValues emojiValues() {
        return getInstance().emojiValues;
    }

    public static @NonNull ImageEditorValues imageEditorValues() {
        return getInstance().imageEditorValues;
    }

}
