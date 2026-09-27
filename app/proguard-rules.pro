# OkHttp / okio：反射读取内部类，缺 keep 会运行期崩
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin stdlib 的元数据（Compose 依赖）
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# 自定义 Application 与 Activity（manifest 按名引用，AGP 通常已处理，双保险）
-keep class tw.vtsh.reader.VtshApp { *; }
-keep class tw.vtsh.reader.MainActivity { *; }
-keep class tw.vtsh.reader.TodayWidget { *; }
-keep class tw.vtsh.reader.FeeWidget { *; }

# 系统 TrustManager 互操作
-keep class javax.net.ssl.** { *; }
-dontwarn javax.annotation.**

# pdfbox-android：JPEG2000/JPX 解码器 (可选依赖 gemalto) 我们不解析图像，剥掉即可
-dontwarn com.gemalto.**
-dontwarn org.bouncycastle.pqc.**
-dontwarn org.bouncycastle.jcajce.provider.**

