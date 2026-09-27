import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名凭据放在仓库外的 keystore.properties（已 gitignore），不入库。
// 没有该文件时 release 退回未签名，源码可公开构建。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "tw.vtsh.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.vtsh.reader"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.10"
        // 小工具/解析器都按名字反射不到，保留自家类避免 R8 误删
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro")
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("unofficial") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("unofficial")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // 产物名带版本，避免每次手动改下载页文件名
    applicationVariants.all {
        outputs.map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { it.outputFileName = "vtsh-reader-${versionName}.apk" }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // pdfbox-android 传递依赖 bcprov 里的后量子密码数据表（8MB），
        // 我们只抽文本（PDF 无加密），永远走不到那些路径；整包剥掉，APK 减 ~6MB
        resources.excludes += "org/bouncycastle/pqc/**"
        resources.excludes += "org/bouncycastle/crypto/pqc/**"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 行事曆：运行时从学校 PDF 抽文本（PdfRenderer 只能出图，不能出文本）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
