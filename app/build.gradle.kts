plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.aetheria.vance"
    compileSdk = 36  // Android 16 (API 36) — correct for target device

    defaultConfig {
        applicationId = "com.aetheria.vance"
        minSdk = 26
        targetSdk = 36  // Android 16 (API 36) — correct for target device
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NPU: Specify ABIs for MediaTek
        ndk {
            abiFilters.add("arm64-v8a")  // MT6878 is arm64
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-O3 -ffunction-sections -fdata-sections"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true  // FIXED: Enable for release
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"  // Match Kotlin 1.9.0 (1.5.8 requires 1.9.22)
    }

    // NPU: External native build
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // NPU: Keep native libraries uncompressed for faster loading
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
    }

    androidResources {
        noCompress += listOf("tflite", "litertlm", "bin")
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Compose
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Material Components
    implementation("com.google.android.material:material:1.11.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // MediaPipe LiteRT LLM — with NPU delegate support
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // NPU: TensorFlow Lite with Neuron delegate
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.15.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")  // NNAPI delegate factory for WakeWordService
    // Neuron delegate — check MediaTek/TensorFlow documentation for exact artifact
    // implementation("org.tensorflow:tensorflow-lite-neuron:2.15.0")  // May vary by SDK version

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Networking (Groq fallback)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // REMOVED: kotlinx-serialization-json (unused — code uses org.json)

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Security — stable version
    implementation("androidx.security:security-crypto:1.0.0")  // FIXED: Was alpha

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager — for BootReceiver deferred service start
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
