import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same credential policy as :app: GitHub Actions env first, then gitignored
// local.properties; keystore never committed; release fails loudly when
// credentials are missing.
fun acCredential(key: String): String? =
    System.getenv(key)
        ?: Properties().apply {
            val lp = rootProject.file("local.properties")
            if (lp.exists()) lp.inputStream().use { load(it) }
        }.getProperty(key)

val acStoreFile: String? = acCredential("AC_STORE_FILE")
val acStorePassword: String? = acCredential("AC_STORE_PASSWORD")
val acKeyAlias: String? = acCredential("AC_KEY_ALIAS")
val acKeyPassword: String? = acCredential("AC_KEY_PASSWORD")
val acSigningReady: Boolean =
    acStoreFile != null && acStorePassword != null && acKeyAlias != null && acKeyPassword != null

gradle.taskGraph.whenReady {
    if (!acSigningReady && allTasks.any { it.name.contains("Release") }) {
        throw GradleException(
            "Release signing credentials missing: set AC_STORE_FILE, AC_STORE_PASSWORD, " +
                "AC_KEY_ALIAS, AC_KEY_PASSWORD via GitHub Actions secrets or gitignored " +
                "local.properties. Refusing to build an unsigned or wrong-key release."
        )
    }
}

android {
    namespace = "com.clahbot.ocrlab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clahbot.ocrlab"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The lab runs on the arm64 test phone; keeping one ABI avoids a 143 MB
        // multi-ABI download for ONNX Runtime and Tesseract.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (acSigningReady) {
            create("release") {
                storeFile = file(acStoreFile!!)
                storePassword = acStorePassword
                keyAlias = acKeyAlias
                keyPassword = acKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (acSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (acSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":ocr"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
