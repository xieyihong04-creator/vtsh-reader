package tw.vtsh.reader

import android.content.Context
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** assets 里的官网中间证书（P0 从 AIA URI 抓取），供 NetClient 补链用 */
object Certs {
    @Volatile
    var intermediate: X509Certificate? = null
        private set

    fun init(ctx: Context) {
        try {
            ctx.assets.open("trust_intermediate.pem").use { input ->
                intermediate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(input) as X509Certificate
            }
        } catch (_: Exception) {
            // 缺资产则退回系统默认校验（浏览器/WebView 仍可用，因 AIA 补链）
        }
    }
}
