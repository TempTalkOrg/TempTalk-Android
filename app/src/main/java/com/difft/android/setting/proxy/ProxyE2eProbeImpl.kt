package com.difft.android.setting.proxy

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.network.ChativeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class ProxyE2eProbeImpl @Inject constructor(
    private val globalConfigsManager: IGlobalConfigsManager,
    @ProxyProbe private val probeClient: ChativeHttpClient,
) : ProxyE2eProbe {

    





    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun probe(): Boolean {
        
        
        val hosts = withContext(Dispatchers.Default) { probeHosts() }
        if (hosts.isEmpty()) {
            L.w { "[Proxy] e2e probe: no probe host available" }
            return false
        }
        return withContext(Dispatchers.IO) {
            
            for (host in hosts) {
                if (probeOnce("https://$host/")) {
                    L.i { "[Proxy] e2e probe OK host=$host" }
                    return@withContext true
                }
            }
            L.w { "[Proxy] e2e probe FAILED hosts=${hosts.size}" }
            false
        }
    }

    
    private fun probeHosts(): List<String> =
        globalConfigsManager.getProxyTunnelChatDomains().distinct()

    




    private suspend fun probeOnce(url: String): Boolean {
        
        val request = ioScope.async {
            probeClient.httpService.getResponseBody(url, emptyMap(), emptyMap())
        }
        return try {
            request.await()
            true
        } catch (e: CancellationException) {
            
            
            ioScope.launch { request.cancel() }
            throw e
        } catch (e: HttpException) {
            true                       
        } catch (e: IOException) {
            false                      
        } catch (e: Exception) {
            false
        }
    }
}
