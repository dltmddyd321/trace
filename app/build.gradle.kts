import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.trace.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.trace.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 키는 local.properties(gitignore 대상)에서 읽는다. 없으면 빈 문자열로 빌드되고
        // 앱은 경로 B를 비활성화한 채 동작한다 — 키가 없다고 빌드가 깨지면 안 된다.
        val apiKey = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }.getProperty("geminiApiKey", "")
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MediaPipe 네이티브 라이브러리가 ABI 마다 10MB 안팎이라 전부 담으면 APK 가 78MB 가 된다.
        // 배포 대상은 실기기뿐이고 arm64 가 아닌 안드로이드 폰은 사실상 없다.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 서명 정보도 local.properties 에서 읽는다. 키스토어와 비밀번호는 저장소에 들어가지 않으므로
    // 설정이 없으면 릴리스 빌드는 서명 없이 나온다 — 빌드 자체가 깨지지는 않는다.
    val signing = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val storeFileName = signing.getProperty("releaseStoreFile", "")

    signingConfigs {
        if (storeFileName.isNotBlank() && rootProject.file(storeFileName).exists()) {
            create("release") {
                storeFile = rootProject.file(storeFileName)
                storePassword = signing.getProperty("releaseStorePassword")
                keyAlias = signing.getProperty("releaseKeyAlias")
                keyPassword = signing.getProperty("releaseKeyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}