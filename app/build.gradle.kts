import org.gradle.api.tasks.testing.Test
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials resolve from GitHub Actions environment variables
// first, then from gitignored local.properties. The keystore itself is
// NEVER committed. Debug builds fall back to AGP's auto-generated default
// debug keystore when no credentials are present (keeps CI green); release
// builds fail loudly instead of silently producing an unsigned or
// wrong-key APK (see the taskGraph guard below).
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

// Clear, early failure only when a release variant is actually requested.
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
    namespace = "com.clahbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clahbot"
        minSdk = 26
        targetSdk = 34
        versionCode = 40
        versionName = "2.12.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The current build is for the arm64 test phone; the OCR stack's
        // native libraries otherwise make a universal APK ~151 MB.
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (acSigningReady) {
            // Stable key: updates must keep the same signature or Android rejects
            // them and Shizuku permission/app data are lost.
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
            // Without credentials AGP signs debug with the default
            // ~/.android/debug.keystore — fine for CI artifacts.
            if (acSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (acSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Missing credentials are caught by the taskGraph guard above
            // before any release task runs.
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
