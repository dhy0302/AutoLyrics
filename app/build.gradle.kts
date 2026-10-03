plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.eu.dinghongyu.autolyrics"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.eu.dinghongyu.autolyrics"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "1.12.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // 发布签名：密钥库与密码均不纳入版本控制，需在本地 gradle.properties 中提供：
    //   密钥库文件（放在项目根目录），文件名可用 RELEASE_STORE_FILE 覆盖：
    //     · autolyrics-release.p12  ← 当前使用（PKCS#12）
    //     · autolyrics-release.jks  （旧格式，仍兼容）
    //   RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
    //
    // 凭据齐备才配置 release 签名；否则跳过（debug 构建不受影响）。
    // 这样既避免把密码写死进源码，也不会因缺凭据导致 release 构建失败。
    val releaseStoreFile = rootProject.file(
        (project.findProperty("RELEASE_STORE_FILE") as String?) ?: "autolyrics-release.p12"
    )
    val releaseStorePwd = project.findProperty("RELEASE_STORE_PASSWORD") as String?
    val releaseKeyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
    val releaseKeyPwd = project.findProperty("RELEASE_KEY_PASSWORD") as String?

    val hasReleaseSigning = releaseStoreFile.exists() &&
        !releaseStorePwd.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPwd.isNullOrBlank()

    if (!hasReleaseSigning) {
        logger.warn(
            "[AutoLyrics] release 签名凭据不完整（需要根目录密钥库文件（默认 " +
                "autolyrics-release.p12）及 RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / " +
                "RELEASE_KEY_PASSWORD），release 包将不签名，仅适用于本地调试。"
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePwd
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 仅在凭据齐备时启用签名，避免无凭据时构建失败
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

    dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 专辑封面：URI 兜底加载（部分播放器只给 ART_URI，不给 Bitmap）
    implementation("io.coil-kt:coil-compose:2.6.0")

    // v1.9.0 接入 SaltUI（椒盐音乐的组件库）。
    //
    // 踩了两个坑，都记在这里免得后人重踩：
    //
    // 1) **坐标**：README 写的是 `io.github.moriafly:salt-ui`，但那只是 KMP
    //    元数据入口，直接写会 404。真实可用的 Android 产物是另一个 artifact
    //    `salt-ui-android`（真实产物名是从 .module 的 available-at 反查的）。
    //    两个都要写——SaltColors 等在 common 变体，SaltTheme_androidKt 在 android 变体。
    //
    // 2) **版本**：千万别用 3.0.0-beta01。它要求 Compose 1.12 + AGP 9.1 + compileSdk 37，
    //    还会拖来 haze / AndroidHiddenApiBypass(隐藏 API，上架 Google Play 会被拒) /
    //    salt-core 一大串新依赖。2.0.10 才是能落地的版本：aar-metadata 写的是
    //    minCompileSdk=1 / minAGP=1.0.0（零门槛），且它依赖的 Compose 是 1.7.0-alpha03，
    //    和本项目的 1.7.5 同一时代，不需要任何 resolutionStrategy.force。
    implementation("io.github.moriafly:salt-ui:2.0.10")
    implementation("io.github.moriafly:salt-ui-android:2.0.10")
    // 从专辑图提取主色，用于歌词高亮与背景基调
    implementation("androidx.palette:palette-ktx:1.0.0")

}
