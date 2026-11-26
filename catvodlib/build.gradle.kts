plugins {
    id("com.android.library")
}

android {
    namespace = "com.github.catvod.crawler"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Gson 由 app 以 jar 形式提供（插件通过 DexClassLoader 需要宿主已加载它），这里只编译期引用
    compileOnly(files("../app/libs/gson-2.10.1.jar"))
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.okhttp.dnsoverhttps)
    api(libs.guava)
    api(libs.logger)
    api(libs.androidx.annotation)
    api(libs.androidx.core.ktx)
    api(libs.androidx.collection)
    api(libs.androidx.preference)
}
