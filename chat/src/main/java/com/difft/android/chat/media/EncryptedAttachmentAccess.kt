package com.difft.android.chat.media

import android.content.Context
import android.net.Uri
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.application
import java.io.File
import java.nio.charset.Charset






















object EncryptedAttachmentAccess {

    
    val authority: String get() = application.packageName + ".encryptedattachment"

    private const val PATH_PREFIX = "m"

    private const val IV_SIZE = 16
    private const val MAC_SIZE = 32

    
    private const val MIN_KEY_SIZE = 64

    



    fun isUsableKey(key: ByteArray?): Boolean = key != null && key.size >= MIN_KEY_SIZE

    
    fun encryptedFile(basePath: String): File = File("$basePath.encrypt")

    
    fun plaintextFile(basePath: String): File = File(basePath)

    








    fun hasEncrypted(basePath: String): Boolean = isStructurallyCompleteCiphertext(encryptedFile(basePath))

    




    fun isStructurallyCompleteCiphertext(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val cipherLen = file.length() - IV_SIZE - MAC_SIZE
        return cipherLen > 0 && cipherLen % 16L == 0L
    }

    
    fun hasPlaintext(basePath: String): Boolean = FileUtil.isFileValid(basePath)

    



    fun isReadable(basePath: String): Boolean = hasEncrypted(basePath) || hasPlaintext(basePath)

    







    fun isReadable(basePath: String, expectedPlainSize: Int): Boolean =
        hasEncrypted(basePath) || (hasPlaintext(basePath) &&
            (expectedPlainSize <= 0 || plaintextFile(basePath).length() == expectedPlainSize.toLong()))

    



    fun isLongTextReady(basePath: String, expectedPlainSize: Int): Boolean =
        isReadable(basePath, expectedPlainSize)

    









    fun contentUri(messageId: String, fileName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(PATH_PREFIX)
            .appendPath(messageId)
            .appendPath(fileName)
            .build()

    



    fun contentUri(context: Context, messageId: String, basePath: String): Uri =
        contentUri(messageId, File(basePath).name)

    





    fun contentUriFromBasePath(basePath: String): Uri {
        val f = File(basePath)
        val messageId = f.parentFile?.name.orEmpty()
        return contentUri(messageId, f.name)
    }

    








    fun imageGlideModel(basePath: String): Any =
        if (hasEncrypted(basePath)) contentUriFromBasePath(basePath) else File(basePath)

    














    fun exportContentUriIfEncrypted(messageId: String, basePath: String): Uri? =
        if (hasEncrypted(basePath)) contentUri(messageId, File(basePath).name) else null

    
















    fun readDecryptedText(context: Context, basePath: String, charset: Charset = Charsets.UTF_8): String? {
        if (hasPlaintext(basePath)) {
            return runCatching { plaintextFile(basePath).readText(charset) }.getOrNull()
        }
        if (!hasEncrypted(basePath)) return null
        val uri = contentUriFromBasePath(basePath)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.reader(charset).readText()
            }
        }.getOrNull()
    }

    internal fun parse(uri: Uri): Pair<String, String>? {
        val segments = uri.pathSegments
        if (segments.size < 3 || segments[0] != PATH_PREFIX) return null
        val messageId = segments[1]
        
        val fileName = segments.subList(2, segments.size).joinToString("/")
        if (messageId.isEmpty() || fileName.isEmpty()) return null
        return messageId to fileName
    }

    














    fun resolveContainedBasePath(messageId: String, fileName: String): String? {
        if (messageId.isEmpty() || fileName.isEmpty()) return null
        if (messageId.any { it == '/' || it == '\\' } || fileName.any { it == '/' || it == '\\' }) return null
        return try {
            val root = File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT)).canonicalFile
            val candidate = File(FileUtil.getMessageAttachmentFilePath(messageId) + fileName).canonicalFile
            val rootPrefix = root.path + File.separator
            if (candidate.path == root.path) null 
            else if ((candidate.path + File.separator).startsWith(rootPrefix)) candidate.path else null
        } catch (e: Exception) {
            null
        }
    }
}
