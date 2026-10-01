import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ подписи лежит вне git (keystore/), см. docs/BUILD.md
val signProps = Properties().apply {
    val f = rootProject.file("keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.egorchenkov.transcriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.egorchenkov.transcriber"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.4.6"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            if (signProps.isNotEmpty()) {
                storeFile = rootProject.file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // JNI sherpa-onnx читает поля Kotlin-классов по именам — без минификации
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // JNI sherpa-onnx ищет у колбэка метод invoke(IIJ)Ljava/lang/Integer; — он есть
        // только у лямбд-классов, а не у invokedynamic-лямбд Kotlin 2 (иначе NoSuchMethodError)
        freeCompilerArgs += "-Xlambdas=class"
    }
    buildFeatures { compose = true }
    // Сжатые .so: APK ~15 МБ вместо 36 — помещается в лимит Telegram (20 МБ)
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
