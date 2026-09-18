import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The redirect URI Spotify sends the browser back to after login.
//
// Custom scheme rather than an Android App Link: App Links need a domain you
// own plus a hosted assetlinks.json, which a personal app has no reason to
// have. Spotify's published rules still allow custom schemes, and this exact
// value was confirmed accepted by the developer dashboard on 2026-09-17.
//
// A custom scheme can in principle be registered by another app. That is the
// attack PKCE is designed for: an intercepted code is worthless without the
// verifier, which never leaves this process. See SpotifyAuth.
val SPOTIFY_REDIRECT_SCHEME = "com.example.evergreen"
val SPOTIFY_REDIRECT_HOST = "auth"
val SPOTIFY_REDIRECT_URI = "$SPOTIFY_REDIRECT_SCHEME://$SPOTIFY_REDIRECT_HOST"

// Never printed, never committed. See the buildConfigField note below.
val spotifyClientId: String =
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .map { text -> Properties().apply { load(text.reader()) }.getProperty("spotify.clientId").orEmpty() }
        .getOrElse("")

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

        // The Spotify client ID is not a secret in the cryptographic sense --
        // PKCE exists precisely so that a public client needs no secret -- but
        // it identifies the developer's app and its quota, so it stays out of
        // the repository. Put `spotify.clientId=...` in local.properties, which
        // is gitignored. An absent value builds fine and the app reports that it
        // is not configured, rather than failing at an obscure point later.
        //
        // Read through the provider API so the configuration cache tracks the
        // file and picks up an edit without a manual clean.
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$SPOTIFY_REDIRECT_URI\"")

        // Keeps the manifest's intent-filter and the authorize request reading
        // from one definition, so they cannot drift apart. A mismatch between
        // them is invisible until login silently never returns.
        manifestPlaceholders["spotifyRedirectScheme"] = SPOTIFY_REDIRECT_SCHEME
        manifestPlaceholders["spotifyRedirectHost"] = SPOTIFY_REDIRECT_HOST
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
        buildConfig = true
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


// Refuse to run instrumented tests on the Walkman.
//
// `connectedAndroidTest` uninstalls both APKs when it finishes, taking the
// app's database with them. On the Pixel that is harmless -- its database is
// disposable test data. On the NW-A307 it is the real listening history,
// including the imported streaming history, which Spotify will not export again
// on demand.
//
// This is not hypothetical. It happened on 2026-09-18: the Pixel was unplugged,
// the Walkman was plugged in, and a `connectedDebugAndroidTest` that had been
// safe five minutes earlier wiped the Walkman instead. A rule that lives only
// in a document protects nothing when the thing that changed is which cable is
// in the laptop, so the rule lives here, where the build can enforce it.
//
// Override with -PallowWalkmanTests=true if you genuinely mean it.
// Resolved through the provider API so the configuration cache tracks it.
// `android.sdkDirectory` is not exposed by AGP 9's DSL, and local.properties is
// deliberately never read by this build beyond the client ID.
val adbExecutable = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.systemProperty("user.home").map { home -> "$home/Android/Sdk" })
    .map { sdk -> "$sdk/platform-tools/adb" }
val allowWalkmanTests = providers.gradleProperty("allowWalkmanTests")

tasks.matching { it.name.startsWith("connected") && it.name.contains("AndroidTest") }
    .configureEach {
        val adbPath = adbExecutable
        val allowed = allowWalkmanTests
        doFirst {
            if (allowed.orNull == "true") return@doFirst

            val adb = File(adbPath.get())
            if (!adb.exists()) return@doFirst

            val attached = runCatching {
                ProcessBuilder(adb.absolutePath, "devices", "-l")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().readText()
            }.getOrNull() ?: return@doFirst

            // Declared here rather than at script level: the configuration
            // cache cannot serialize a reference to a script object, so a
            // captured top-level val would make every build report a problem.
            val walkmanMarkers = listOf("NW_A300", "NW-A300", "NW_A307", "NW-A307")
            val walkman = attached.lines().firstOrNull { line ->
                walkmanMarkers.any { marker -> line.contains(marker, ignoreCase = true) }
            }
            if (walkman != null) {
                throw GradleException(
                    "Refusing to run instrumented tests: a Sony Walkman is attached.\n" +
                        "  $walkman\n" +
                        "Running them uninstalls the app and destroys its database, and the " +
                        "Walkman holds the real listening history.\n" +
                        "Unplug it and use the Pixel or an emulator, or pass " +
                        "-PallowWalkmanTests=true if you really mean it.",
                )
            }
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
    // Custom Tabs. RFC 8252 recommends an in-app browser tab for OAuth in
    // native apps, over both an external browser and an embedded webview.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
