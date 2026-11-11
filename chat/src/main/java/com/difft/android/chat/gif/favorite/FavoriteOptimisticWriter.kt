package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.chat.gif.GifSendInput
import com.difft.android.chat.gif.GifSendUseCase
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.MSG_TEMP_PREFIX
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.REMOTE_TEMP_PREFIX
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toModel
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toPendingSource
import com.difft.android.network.responses.FavoriteAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton





@Singleton
class FavoriteOptimisticWriter @Inject constructor(
    private val syncRepo: FavoriteSyncRepository,
    private val keyRepo: FavoriteKeyRepo,
    private val keyLifecycle: FavoriteKeyLifecycle,
    private val assetUploader: FavoriteAssetUploader,
    private val gifSendUseCase: GifSendUseCase,
    private val ciphertextFetcher: FavoriteCiphertextFetcher,
    private val writeRepo: FavoriteWriteRepository,
    @param:AppCoroutineScope private val appScope: CoroutineScope
) {
    
    
    
    
    private val resolvingTempHashes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    











    private val casMutex = Mutex()

    
    suspend fun favoriteRemote(giphyId: String, previewUrl: String, width: Int, height: Int) {
        val tempHash = REMOTE_TEMP_PREFIX + giphyId
        
        syncRepo.upsertRemotePlaceholder(tempHash, previewUrl, width, height)
        
        appScope.launch { resolveAndConfirm(tempHash) }
    }

    




    suspend fun favoriteMessage(ref: PendingSource.Message, width: Int, height: Int, size: Int, contentType: String) {
        val tempHash = MSG_TEMP_PREFIX + ref.accountFileHash
        
        if (syncRepo.hasConfirmed(ref.accountFileHash)) {
            L.i { "[FavoriteOptimistic] favorite message re-favorite existing hash=${ref.accountFileHash}" }
            syncRepo.markPendingRefavorite(ref.accountFileHash)
            appScope.launch {
                
                
                runCatching { casMutex.withLock { writeRepo.reconfirm(ref.accountFileHash) } }
                    .onFailure { L.w { "[FavoriteOptimistic] reconfirm deferred hash=${ref.accountFileHash}: ${it.message}" } }
            }
            return
        }
        syncRepo.upsertMessagePlaceholder(tempHash, ref, width, height, size, contentType)
        L.i { "[FavoriteOptimistic] favorite message enqueued msgId=${ref.messageId} hash=${ref.accountFileHash}" }
        appScope.launch { resolveAndConfirm(tempHash) }
    }

    




    suspend fun resolvePending(tempHash: String) = resolveAndConfirm(tempHash)

    





    suspend fun <T> runGuarded(block: suspend () -> T): T = casMutex.withLock { block() }

    


    suspend fun unfavorite(fileHash: String) {
        val row = syncRepo.firstByFileHash(fileHash) ?: return
        if (row.pending && row.attachmentId.isNullOrEmpty()) {
            syncRepo.removeByTempHash(fileHash)
            L.i { "[FavoriteOptimistic] unfavorite pending placeholder (local-only) tempHash=$fileHash" }
            return
        }
        
        syncRepo.markPendingRemoval(fileHash)
        val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
        L.i { "[FavoriteOptimistic] unfavorite tombstoned, syncing fileHash=$fileHash" }
        appScope.launch {
            
            
            
            runCatching { casMutex.withLock { writeRepo.syncUnfavorite(record) } }
                .onFailure { L.w { "[FavoriteOptimistic] unfavorite sync deferred fileHash=$fileHash: ${it.message}" } }
        }
    }

    






    private suspend fun resolveAndConfirm(tempHash: String) {
        
        if (!resolvingTempHashes.add(tempHash)) {
            L.i { "[FavoriteOptimistic] resolve already in flight, skipping duplicate tempHash=$tempHash" }
            return
        }
        try {
            val row = syncRepo.firstByFileHash(tempHash) ?: return 
            val pointer = when (val src = row.toPendingSource()) {
                is PendingSource.Remote -> {
                    val uri = gifSendUseCase.resolveSendable(GifSendInput.FromUrl(src.previewUrl, row.width, row.height))
                    val f = File(uri.path ?: error("resolved uri has no path"))
                    assetUploader.transStore(f, row.width, row.height).also { writeRepo.seedFavoritesCache(f, it.id, it.key) }
                }
                is PendingSource.Message -> {
                    assetUploader.transStoreKnown(src, row.width, row.height, row.size, row.contentType) {
                        downloadMessagePlaintext(src) 
                    }.also { p ->
                        
                        
                        localPlaintextIfPresent(src)?.let { (file, isTemp) ->
                            writeRepo.seedFavoritesCache(file, p.id, p.key)
                            if (isTemp) file.delete()
                        }
                    }
                }
                PendingSource.None -> return 
            }
            confirmOrCompensate(tempHash, pointer)
        } catch (e: Exception) {
            
            
            
            L.w { "[FavoriteOptimistic] favorite deferred (kept pending) tempHash=$tempHash: ${e.stackTraceToString()}" }
        } finally {
            resolvingTempHashes.remove(tempHash)
        }
    }

    









    private suspend fun confirmOrCompensate(tempHash: String, pointer: FavoriteAttachmentPointer) = casMutex.withLock {
        val firstCreate = keyLifecycle.ensureFavKey()
        if (!keyRepo.hasKey()) {
            L.i { "[FavoriteOptimistic] kept pending (no favKey) tempHash=$tempHash" }
            return@withLock
        }
        
        if (!firstCreate) syncRepo.pullAndDecrypt()
        try {
            val newVersion = writeRepo.putWithCas(
                FavoriteAction.FAVORITE,
                FavoriteRecord(pointer, syncRepo.cachedListVersion),
                wrappedFavKey = keyLifecycle.wrappedFavKeyForPut(firstCreate)
            )
            
            
            
            
            val confirmed = FavoriteRecord(pointer, newVersion).toModel().apply { pending = false }
            if (!syncRepo.confirmPlaceholder(tempHash, confirmed)) {
                
                
                
                L.i { "[FavoriteOptimistic] confirm skipped (unfavorited mid-flight), compensating tempHash=$tempHash fileHash=${pointer.fileHash}" }
                
                
                runCatching { FavoriteEncryptedAttachmentProvider.encryptedFile(pointer.id).delete() }
                compensateUnfavorite(pointer)
            }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteOptimistic] permanently rejected, rolled back tempHash=$tempHash: ${e.message}" }
            syncRepo.removeByTempHash(tempHash)
        } catch (e: CasExhaustedException) {
            
            L.w { "[FavoriteOptimistic] CAS exhausted, kept placeholder tempHash=$tempHash" }
        }
    }

    





    private suspend fun compensateUnfavorite(pointer: FavoriteAttachmentPointer) {
        if (!keyRepo.hasKey()) return
        
        syncRepo.pullAndDecrypt()
        try {
            writeRepo.putWithCas(FavoriteAction.UNFAVORITE, FavoriteRecord(pointer, syncRepo.cachedListVersion), wrappedFavKey = null)
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteOptimistic] compensating unfavorite permanently rejected fileHash=${pointer.fileHash}: ${e.message}" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteOptimistic] compensating unfavorite CAS exhausted fileHash=${pointer.fileHash}" }
        }
    }

    






    private suspend fun downloadMessagePlaintext(ref: PendingSource.Message): File = withContext(Dispatchers.IO) {
        val basePath = pendingMessageBasePath(ref.messageId, ref.fileName)
        val srcDir = File(application.cacheDir, "gif_fav_src").apply { mkdirs() }
        
        
        
        
        val safeName = File(ref.fileName).name.takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: ref.accountFileHash.hashCode().toString()
        
        
        
        resolveMessageGifPlaintext(application, basePath, ref.key)?.let { (file, isTemp) ->
            if (isTemp) return@withContext file
            val copy = File(srcDir, "${System.currentTimeMillis()}_$safeName")
            file.copyTo(copy, overwrite = true)
            return@withContext copy
        }
        
        
        val encTemp = File(srcDir, "${System.currentTimeMillis()}_${ref.accountFileHash.hashCode()}.encrypt")
        try {
            if (!ciphertextFetcher.downloadCiphertextTo(ref.authorizeId, ref.accountFileHash, encTemp)) {
                throw java.io.IOException("message ciphertext download failed messageId=${ref.messageId}")
            }
            val plainTemp = File(srcDir, "${System.currentTimeMillis()}_$safeName")
            com.difft.android.chat.util.FileDecryptionUtil.decryptFile(encTemp, plainTemp, ref.key) 
            plainTemp
        } finally {
            encTemp.delete()
        }
    }

    







    private suspend fun localPlaintextIfPresent(ref: PendingSource.Message): Pair<File, Boolean>? = withContext(Dispatchers.IO) {
        val basePath = pendingMessageBasePath(ref.messageId, ref.fileName)
        if (!EncryptedAttachmentAccess.isReadable(basePath)) return@withContext null
        resolveMessageGifPlaintext(application, basePath, ref.key)
    }
}
