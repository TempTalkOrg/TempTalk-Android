package util

import android.database.Cursor
import java.util.Optional

object CursorUtil {

    @JvmStatic
    fun requireString(cursor: Cursor, column: String): String =
        cursor.getString(cursor.getColumnIndexOrThrow(column))

    @JvmStatic
    fun requireInt(cursor: Cursor, column: String): Int =
        cursor.getInt(cursor.getColumnIndexOrThrow(column))

    @JvmStatic
    fun requireFloat(cursor: Cursor, column: String): Float =
        cursor.getFloat(cursor.getColumnIndexOrThrow(column))

    @JvmStatic
    fun requireLong(cursor: Cursor, column: String): Long =
        cursor.getLong(cursor.getColumnIndexOrThrow(column))

    @JvmStatic
    fun requireBoolean(cursor: Cursor, column: String): Boolean =
        requireInt(cursor, column) != 0

    @JvmStatic
    fun requireBlob(cursor: Cursor, column: String): ByteArray? =
        cursor.getBlob(cursor.getColumnIndexOrThrow(column))

    @JvmStatic
    fun isNull(cursor: Cursor, column: String): Boolean =
        cursor.isNull(cursor.getColumnIndexOrThrow(column))

    @JvmStatic
    fun getString(cursor: Cursor, column: String): Optional<String> =
        if (cursor.getColumnIndex(column) < 0) {
            Optional.empty()
        } else {
            Optional.ofNullable(requireString(cursor, column))
        }

    @JvmStatic
    fun getInt(cursor: Cursor, column: String): Optional<Int> =
        if (cursor.getColumnIndex(column) < 0) {
            Optional.empty()
        } else {
            Optional.of(requireInt(cursor, column))
        }

    @JvmStatic
    fun getBoolean(cursor: Cursor, column: String): Optional<Boolean> =
        if (cursor.getColumnIndex(column) < 0) {
            Optional.empty()
        } else {
            Optional.of(requireBoolean(cursor, column))
        }

    @JvmStatic
    fun getBlob(cursor: Cursor, column: String): Optional<ByteArray> =
        if (cursor.getColumnIndex(column) < 0) {
            Optional.empty()
        } else {
            Optional.ofNullable(requireBlob(cursor, column))
        }
}
