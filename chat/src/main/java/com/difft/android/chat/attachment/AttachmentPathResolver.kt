package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.Attachment


















object AttachmentPathResolver {

    





    fun interface Migrator {
        







        fun migrateIfNeeded(attachment: Attachment, targetDirectory: String, legacyOwnerMessageId: String?): Boolean
    }

    
    @Volatile
    var migrator: Migrator? = null

    



    fun directoryKeyFor(attachment: Attachment): String = attachment.localId

    





    fun directoryKeyForRow(localId: String?): String? = localId?.takeIf { it.isNotEmpty() }

    
    fun directoryFor(attachment: Attachment): String =
        FileUtil.getMessageAttachmentFilePath(directoryKeyFor(attachment))

    



    fun fileFor(attachment: Attachment): String =
        directoryFor(attachment) + (attachment.fileName ?: "")

    








    fun stagingFileFor(localId: String, fileName: String): String =
        FileUtil.getMessageAttachmentFilePath(localId) + fileName

    







    fun materializedFileFor(attachment: Attachment, legacyOwnerMessageId: String?): String {
        migrator?.migrateIfNeeded(attachment, directoryFor(attachment), legacyOwnerMessageId)
        return fileFor(attachment)
    }
}
