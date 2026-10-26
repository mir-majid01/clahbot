plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clahbot.ocr"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Bundled on-device Latin OCR model; no network or Play Services download.
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Tesseract row-crop reader; model is bundled in assets/tessdata.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // PP-OCRv4 recognition on ONNX Runtime; model and dictionary are bundled.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
}
