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
        // ★ targetSdk 必须正好是 28 —— 这是 /system/etc/selinux/plat_seapp_contexts 决定的：
        //
        //   minTargetSdkVersion=34 → domain=untrusted_app      levelFrom=all
        //   minTargetSdkVersion=29 → domain=untrusted_app_29   levelFrom=all
        //   minTargetSdkVersion=28 → domain=untrusted_app_27   levelFrom=all    ← 只有这条两头都满足
        //   minTargetSdkVersion=26 → domain=untrusted_app_27   levelFrom=user
        //
        // 取 28 才同时拿到两件事：
        //   ① 域仍是 untrusted_app_27（兼容域，允许从 app 数据目录 exec 二进制）
        //   ② levelFrom=all 给出完整 MCS category（旧壳靠这个绑 3080 端口）
        //
        // 取 27 会掉进 levelFrom=user 那行：能 exec，但 category 不全，
        // 连 ServerSocket 都 bind 不了（Node 报 listen EACCES，极易误判成端口冲突）。
        // 取 29 以上则进 untrusted_app_29/32/34，失去 exec 权限。
        //
        // 旧壳 com.deepseek.harness 的实际值就是 minSdk=24 / targetSdk=28，实测可跑。
        minSdk = 24
        targetSdk = 28
        versionCode = 12
        versionName = "0.8.4"
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

        // ★ 正式签名（P5）。密钥在 Vaultwarden「DSH 安卓签名密钥 (cc.bestly.dsh)」，
        // CI 通过 RELEASE_KEYSTORE_B64 / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS 注入。
        // SHA256 指纹：62:E6:2F:47:FE:8F:DA:BB:51:A6:EB:ED:E1:D3:B3:56:A1:5C:76:14:B5:3D:56:7E:EA:0D:BC:CF:42:CE:60:09
        // 这是 App 的身份根：一旦换签名，已安装的 App 无法覆盖升级，只能卸载重装。
        create("release") {
            val ksPath =
                System.getenv("RELEASE_KEYSTORE_PATH")
                    ?: File(System.getProperty("user.home"), "dsh-release.keystore").absolutePath
            val ksFile = File(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "dsh-release"
                // PKCS12 只有单一口令，keyPassword 必须与 storePassword 一致
                keyPassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
            // 有正式密钥就用正式签名，没有就回退 debug（保证任何环境都能出包）
            val releaseKs =
                System.getenv("RELEASE_KEYSTORE_PATH")
                    ?: File(System.getProperty("user.home"), "dsh-release.keystore").absolutePath
            signingConfig =
                if (File(releaseKs).exists()) signingConfigs.getByName("release")
                else signingConfigs.getByName("fixedDebug")
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

    // ★ 关掉 release 的 lintVital 阻塞。
    // release 构建会跑 lintVitalRelease，其中 ExpiredTargetSdkVersion 会报
    // "Google Play requires that apps target API level 33 or higher" 并**致命失败**。
    // 但本 App 的 targetSdk 必须锁 28 —— 这是 plat_seapp_contexts 决定的（见上方注释），
    // 改成 33 就进 untrusted_app_33，失去从 app 数据目录 exec 二进制的能力，引擎直接起不来。
    // 本 App 不进 Google Play（自用 + 直接分发 APK），所以这条检查对我们是纯噪声。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
