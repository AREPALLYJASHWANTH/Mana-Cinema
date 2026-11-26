import java.util.Properties

// NOTE: the google-services plugin is intentionally NOT applied.
// They generate google_app_id / project_id / google_api_key / firebase_database_url
// string resources that end up readable inside resources.arsc (MT Manager string pool).
// All Firebase config is supplied in code by FirebaseInitializer instead.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Secrets are read from secrets.properties (preferred, git-ignored) or local.properties,
// falling back to an env var. Kept separate from local.properties so AndroidIDE's
// auto-managed sdk.dir handling never clobbers the key.
val secretProps = Properties().apply {
    listOf("secrets.properties", "local.properties").forEach { name ->
        val f = rootProject.file(name)
        if (f.exists()) f.inputStream().use { load(it) }
    }
}
val firebaseApiKey: String = secretProps.getProperty("FIREBASE_API_KEY")
    ?: System.getenv("FIREBASE_API_KEY")
    ?: ""

if (firebaseApiKey.isBlank()) {
    throw GradleException("FIREBASE_API_KEY is required via secrets.properties, local.properties, or the FIREBASE_API_KEY environment variable.")
}

// Release signing: read keystore location and passwords from secrets.properties /
// local.properties / environment (all git-ignored). Falls back to the debug
// signing config ONLY for the debug build variant, never for release.
val releaseStoreFile: String = secretProps.getProperty("RELEASE_STORE_FILE")
    ?: System.getenv("RELEASE_STORE_FILE") ?: ""
val releaseStorePassword: String = secretProps.getProperty("RELEASE_STORE_PASSWORD")
    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
val releaseKeyAlias: String = secretProps.getProperty("RELEASE_KEY_ALIAS")
    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
val releaseKeyPassword: String = secretProps.getProperty("RELEASE_KEY_PASSWORD")
    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
val hasReleaseKeystore: Boolean = releaseStoreFile.isNotBlank() && releaseStorePassword.isNotBlank()
    && releaseKeyAlias.isNotBlank() && releaseKeyPassword.isNotBlank() && rootProject.file(releaseStoreFile).exists()

tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && name.startsWith("assemble")) {
        doFirst {
            if (!hasReleaseKeystore) throw GradleException(
                "Release build requires RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD with an existing keystore."
            )
        }
    }
}

android {
    namespace = "com.manacinema.app"
    compileSdk = 34

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.manacinema.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use the real release keystore when configured; fall back to a build error
            // rather than silently signing production builds with the public debug key.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xskip-metadata-version-check",
            "-Xskip-prerelease-check"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Ship the exported Room schema JSONs into the androidTest APK so
    // MigrationTestHelper can validate every version at test time.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Room exports one schema JSON per DB version here. These files are committed
// to source control so future migrations can be diffed and validated.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    
    // Direct Compose library coordinates to bypass BOM mutation issues
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.ui:ui-graphics:1.5.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    implementation("androidx.compose.foundation:foundation:1.5.4")
    implementation("androidx.compose.material:material:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Media streaming (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // Local DB Caching
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Direct Firebase library coordinates to bypass BOM mutation issues
    implementation("com.google.firebase:firebase-auth:22.3.1")
    implementation("com.google.firebase:firebase-database:20.3.0")
    implementation("com.google.firebase:firebase-firestore:24.10.3")
    implementation("com.google.firebase:firebase-messaging:23.4.1")
    implementation("com.google.firebase:firebase-analytics:21.6.1")
    implementation("com.google.firebase:firebase-crashlytics:18.6.4")
    implementation("com.google.firebase:firebase-functions:20.4.0")
    implementation("com.onesignal:OneSignal:5.9.2")

    // Google Cast (Chromecast)
    implementation("androidx.mediarouter:mediarouter:1.6.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")

    // Security (EncryptedSharedPreferences for parental PIN)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines + Play Services Task.await() interop
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Test libs
    testImplementation("junit:junit:4.13.2")

    // Room migration validation (instrumented)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

tasks.register("copyApk") {
    dependsOn("assembleDebug")
    doLast {
        val apkFile = File(layout.buildDirectory.dir("outputs/apk/debug").get().asFile, "app-debug.apk")
        val destFile = File(rootProject.projectDir, "Mana_Cinema.apk")
        if (apkFile.exists()) {
            apkFile.copyTo(destFile, overwrite = true)
            println("Successfully copied APK to ${destFile.absolutePath}")
        } else {
            throw GradleException("Source APK not found at ${apkFile.absolutePath}")
        }
    }
}
