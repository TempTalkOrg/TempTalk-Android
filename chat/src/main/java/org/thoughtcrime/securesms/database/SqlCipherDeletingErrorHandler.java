package org.thoughtcrime.securesms.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;

import com.difft.android.base.log.lumberjack.L;

import net.zetetic.database.DatabaseErrorHandler;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

import util.CursorUtil;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;

/**
 * Prints some diagnostics and then deletes the original so the database can be recreated.
 * This should only be used for database that contain "unimportant" data, like logs.
 * Otherwise, you should use {@link SqlCipherErrorHandler}.
 */
public final class SqlCipherDeletingErrorHandler implements DatabaseErrorHandler {

    private static final String TAG = L.INSTANCE.tag(SqlCipherDeletingErrorHandler.class);

    private final String databaseName;

    public SqlCipherDeletingErrorHandler(@NonNull String databaseName) {
        this.databaseName = databaseName;
    }

    @Override
    public void onCorruption(SQLiteDatabase db, SQLiteException e) {
        String message = e.getMessage();
        try {
            L.e(() -> TAG + " Database '" + databaseName + "' corrupted! Message: " + message + ". Going to try to run some diagnostics.");

            L.w(() -> TAG + " ===== PRAGMA integrity_check =====");
            try (Cursor cursor = db.rawQuery("PRAGMA integrity_check", null)) {
                while (cursor.moveToNext()) {
                    L.w(() -> TAG + " " + CursorUtil.readRowAsString(cursor));
                }
            } catch (Throwable t) {
                L.e(() -> TAG + " Failed to do integrity_check!" + t);
            }

            L.w(() -> TAG + " ===== PRAGMA cipher_integrity_check =====");
            try (Cursor cursor = db.rawQuery("PRAGMA cipher_integrity_check", null)) {
                while (cursor.moveToNext()) {
                    L.w(() -> TAG + " " + CursorUtil.readRowAsString(cursor));
                }
            } catch (Throwable t) {
                L.e(() -> TAG + " Failed to do cipher_integrity_check!" + t);
            }
        } catch (Throwable t) {
            L.e(() -> TAG + " Failed to run diagnostics!" + t);
        } finally {
            L.w(() -> TAG + " Deleting database " + databaseName);
            ApplicationDependencies.getApplication().deleteDatabase(databaseName);
        }
    }
}
