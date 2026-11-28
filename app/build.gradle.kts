plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nextmovie"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nextmovie"
        // Android 8.0 起使用 java.util.Base64 / 现代 Media3 能力；低版本设备留待 Lite 分支
        minSdk = 26
        // 保持 33：Android 14 起禁止加载可写 dex，JAR 插件依赖这一点
        // 保持 28：Android 10+ 对 targetSdk>=29 的应用禁止 dlopen 应用私有目录里的 .so。
        // Guard 插件会在自己的 clinit 里把 assets/ftyguard_v*.so 拷到 cacheDir 再 System.load，
        // targetSdk 33 时这条路径会被 linker 直接拒掉（"not accessible for the namespace"）。
        // 我们是侧载的 TV 播放器，不依赖 Play 的 targetSdk 要求，28 是这类宿主应用的标准做法。
        targetSdk = 28
        versionCode = 45
        versionName = "0.6.29-dev"
    }

    // tv   -> armeabi-v7a：交付给电视盒子（多数盒子是 32 位 ARM，v7a 兼容面最广）
    // dev  -> x86_64：Mac 上的 AVD，只用来调 UI
    // tv64 -> arm64-v8a：64 位电视盒子 / 模拟器；出包 ./gradlew :app:assembleTv64Debug
    flavorDimensions += "abi"
    productFlavors {
        create("tv") {
            dimension = "abi"
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        // 64 位盒子用这一支；与 tv 同 applicationId，只能装其一
        create("tv64") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("dev") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 单元测试里 Joy/Media3 会触碰 android.os 桩方法，统一返回默认值即可
    testOptions { unitTests.isReturnDefaultValues = true }

    buildFeatures {
        compose = true
    }

    lint {
        // This sideloaded TV host intentionally targets 28 for native plugin compatibility.
        // The Google Play submission target is not applicable to its release APKs.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(project(":catvodlib"))
    implementation("com.google.zxing:core:3.5.3")
    // `.js` 站点宿主：TVBox 生态里约 10% 的站点是 JS 脚本，需要在本地跑 JS
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")
    // drpy / xBPQ 的 pdfh/pdfa 选择器桥：用 Jsoup 的 CSS + :eq()/:contains() 承接
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // CatVod 插件普遍直接依赖宿主内置的 Gson（TypeToken/JsonObject），
    // 缺少它会在 DexClassLoader 里抛 ClassNotFoundException。
    implementation(files("libs/gson-2.10.1.jar"))
    // 可选：音频 FFmpeg 软解。将 media3-decoder-ffmpeg-*.aar 放入 app/libs/ 即可被反射挂载
    // 官方不发布预编译包，需自编译或使用兼容 AAR（注意 ABI: armeabi-v7a / arm64-v8a）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("media3-decoder-ffmpeg*.aar", "lib-decoder-ffmpeg*.aar"))))

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
