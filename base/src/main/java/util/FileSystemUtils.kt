package util

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Low-level file-system helpers (origin: Blankj AndroidUtilCode FileUtils/FileIOUtils, trimmed to
 * the members actually used in this codebase). App-domain file operations live in
 * [com.difft.android.base.utils.FileUtil].
 */
object FileSystemUtils {

    private const val BUFFER_SIZE = 524288

    /** Return the name of file. */
    @JvmStatic
    fun getFileName(file: File?): String {
        if (file == null) return ""
        return getFileName(file.absolutePath)
    }

    /** Return the name of file. */
    @JvmStatic
    fun getFileName(filePath: String?): String {
        if (TextUtils.isEmpty(filePath)) return ""
        val lastSep = filePath!!.lastIndexOf(File.separator)
        return if (lastSep == -1) filePath else filePath.substring(lastSep + 1)
    }

    /** Return the length (recursively for a directory). */
    @JvmStatic
    fun getLength(filePath: String?): Long = getLength(getFileByPath(filePath))

    /** Return the length (recursively for a directory). */
    @JvmStatic
    fun getLength(file: File?): Long {
        if (file == null) return 0
        return if (file.isDirectory) getDirLength(file) else getFileLength(file)
    }

    /** Return the length of file, resolving http(s) URLs via a HEAD-like content-length probe. */
    @JvmStatic
    fun getFileLength(filePath: String): Long {
        val isURL = filePath.matches("[a-zA-z]+://[^\\s]*".toRegex())
        if (isURL) {
            try {
                val conn = URL(filePath).openConnection() as HttpsURLConnection
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.connect()
                if (conn.responseCode == 200) {
                    return conn.contentLength.toLong()
                }
                return -1
            } catch (e: IOException) {
                L.w(e) { "[FileSystemUtils] getFileLength from URL failed" }
            }
        }
        return getFileLength(getFileByPath(filePath))
    }

    /** Convert bytes to an upper-case hex string. */
    @JvmStatic
    fun bytesToHex(input: ByteArray): String {
        val sb = StringBuilder(128)
        for (b in input) {
            val hex = Integer.toHexString(b.toInt() and 0xFF)
            if (hex.length < 2) {
                sb.append(0)
            }
            sb.append(hex)
        }
        return sb.toString().uppercase(Locale.ROOT)
    }

    /** Decode a condensed hex digest string to bytes. */
    @JvmStatic
    @Throws(IOException::class)
    fun decodeDigestHex(encoded: String): ByteArray {
        val data = encoded.toCharArray()
        val len = data.size

        if ((len and 0x01) != 0) {
            throw IOException("Odd number of characters.")
        }

        val out = ByteArray(len shr 1)

        var i = 0
        var j = 0
        while (j < len) {
            var f = Character.digit(data[j], 16) shl 4
            j++
            f = f or Character.digit(data[j], 16)
            j++
            out[i] = (f and 0xFF).toByte()
            i++
        }

        return out
    }

    /** Copy the directory or file. */
    @JvmStatic
    fun copy(srcPath: String?, destPath: String?): Boolean =
        copy(getFileByPath(srcPath), getFileByPath(destPath))

    // region internal helpers (transitive closure of the public methods above)

    private fun getFileByPath(filePath: String?): File? =
        if (TextUtils.isEmpty(filePath)) null else File(filePath!!)

    private fun isFile(file: File?): Boolean =
        file != null && file.exists() && file.isFile

    private fun getDirLength(dir: File): Long {
        if (!(dir.exists() && dir.isDirectory)) return 0
        var len: Long = 0
        val files = dir.listFiles()
        if (files != null && files.isNotEmpty()) {
            for (file in files) {
                len += if (file.isDirectory) getDirLength(file) else file.length()
            }
        }
        return len
    }

    private fun getFileLength(file: File?): Long {
        if (!isFile(file)) return -1
        return file!!.length()
    }

    private fun createOrExistsDir(file: File?): Boolean =
        file != null && (if (file.exists()) file.isDirectory else file.mkdirs())

    private fun createOrExistsFile(file: File?): Boolean {
        if (file == null) return false
        if (file.exists()) return file.isFile
        if (!createOrExistsDir(file.parentFile)) return false
        return try {
            file.createNewFile()
        } catch (e: IOException) {
            L.w(e) { "[FileSystemUtils] createOrExistsFile failed" }
            false
        }
    }

    private fun copy(src: File?, dest: File?): Boolean {
        if (src == null) return false
        return if (src.isDirectory) copyDir(src, dest) else copyFile(src, dest)
    }

    private fun copyDir(srcDir: File, destDir: File?): Boolean {
        if (destDir == null) return false
        val srcPath = srcDir.path + File.separator
        val destPath = destDir.path + File.separator
        if (destPath.contains(srcPath)) return false
        if (!srcDir.exists() || !srcDir.isDirectory) return false
        if (!createOrExistsDir(destDir)) return false
        val files = srcDir.listFiles()
        if (files != null && files.isNotEmpty()) {
            for (file in files) {
                val oneDestFile = File(destPath + file.name)
                if (file.isFile) {
                    if (!copyFile(file, oneDestFile)) return false
                } else if (file.isDirectory) {
                    if (!copyDir(file, oneDestFile)) return false
                }
            }
        }
        return true
    }

    private fun copyFile(srcFile: File?, destFile: File?): Boolean {
        if (srcFile == null || destFile == null) return false
        if (srcFile == destFile) return false
        if (!srcFile.exists() || !srcFile.isFile) return false
        if (destFile.exists()) {
            if (!destFile.delete()) {
                return false
            }
        }
        if (!createOrExistsDir(destFile.parentFile)) return false
        return try {
            writeFileFromIS(destFile, FileInputStream(srcFile))
        } catch (e: FileNotFoundException) {
            L.w(e) { "[FileSystemUtils] copyFile failed" }
            false
        }
    }

    private fun writeFileFromIS(file: File, input: InputStream): Boolean {
        if (!createOrExistsFile(file)) {
            L.e { "[FileSystemUtils] create file <$file> failed." }
            return false
        }
        var os: BufferedOutputStream? = null
        return try {
            os = BufferedOutputStream(FileOutputStream(file, false), BUFFER_SIZE)
            val data = ByteArray(BUFFER_SIZE)
            var len: Int
            while (input.read(data).also { len = it } != -1) {
                os.write(data, 0, len)
            }
            true
        } catch (e: IOException) {
            L.w(e) { "[FileSystemUtils] writeFileFromIS failed" }
            false
        } finally {
            StreamUtil.close(input)
            StreamUtil.close(os)
        }
    }

    // endregion
}
