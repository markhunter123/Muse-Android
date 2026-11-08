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
}

kotlin { jvmToolchain(21) }

dependencies {
    // BinderContainer 等在 api 工件里，server 代码直接引用
    api(libs.shizuku.api)
    compileOnly(libs.shizuku.provider)
    implementation(libs.gson)
    api(libs.rikkax.parcelablelist)
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
    implementation(libs.refine.runtime)
    // AdbKey 解析与配对密码学（Task 9/10 用，一次加齐）
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.ndk.boringssl)
}
