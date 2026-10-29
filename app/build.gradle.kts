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
        versionCode = 5
        versionName = "0.5.0"
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
