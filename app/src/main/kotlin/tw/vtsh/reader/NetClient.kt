package tw.vtsh.reader

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 官网 TLS 链不完整：服务器只送叶子证书，缺中间 CA
 * （Chunghwa Telecom GCC R46 OV TLS CA 2025，由系统内置的 GlobalSign Root R46 签发）。
 * 浏览器会做 AIA 补链所以看得懂，OkHttp 不会 —— 握手直接失败。
 *
 * 做法：从 assets 读入该中间证书，验证时若服务器只给一张证书就补上再交回
 * 系统默认 TrustManager 完整校验。不跳过校验、不固定证书、不信任任意主机。
 */
object NetClient {
    const val BASE = "https://www.vtsh.tc.edu.tw"
    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val systemTm: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
            .trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    private val patchedTm: X509TrustManager by lazy {
        val inter = Certs.intermediate
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                systemTm.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val full = if (chain.size == 1 && inter != null) chain + inter else chain
                @Suppress("UNCHECKED_CAST")
                systemTm.checkServerTrusted(full as Array<X509Certificate>, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
        }
    }

    val http: OkHttpClient by lazy {
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(patchedTm), null)
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, patchedTm)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun request(url: String): Request =
        Request.Builder().url(url).header("User-Agent", UA).build()

    /** 缓存文件名用的稳定摘要：String.hashCode 只有 32 位，URL 一多必撞 */
    fun cacheKey(url: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
