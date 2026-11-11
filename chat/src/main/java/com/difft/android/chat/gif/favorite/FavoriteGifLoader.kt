package com.difft.android.chat.gif.favorite

import android.net.Uri
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBFavoriteGifModel
import org.difft.app.database.models.FavoriteGifModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton




@Singleton
class FavoriteGifLoader @Inject constructor(
    private val ciphertextFetcher: FavoriteCiphertextFetcher,
) {
    
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, Mutex>()

    






    suspend fun resolve(fileHash: String): Uri? = withContext(Dispatchers.IO) {
        lockFor(fileHash).withLock {
            val row = wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(fileHash))
            if (row == null) {
                L.w { "[FavoriteGifLoader] no favorite row fileHash=$fileHash" }
                return@withLock null
            }
            val attachmentId = row.attachmentId
            if (attachmentId.isNullOrEmpty()) {
                L.w { "[FavoriteGifLoader] missing attachmentId fileHash=$fileHash" }
                return@withLock null
            }
            
            
            
            if (!hasUsableKey(row)) {
                L.w { "[FavoriteGifLoader] missing/short encKey fileHash=$fileHash" }
                return@withLock null
            }

            val encFile = FavoriteEncryptedAttachmentProvider.encryptedFile(attachmentId)
            if (EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encFile)) {
                return@withLock FavoriteEncryptedAttachmentProvider.contentUri(attachmentId) 
            }
            
            
            if (!ciphertextFetcher.downloadCiphertextTo(row.authorizeId, fileHash, encFile)) return@withLock null
            FavoriteEncryptedAttachmentProvider.contentUri(attachmentId)
        }
    }

    
    private fun hasUsableKey(row: FavoriteGifModel): Boolean {
        val enc = row.encKey
        if (enc.isNullOrEmpty()) return false
        return runCatching { Base64.decode(enc, Base64.NO_WRAP).size }.getOrDefault(0) >= 64
    }

    
    
    
    private suspend fun lockFor(fileHash: String): Mutex = inFlightLock.withLock {
        inFlight.getOrPut(fileHash) { Mutex() }
    }
}
