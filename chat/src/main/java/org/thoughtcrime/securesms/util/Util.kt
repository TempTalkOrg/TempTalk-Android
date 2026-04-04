package org.thoughtcrime.securesms.util

import android.app.ActivityManager
import com.difft.android.base.utils.Base64
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.TextUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object Util {

    private const val DEFAULT_CLIPBOARD_EXPIRATION_SECONDS = 5 * 60

    // region String join methods

    @JvmStatic
    fun join(list: Array<String>, delimiter: String): String =
        list.joinToString(delimiter)

    @JvmStatic
    fun <T> join(list: Collection<T>, delimiter: String): String =
        list.joinToString(delimiter)

    @JvmStatic
    fun join(list: LongArray, delimiter: String): String =
        list.joinToString(delimiter)

    @JvmStatic
    fun join(list: List<Long>, delimiter: String): String =
        list.joinToString(delimiter)

    @JvmStatic
    @SafeVarargs
    fun <E> join(vararg lists: List<E>): List<E> =
        lists.flatMap { it }

    // endregion

    // region Collection utilities

    @JvmStatic
    fun isEmpty(collection: Collection<*>?): Boolean =
        collection.isNullOrEmpty()

    @JvmStatic
    fun isEmpty(charSequence: CharSequence?): Boolean =
        charSequence.isNullOrEmpty()

    @JvmStatic
    fun <K, V> getOrDefault(map: Map<K, V>, key: K, defaultValue: V): V =
        map[key] ?: defaultValue

    // endregion

    // region Threading

    @JvmStatic
    fun wait(lock: Any, timeout: Long) {
        try {
            (lock as java.lang.Object).wait(timeout)
        } catch (ie: InterruptedException) {
            throw AssertionError(ie)
        }
    }

    // endregion

    // region String split

    @JvmStatic
    fun split(source: String?, delimiter: String): List<String> {
        if (TextUtils.isEmpty(source)) {
            return emptyList()
        }
        return source!!.split(delimiter)
    }

    // endregion

    // region Byte array operations

    @JvmStatic
    fun split(input: ByteArray, firstLength: Int, secondLength: Int): Array<ByteArray> {
        val parts = arrayOf(ByteArray(firstLength), ByteArray(secondLength))
        System.arraycopy(input, 0, parts[0], 0, firstLength)
        System.arraycopy(input, firstLength, parts[1], 0, secondLength)
        return parts
    }

    @JvmStatic
    fun combine(vararg elements: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (element in elements) {
            baos.write(element)
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun trim(input: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        System.arraycopy(input, 0, result, 0, length)
        return result
    }

    // endregion

    // region Secret generation

    @JvmStatic
    fun getSecret(size: Int): String =
        Base64.encodeBytes(getSecretBytes(size))

    @JvmStatic
    fun getSecretBytes(size: Int): ByteArray =
        getSecretBytes(SecureRandom(), size)

    @JvmStatic
    fun getSecretBytes(secureRandom: SecureRandom, size: Int): ByteArray {
        val secret = ByteArray(size)
        secureRandom.nextBytes(secret)
        return secret
    }

    // endregion

    // region Object utilities

    @JvmStatic
    fun equals(a: Any?, b: Any?): Boolean =
        a == b || (a != null && a == b)

    @JvmStatic
    fun hashCode(vararg objects: Any?): Int =
        objects.contentHashCode()

    @JvmStatic
    fun uri(uri: String?): Uri? =
        uri?.let { Uri.parse(it) }

    // endregion

    // region Device utilities

    @JvmStatic
    fun isLowMemory(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.isLowRamDevice || activityManager.largeMemoryClass <= 64
    }

    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int =
        value.coerceIn(min, max)

    @JvmStatic
    fun clamp(value: Float, min: Float, max: Float): Float =
        value.coerceIn(min, max)

    /**
     * Returns half of the difference between the given length, and the length when scaled by the
     * given scale.
     */
    @JvmStatic
    fun halfOffsetFromScale(length: Int, scale: Float): Float {
        val scaledLength = length * scale
        return (length - scaledLength) / 2
    }

    // endregion

    // region Clipboard operations

    /**
     * Copy text to clipboard with automatic expiration (default 5 minutes).
     * Uses a special label to identify our content and avoid clearing content from other apps.
     */
    @JvmStatic
    fun copyToClipboard(context: Context, text: CharSequence) {
        copyToClipboard(context, text, DEFAULT_CLIPBOARD_EXPIRATION_SECONDS)
    }

    /**
     * Copy text to clipboard with specified expiration time.
     *
     * @param context          Application context
     * @param text             Text to copy
     * @param expiresInSeconds Time in seconds before clipboard is automatically cleared.
     *                         Use 0 to disable auto-clear.
     */
    @JvmStatic
    fun copyToClipboard(context: Context, text: CharSequence, expiresInSeconds: Int) {
        // Use special label to identify content copied by our app
        val clipData = ClipData.newPlainText(ClearClipboardAlarmReceiver.CLIPBOARD_LABEL, text)
        // Mark as sensitive to prevent clipboard preview and cross-device sync (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clipData.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        ServiceUtil.getClipboardManager(context).setPrimaryClip(clipData)
        ToastUtil.show(R.string.chat_message_action_copied)

        // Schedule clipboard clear if expiration time is set
        if (expiresInSeconds > 0) {
            scheduleClipboardClear(context, expiresInSeconds)
        }
    }

    /**
     * Schedule a clipboard clear after the specified delay.
     * Can be called directly when copying content outside of Util.copyToClipboard (e.g., file copy).
     */
    @JvmStatic
    fun scheduleClipboardClear(context: Context, delaySeconds: Int) {
        val alarmManager = ServiceUtil.getAlarmManager(context)
        val intent = Intent(context, ClearClipboardAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds.toLong()),
            pendingIntent
        )
    }

    // endregion
}