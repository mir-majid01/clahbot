import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clahbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clahbot"
        minSdk = 26
        targetSdk = 34
        versionCode = 29
        versionName = "2.10.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The current build is for the arm64 test phone; the OCR stack's
        // native libraries otherwise make a universal APK ~151 MB.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        // Stable key: updates must keep the same signature or Android rejects
        // them and Shizuku permission/app data are lost.
        create("release") {
            storeFile = rootProject.file("keystore/autoclash-release.jks")
            storePassword = "REDACTED_SIGNING_PASSWORD"
            keyAlias = "autoclash"
            keyPassword = "REDACTED_SIGNING_PASSWORD"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Strong skipping reduces recomposition during live stats updates and
        // scrolling without changing any UI behavior.
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true"
        )
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":ocr"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")

    // Shizuku runs getevent in the user-service process with shell UID.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

// Optional local migration checks on copies of the user's live files.
tasks.withType<Test>().configureEach {
    listOf("CLAHS_TEST_MACROS", "CLAHS_TEST_ATTACKS").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}
