plugins {
    id("com.android.application")
}

android {
    namespace = "vn.quettailieu.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.quettailieu.scanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Khoá ký riêng (không đưa lên GitHub). Có file keystore thì bản release được ký,
    // không có thì tạo APK chưa ký (ký sau bằng apksigner).
    val ks = file("../keystore/quettailieu.jks")
    if (ks.exists()) {
        signingConfigs {
            create("app") {
                storeFile = ks
                storePassword = "quettailieu"
                keyAlias = "quettailieu"
                keyPassword = "quettailieu"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (ks.exists()) signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        encoding = "UTF-8"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
