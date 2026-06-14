plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 moves the Compose compiler out of a standalone artifact and into
    // a first-party Gradle plugin versioned in lockstep with Kotlin. This replaces
    // the old `composeOptions { kotlinCompilerExtensionVersion = ... }` block.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}
