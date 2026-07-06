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

/**
 * 阶段 2 探针实现：取探测 host → 用专用探针 client 逐个探 → 多 host fallback。
 *
 * 探测 host 取 **proxy chat 隧道域名**（`proxy.tunnelDomains.chat`，与代理开启时主流量实际
 * 使用的 host 同源，见 UrlManager.proxyChatDomains / ProxyConfigProvider.tunnelHosts）——
 * 探测验证的 host 即主流量会用的 host，避免「探 live host、走 proxy host」的一致性缺口。
 * 该取值本身已是「线上优先 + 预埋兜底」（见 IGlobalConfigsManager.getProxyTunnelChatDomains）。
 */
@Singleton
class ProxyE2eProbeImpl @Inject constructor(
    private val globalConfigsManager: IGlobalConfigsManager,
    @ProxyProbe private val probeClient: ChativeHttpClient,
) : ProxyE2eProbe {

    /**
     * 探针的网络请求在这个**独立 IO 作用域**里执行，而非作为调用方（探针 Job，运行在
     * viewModelScope 的 Main 线程上）的子协程。原因见 [probeOnce]：取消一个进行中的
     * Retrofit/OkHttp 调用，会在「发起取消的线程」上同步执行其 invokeOnCancellation；走代理隧道时
     * 它会关闭 Conscrypt TLS socket 并写出 close_notify（一次阻塞网络写）。而代理设置页在切换开关或
     * 销毁页面时会在 **Main 线程**取消探针 → NetworkOnMainThreadException 崩溃。把请求隔离到本作用域后，
     * Main 线程的取消只会解开 await()（不触碰 socket），socket 关闭恒由本 IO 作用域完成。
     * SupervisorJob 保证单次探测失败不连累作用域；@Singleton 使其与进程同生命周期。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun probe(): Boolean {
        // probeHosts() 可能经 IGlobalConfigsManager.getNewGlobalConfigs() → loadInitialConfigBlocking()
        // （仅预埋为空回退 live 时）含 runBlocking(Dispatchers.IO)。两条约束：① 不能放进下面的
        // withContext(Dispatchers.IO)——IO 池饱和时在 IO 线程上再 runBlocking(IO) 可能死锁；② 也不能留在
        // caller 的 Main 线程（viewModelScope 默认 Main）——DataStore 未预热时该 runBlocking 会阻塞主线程
        // （卡顿/ANR）。用 Dispatchers.Default：它独立于 IO 池（不会 IO 死锁），又把潜在阻塞移出主线程；
        // 预埋读取（主路径）命中 assets 缓存、开销可忽略。
        val hosts = withContext(Dispatchers.Default) { probeHosts() }
        if (hosts.isEmpty()) {
            L.w { "[Proxy] e2e probe: no probe host available" }
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

    /**
     * 探测 host：取 proxy chat 隧道域名（代理主流量实际使用的 host，与 ProxyConfigProvider 的
     * 隧道白名单同源）。该取值已是「线上优先 + 预埋兜底」并已归一化/去重。
     */
    private fun probeHosts(): List<String> =
        globalConfigsManager.getProxyTunnelChatDomains().distinct()

    /**
     * 硬约束：[url] **必须**是绝对 URL `https://$host/`（self-cert host）。
     * **绝不可传相对路径** —— 相对路径会回落到 probeClient 的 baseUrl（占位哨兵 https://probe.invalid/），
     * 既无法路由，更危险的是若 baseUrl 是真实 host 会绕过隧道直连、造成假阳性。探针恒用 @Url 绝对 URL。
     */
    private suspend fun probeOnce(url: String): Boolean {
        // 在 ioScope（而非调用方子协程）里发起请求，调用方的取消不会向下传播到它。
        val request = ioScope.async {
            probeClient.httpService.getResponseBody(url, emptyMap(), emptyMap())
        }
        return try {
            request.await()
            true
        } catch (e: CancellationException) {
            // 调用方（Main 线程的探针 Job / viewModelScope 销毁）取消时，await() 在此抛 CE，但 request
            // 仍在 ioScope 中存活——在 ioScope（IO 线程）里取消它，让 OkHttp 的 socket 关闭（close_notify
            // 网络写）发生在 IO 线程而非 Main，从根上消除 NetworkOnMainThreadException。必须最先捕获：
            // CE ⊄ IOException 但 ⊂ Exception，不 rethrow 会吞掉协程取消 → 误写 FAILED。
            ioScope.launch { request.cancel() }
            throw e
        } catch (e: HttpException) {
            true                       // 任意 HTTP 状态码（含 5xx，拦截器已关，原样抛）= 传输/路由已通
        } catch (e: IOException) {
            false                      // 连接/握手/超时 = 不可达（SSLHandshakeException ⊂ IOException）
        } catch (e: Exception) {
            false
        }
    }
}
