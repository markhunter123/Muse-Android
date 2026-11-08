plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.refine)
}

android {
    namespace = "moe.shizuku.server.muse"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // MUSE: adb 配对 JNI（Task 9）；本机 NDK r29（D:\Android\Sdk\ndk\29.0.14206865）
    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        // boringssl prefab（Task 9 的配对 JNI 链接用）
        prefab = true
    }

    // MUSE: JVM 单测里 AdbKey init 会调 android.util.Log，用默认返回值兜底
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin { jvmToolchain(21) }


dependencies {
    // BinderContainer 等在 api 工件里，server 代码直接引用
    api(libs.shizuku.api)
    compileOnly(libs.shizuku.provider)
    implementation(libs.gson)
    implementation(libs.androidx.annotation)
    // adb 客户端 vendored 文件用：KTX 的 edit{} 扩展
    implementation(libs.androidx.core.ktx)
    api(libs.rikkax.parcelablelist)
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
    implementation(libs.refine.runtime)
    // AdbKey 解析与配对密码学（Task 9/10 用，一次加齐）
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.ndk.boringssl)

    testImplementation(libs.junit)
}
