package com.difft.android.linkeddevices

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.signal.DeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
















@Singleton
class LinkedDevicesCountStore @Inject constructor(
    private val deviceRepository: DeviceRepository,
) {
    private val _count = MutableStateFlow<Int?>(null)
    val count: StateFlow<Int?> = _count.asStateFlow()

    private val refreshMutex = Mutex()            
    private val writeLock = Any()                 
    private val generation = AtomicInteger(0)     

    
    suspend fun refresh() {
        if (!refreshMutex.tryLock()) {
            L.i { "[LinkedDevices] badge count refresh coalesced; in-flight request will satisfy it" }
            return
        }
        try {
            val genAtStart = generation.get()     
            val secondaryCount = deviceRepository.getDevices().size 
            synchronized(writeLock) {
                if (generation.get() != genAtStart) {   
                    L.i { "[LinkedDevices] badge count fetch discarded (superseded by push)" }
                } else {
                    _count.value = secondaryCount
                    L.i { "[LinkedDevices] badge count refreshed count=$secondaryCount" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w(e) { "[LinkedDevices] badge count refresh failed; keeping last-known" }
        } finally {
            refreshMutex.unlock()
        }
    }

    
    fun update(count: Int) {
        synchronized(writeLock) {
            generation.incrementAndGet()          
            _count.value = count
        }
        L.i { "[LinkedDevices] badge count pushed count=$count" }
    }
}
