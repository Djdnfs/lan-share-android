plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lanshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lanshare"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.1"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
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
}

// 无任何第三方依赖：纯 Android SDK + Kotlin 标准库，避免依赖下载失败
dependencies {
}
