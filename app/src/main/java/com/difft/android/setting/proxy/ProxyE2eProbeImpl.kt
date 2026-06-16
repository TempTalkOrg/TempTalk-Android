package com.difft.android.setting.proxy

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.network.ChativeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阶段 2 探针实现：取 self-cert host → 用专用探针 client 逐个探 → 多 host fallback。
 *
 * 与 :network 的 ProxyConfigProvider.extractGlobalSelfCertHosts 同款 certType=self 过滤语义
 * （见 ProxyConfigProvider.kt extractGlobalSelfCertHosts）；两处过滤刻意各自实现，改其一须同步另一。
 */
@Singleton
class ProxyE2eProbeImpl @Inject constructor(
    private val globalConfigsManager: IGlobalConfigsManager,
    @ProxyProbe private val probeClient: ChativeHttpClient,
) : ProxyE2eProbe {

    override suspend fun probe(): Boolean {
        // selfCertHosts() 内部经 IGlobalConfigsManager.getNewGlobalConfigs() → loadInitialConfigBlocking()
        // 含 runBlocking(Dispatchers.IO)。两条约束：① 不能放进下面的 withContext(Dispatchers.IO)——IO 池
        // 饱和时在 IO 线程上再 runBlocking(IO) 可能死锁；② 也不能留在 caller 的 Main 线程（viewModelScope
        // 默认 Main）——DataStore 未预热时该 runBlocking 会阻塞主线程（卡顿/ANR）。用 Dispatchers.Default：
        // 它独立于 IO 池（不会 IO 死锁），又把潜在阻塞移出主线程；稳态下 getNewGlobalConfigs() 命中缓存、开销可忽略。
        val hosts = withContext(Dispatchers.Default) { selfCertHosts() }
        if (hosts.isEmpty()) {
            L.w { "[Proxy] e2e probe: no self-cert host available" }
            return false
        }
        // 仅把真正的 OkHttp 网络 I/O 放进 IO 池。
        return withContext(Dispatchers.IO) {
            // 多 host fallback：任一成功即判通。
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

    /** 复用 certType=self 过滤逻辑；与 ProxyConfigProvider.extractGlobalSelfCertHosts 同款语义。 */
    private fun selfCertHosts(): List<String> {
        val data = globalConfigsManager.getNewGlobalConfigs()?.data ?: return emptyList()
        val fromHosts = data.hosts.orEmpty()
            .filter { it.certType.equals("self", ignoreCase = true) }
            .mapNotNull { it.name }
        val fromDomains = data.domains.orEmpty()
            .filter { it.certType.equals("self", ignoreCase = true) }
            .mapNotNull { it.domain }
        return (fromHosts + fromDomains).distinct()
    }

    /**
     * 硬约束：[url] **必须**是绝对 URL `https://$host/`（self-cert host）。
     * **绝不可传相对路径** —— 相对路径会回落到 probeClient 的 baseUrl（占位哨兵 https://probe.invalid/），
     * 既无法路由，更危险的是若 baseUrl 是真实 host 会绕过隧道直连、造成假阳性。探针恒用 @Url 绝对 URL。
     */
    private suspend fun probeOnce(url: String): Boolean = try {
        probeClient.httpService.getResponseBody(url, emptyMap(), emptyMap())
        true
    } catch (e: CancellationException) {
        throw e                    // 必须最先：CE ⊄ IOException 但 ⊂ Exception，不 rethrow 会吞协程取消 → 误写 FAILED
    } catch (e: HttpException) {
        true                       // 任意 HTTP 状态码（含 5xx，拦截器已关，原样抛）= 传输/路由已通
    } catch (e: IOException) {
        false                      // 连接/握手/超时 = 不可达（SSLHandshakeException ⊂ IOException）
    } catch (e: Exception) {
        false
    }
}
