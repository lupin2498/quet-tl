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
        versionCode = 6
        versionName = "1.5"
        // Thư viện nhận dạng chữ (ML Kit) có mã máy: chỉ đóng gói cho máy thật (arm) và máy ảo kiểm thử (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Lớp nối với ML Kit để riêng: build_apk.sh (không Gradle, không thư viện ngoài) chỉ biên dịch src/main/java
    sourceSets {
        getByName("main") {
            java.srcDir("src/mlkit/java")
        }
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

    // nén mã máy trong APK (file cài nhỏ hơn nhiều; hệ thống giải nén khi cài)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Nhận dạng chữ ngay trên máy cho tính năng chuyển sang Word: mô hình chữ La-tinh (đọc được tiếng Việt) đóng kèm
    // trong ứng dụng, không tải thêm, không cần mạng
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
