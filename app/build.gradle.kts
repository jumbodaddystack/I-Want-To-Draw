plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.aichat.sandbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.doodlepad.kids"
        minSdk = 36
        targetSdk = 36
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
        // The `-Xskip-metadata-version-check` opt-in is no longer needed: the
        // I0.5 toolchain bump moved the compiler to Kotlin 2.0.21, which reads
        // AndroidX Ink 1.0.0's Kotlin 2.0 metadata (mv=2.0.0) natively.
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The Compose compiler version is now supplied by the
    // `org.jetbrains.kotlin.plugin.compose` Gradle plugin (Kotlin 2.0+), so the
    // old `composeOptions { kotlinCompilerExtensionVersion }` is gone.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
}

configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

// Keep the on-device (android) AndroidX Ink variants off the JVM unit-test
// classpaths. Main code links the android variants (they ship in the APK and
// load the arm64 `libink.so`); the headless `:app:testDebugUnitTest` host links
// the `-jvm` variants (`testImplementation` above, with `linux-x86_64`
// `libink.so`). Without this exclusion both would be present and the JVM would
// pick the android `Stroke`/`Brush`, whose native lib can't load off-device.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    exclude(group = "androidx.ink", module = "ink-strokes")
    exclude(group = "androidx.ink", module = "ink-brush")
    exclude(group = "androidx.ink", module = "ink-geometry")
    exclude(group = "androidx.ink", module = "ink-rendering")
    exclude(group = "androidx.ink", module = "ink-authoring")
    exclude(group = "androidx.ink", module = "ink-nativeloader")
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

    // AndroidX Ink. Phase I0 added the `InkInterop` seam as `compileOnly`;
    // phase I1 moves authoring on-device, so the ink engine now ships in the
    // APK. `ink-authoring` is the live front-buffer drawing view and pulls in
    // `ink-strokes` / `ink-brush` / `ink-geometry` / `ink-rendering` /
    // `ink-nativeloader` (incl. the arm64 `libink.so`) transitively; the
    // explicit lines below just make our direct uses legible.
    //
    // Unit tests still run headless on the JVM, so the `-jvm` artifacts (which
    // bundle `linux-x86_64/libink.so`) are on the test classpath, and the
    // android variants are excluded from the unit-test classpaths below so the
    // two never collide (the android `Stroke`/`Brush` classes load a native lib
    // that doesn't exist off-device).
    val inkVersion = "1.0.0"
    implementation("androidx.ink:ink-strokes:$inkVersion")
    implementation("androidx.ink:ink-brush:$inkVersion")
    implementation("androidx.ink:ink-geometry:$inkVersion")
    implementation("androidx.ink:ink-authoring:$inkVersion")
    implementation("androidx.ink:ink-rendering:$inkVersion")
    implementation("androidx.ink:ink-nativeloader:$inkVersion")
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
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

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
