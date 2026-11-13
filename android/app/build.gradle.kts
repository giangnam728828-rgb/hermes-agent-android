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
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    /**
     * 固定签名，保证后续版本能直接覆盖安装。keystore 与项目一起维护，
     * 凭据不再写死在源码里：CI 由 Secrets 注入环境变量，本地开发可放在
     * android/keystore.properties（已被 .gitignore 排除）。
     */
    signingConfigs {
        create("release") {
            val store = rootProject.file("keystore/hermes-release.jks")
            if (store.isFile) {
                storeFile = store

                val localProps = java.util.Properties().apply {
                    val f = rootProject.file("keystore.properties")
                    if (f.isFile) f.inputStream().use { load(it) }
                }
                fun secret(envName: String, propName: String): String {
                    val fromEnv = System.getenv(envName)?.takeIf { it.isNotBlank() }
                    if (fromEnv != null) return fromEnv
                    val fromFile = localProps.getProperty(propName)?.takeIf { it.isNotBlank() }
                    if (fromFile != null) return fromFile
                    throw org.gradle.api.GradleException(
                        "缺少签名凭据：请设置环境变量 $envName，" +
                            "或在 android/keystore.properties 中配置 $propName"
                    )
                }

                storePassword = secret("HERMES_STORE_PASSWORD", "storePassword")
                keyAlias = secret("HERMES_KEY_ALIAS", "keyAlias")
                keyPassword = secret("HERMES_KEY_PASSWORD", "keyPassword")
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
