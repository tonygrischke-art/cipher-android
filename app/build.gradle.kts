plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

detekt {
    toolVersion = "1.23.6"
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = true
}

android {
    namespace = "com.aetheria.vance"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aetheria.vance"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
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
            isMinifyEnabled = true
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
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
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
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // Compose
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Material Components
    implementation("com.google.android.material:material:1.14.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    // TensorFlow Lite — C API for NPU inference via standard NNAPI delegate
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Networking (local llama.cpp bridge only — no cloud)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Memory leak detection (debug only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // Network inspector (debug only)
    debugImplementation("com.github.chuckerteam.chucker:library:4.0.0")
    releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.0.0")

    // Detekt
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
}