package com.difft.android.setting.proxy

/** 阶段 2：经已激活的隧道对一个 self-cert origin 发一次轻量请求，判端到端可达。 */
interface ProxyE2eProbe {
    /** true = 经代理成功触达 TempTalk（任意 HTTP 状态码）；false = 不可达。绝不抛。 */
    suspend fun probe(): Boolean
}
