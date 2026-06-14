plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.aichat.sandbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aichat.sandbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
        // AndroidX Ink 1.0.0 publishes Kotlin 2.0 metadata (mv=2.0.0); the
        // project's Kotlin 1.9.22 compiler reads it under this opt-in. The ink
        // APIs the InkInterop seam uses are stable 1.0.0. Drop this once the
        // I0.5 toolchain bump moves Kotlin past 2.0.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Stylus input: one-frame look-ahead for ink (sub-phase 1.4).
    implementation("androidx.input:input-motionprediction:1.0.0-beta05")

    // AndroidX Ink (phase I0 — the InkInterop seam). The ink engine is not yet
    // wired into any on-device code path, so it is `compileOnly` here: the seam
    // compiles, but no ink classes or native `.so` are packaged into the APK.
    // The `-jvm` artifacts (which bundle `linux-x86_64/libink.so`) are added to
    // the unit-test classpath so the round-trip tests run headless, without an
    // emulator. Phase I1 flips these to `implementation` of the android
    // variants when authoring moves on-device.
    val inkVersion = "1.0.0"
    compileOnly("androidx.ink:ink-strokes-jvm:$inkVersion")
    compileOnly("androidx.ink:ink-brush-jvm:$inkVersion")
    compileOnly("androidx.ink:ink-geometry-jvm:$inkVersion")
    testImplementation("androidx.ink:ink-strokes-jvm:$inkVersion")
    testImplementation("androidx.ink:ink-brush-jvm:$inkVersion")
    testImplementation("androidx.ink:ink-geometry-jvm:$inkVersion")
    testImplementation("androidx.ink:ink-nativeloader-jvm:$inkVersion")

    // On-device handwriting OCR for the notes AI pipeline (sub-phase 2.1+).
    // The model itself is fetched at runtime via RemoteModelManager, so no
    // ML Kit DEPENDENCIES meta-data tag is required for digital-ink.
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    // Lets HandwritingOcr `await()` ML Kit's Play Services Tasks from
    // coroutines (sub-phase 2.3).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Image loading (Coil for Compose)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Markdown rendering
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    // Syntax highlighting disabled - prism4j annotation processor not compatible with KSP-only builds
    // implementation("io.noties.markwon:syntax-highlight:4.6.2")
    // implementation("io.noties:prism4j:2.0.0")
    // annotationProcessor("io.noties:prism4j-bundler:2.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
