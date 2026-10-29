plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cc.bestly.dsh"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.bestly.dsh"
        // ★ 必须 <= 27：Android 10 (API 29) 起对 targetSdk>=28 的 app 施加 W^X 限制，
        // 禁止从 app 私有数据目录 exec 二进制（ProcessBuilder 报 error=13 Permission denied）。
        // targetSdk<=27 的 app 跑在兼容域 u:r:untrusted_app_27 里，允许 exec。
        // 旧壳 com.deepseek.harness 能跑，就是因为它 targetSdk 老。
        minSdk = 27
        targetSdk = 27
        versionCode = 2
        versionName = "0.2.0"
    }

    // ★ 显式固定 debug 签名。
    // 不显式配置时，AGP 只在 keystore 缺失时自动生成到 ~/.android/debug.keystore；
    // 实测 CI 上即使把 keystore 放好，产出的 APK 证书仍然每次不同 —— 那说明
    // AGP 没用它。显式指向同一路径才能真正固定，否则每次构建签名不同，
    // 覆盖升级报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能卸载重装（会清 payload）。
    signingConfigs {
        create("fixedDebug") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
        }
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
        // AGP 8.0+ 默认不生成 BuildConfig 类，不显式打开就会在代码里报
        // Unresolved reference 'BuildConfig'
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
