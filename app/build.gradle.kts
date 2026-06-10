 1|plugins {
 2| id("com.android.application")
 3| id("org.jetbrains.kotlin.android")
 4| id("com.google.dagger.hilt.android")
 5| id("org.jetbrains.kotlin.kapt")
 6|}
 7|
 8|android {
 9| namespace = "com.aetheria.vance"
 10| compileSdk = 36
 11|
 12| defaultConfig {
 13| applicationId = "com.aetheria.vance"
 14| minSdk = 26
 15| targetSdk = 36
 16| versionCode = 1
 17| versionName = "0.1.0"
 18|
 19| testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 20|
 21| ndk {
 22| abiFilters.add("arm64-v8a")
 23| }
 24|
 25| externalNativeBuild {
 26| cmake {
 27| cppFlags += "-O3 -ffunction-sections -fdata-sections"
 28| arguments += "-DANDROID_STL=c++_shared"
 29| }
 30| }
 31|
 32| vectorDrawables {
 33| useSupportLibrary = true
 34| }
 35| }
 36|
 37| buildTypes {
 38| release {
 39| isMinifyEnabled = true
 40| isShrinkResources = true
 41| proguardFiles(
 42| getDefaultProguardFile("proguard-android-optimize.txt"),
 43| "proguard-rules.pro"
 44| )
 45| }
 46| debug {
 47| isMinifyEnabled = false
 48| }
 49| }
 50|
 51| compileOptions {
 52| sourceCompatibility = JavaVersion.VERSION_17
 53| targetCompatibility = JavaVersion.VERSION_17
 54| }
 55|
 56| kotlinOptions {
 57| jvmTarget = "17"
 58| }
 59|
 60| buildFeatures {
 61| compose = true
 62| buildConfig = true
 63| }
 64|
 65| composeOptions {
 66| kotlinCompilerExtensionVersion = "1.5.1"
 67| }
 68|
 69| externalNativeBuild {
 70| cmake {
 71| path = file("src/main/cpp/CMakeLists.txt")
 72| version = "3.22.1"
 73| }
 74| }
 75|
 76| sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
 77|
 78| packaging {
 79| resources {
 80| excludes += "/META-INF/{AL2.0,LGPL2.1}"
 81| }
 82| jniLibs {
 83| useLegacyPackaging = true
 84| }
 85| }
 86|
 87| lint {
 88| abortOnError = false
 89| }
 90|
 91| androidResources {
 92| noCompress += listOf("tflite", "litertlm", "bin")
 93| }
 94|}
 95|
 96|dependencies {
 97| // Core
 98| implementation("androidx.core:core-ktx:1.12.0")
 99| implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
100| implementation("androidx.lifecycle:lifecycle-service:2.7.0")
101|
102| // Compose
103| implementation("androidx.activity:activity-compose:1.8.0")
104| implementation(platform("androidx.compose:compose-bom:2024.02.00"))
105| implementation("androidx.compose.ui:ui")
106| implementation("androidx.compose.material3:material3")
107| implementation("androidx.compose.material:material-icons-extended:1.6.0")
108| implementation("androidx.compose.ui:ui-tooling-preview")
109| debugImplementation("androidx.compose.ui:ui-tooling")
110|
111| // Material Components
112| implementation("com.google.android.material:material:1.11.0")
113|
114| // Hilt
115| implementation("com.google.dagger:hilt-android:2.50")
116| kapt("com.google.dagger:hilt-compiler:2.50")
117| implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
118|
119| // Room
120| implementation("androidx.room:room-runtime:2.6.1")
121| implementation("androidx.room:room-ktx:2.6.1")
122| kapt("androidx.room:room-compiler:2.6.1")
123|
124| // MediaPipe GenAI — ONLY compile-time dependency for inference
125| implementation("com.google.mediapipe:tasks-genai:0.10.14")
126| implementation("com.google.mediapipe:tasks-core:0.10.14")
127|
128| // Shizuku
129| implementation("dev.rikka.shizuku:api:13.1.5")
130| implementation("dev.rikka.shizuku:provider:13.1.5")
131|
132| // Networking (local llama.cpp bridge only — no cloud)
133| implementation("com.squareup.okhttp3:okhttp:4.12.0")
134|
135| // Location
136| implementation("com.google.android.gms:play-services-location:21.1.0")
137|
138| // Security
139| implementation("androidx.security:security-crypto:1.0.0")
140|
141| // Coroutines
142| implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
143|
144| // WorkManager
145| implementation("androidx.work:work-runtime-ktx:2.9.0")
146|
147| // Testing
148| testImplementation("junit:junit:4.13.2")
149| androidTestImplementation("androidx.test.ext:junit:1.1.5")
150| androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
151|}
