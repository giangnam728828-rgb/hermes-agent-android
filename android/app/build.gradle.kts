plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.hermes.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.hermes.agent"
        minSdk = 26
        // 与 Kimi Code 那套一致：targetSdk 28 才允许从 App 私有目录执行二进制，
        // proot 需要这个能力。
        targetSdk = 28
        // 覆盖安装三要素：applicationId 不变、签名证书不变、versionCode 只增不减。
        // 2.0 相对 1.0 是升级，旧版数据（面板 localStorage / 工作目录 / 设置）全部保留。
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    /**
     * 固定签名，保证后续版本能直接覆盖安装。keystore 与项目一起维护。
     * 凭据只由环境变量 / GitHub Secrets 注入，源码不含任何密码；
     * workflow 顶层 env 保证 CI 中这三个变量一定有值。
     * 本地构建请先 export 同名环境变量。
     */
    signingConfigs {
        create("release") {
            val store = rootProject.file("keystore/hermes-release.jks")
            if (store.isFile) {
                storeFile = store
                storePassword = System.getenv("HERMES_STORE_PASSWORD")
                    ?: throw org.gradle.api.GradleException("缺少签名凭据：HERMES_STORE_PASSWORD")
                keyAlias = System.getenv("HERMES_KEY_ALIAS")
                    ?: throw org.gradle.api.GradleException("缺少签名凭据：HERMES_KEY_ALIAS")
                keyPassword = System.getenv("HERMES_KEY_PASSWORD")
                    ?: throw org.gradle.api.GradleException("缺少签名凭据：HERMES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val store = rootProject.file("keystore/hermes-release.jks")
            signingConfig = if (store.isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    /**
     * targetSdk 28 是 proot 的硬约束（Android 10+ 只允许 targetSdk < 29 的 App
     * 从私有目录执行二进制）。Google Play 的这条规则对自签名分发的 App 不适用，
     * 因此显式关掉该检查，而不是把 SDK 调高导致 proot 失效。
     */
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // 纯 framework 实现，不引 androidx：包体小、启动快，也不受 AppCompat 主题约束。
}
