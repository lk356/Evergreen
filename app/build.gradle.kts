plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.evergreen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.evergreen"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // Lets Room's migration tests read the exported schema JSON (see `ksp {}`
    // below). Harmless until Phase 2 adds the first migration.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

// Test classpath only — none of this reaches the shipped app.
//
// Room's MigrationTestHelper parses the exported schema JSON using
// kotlinx-serialization. room-migration 2.8.5 was compiled against
// serialization 1.8.x, where `GeneratedSerializer.typeParametersSerializers()`
// has a default implementation and so is not generated into each serializer.
// Something on the instrumented classpath pins -core to 1.7.3 "strictly", where
// that method is still abstract, and runMigrationsAndValidate then dies with
// AbstractMethodError on FieldBundle$$serializer.
//
// Forcing -core up to match -json realigns the pair. `force` is used rather
// than a plain dependency because a "strictly" constraint outranks an ordinary
// version request. Matched case-insensitively on "AndroidTest" because the
// configuration that actually feeds the test APK is named
// `debugAndroidTestRuntimeClasspath` — it does not start with "androidTest".
// Production configurations are untouched.
configurations.matching { it.name.contains("AndroidTest", ignoreCase = true) }.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.kotlinxSerialization.get()}")
        force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${libs.versions.kotlinxSerialization.get()}")
    }
}


ksp {
    // Writes a JSON description of the database to app/schemas/ on every build.
    // These files are committed: they are what lets a future migration be
    // generated and tested against the real previous schema instead of being
    // hand-written from memory.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
