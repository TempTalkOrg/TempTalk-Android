package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.wcdb
import java.util.concurrent.ConcurrentHashMap






class MessageContactsCacheUtil {

    private val cache = ConcurrentHashMap<String, ContactorModel>()

    




    fun getContactor(id: String): ContactorModel? {
        return cache[id]
    }

    



    suspend fun loadContactors(ids: Set<String>, preferredGid: String? = null) {
        if (ids.isEmpty()) return

        val missingIds = ids.filter { cache[it] == null }.toSet()
        if (missingIds.isEmpty()) return

        val contactors = withContext(Dispatchers.IO) {
            wcdb.getContactorsFromAllTable(missingIds.toList(), preferredGid)
        }

        contactors.forEach { contactor ->
            cache[contactor.id] = contactor
        }

        L.d { "[MessageContactsCache] loaded=${contactors.size} cacheSize=${cache.size}" }
    }

    



    fun put(contactor: ContactorModel) {
        cache[contactor.id] = contactor
    }

    



    fun remove(id: String) {
        cache.remove(id)
    }

    



    fun remove(ids: Set<String>) {
        ids.forEach { cache.remove(it) }
    }

    


    fun clear() {
        cache.clear()
    }

    



    fun getCachedIds(): Set<String> {
        return cache.keys.toSet()
    }

    


    fun size(): Int {
        return cache.size
    }

    companion object {
        




        fun collectContactIds(messages: List<ChatMessage>): Set<String> {
            return buildSet {
                messages.forEach { msg ->
                    add(msg.authorId)

                    if (msg is TextChatMessage) {
                        msg.reactions?.forEach { reaction ->
                            add(reaction.uid)
                        }

                        msg.forwardContext?.forwards?.forEach { forward ->
                            add(forward.author)
                        }

                        msg.sharedContacts?.forEach { contact ->
                            contact.phone?.firstOrNull()?.value?.let { phoneId ->
                                add(phoneId)
                            }
                        }

                        msg.quote?.author?.let { authorId ->
                            add(authorId)
                        }
                    }
                }
            }
        }
    }
}
