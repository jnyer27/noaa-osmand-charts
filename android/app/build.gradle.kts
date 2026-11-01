plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from CI secrets; without them the release build
// falls back to the debug key so local builds still work.
val ks = System.getenv("NOAAROUTE_KEYSTORE")

android {
    namespace = "com.jnyer27.noaaroute"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.jnyer27.noaaroute"
        minSdk = 29
        targetSdk = 35
        // MapLibre ships native libraries. Phones we run on are arm64; the emulator is x86_64.
        // 32-bit ABIs are left out so the APK does not carry a second copy of the renderer.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // APKs built from a branch read the route-pack-beta release (packs from branch runs
        // of route-pack.yml); main and local builds read the route-pack release
        val branch = System.getenv("GITHUB_REF_NAME")
        // NOAA_CATALOG=main forces the release catalogs. A branch APK otherwise reads
        // the beta releases, which do not carry the region chart tiles published on main.
        val releaseCatalogs = System.getenv("NOAA_CATALOG") == "main" || branch == null || branch == "main"
        val packTag = if (releaseCatalogs) "route-pack" else "route-pack-beta"
        buildConfigField("String", "PACK_BASE", "\"https://github.com/jnyer27/noaa-osmand-charts/releases/download/$packTag/\"")
        // Vector chart maps for OsmAnd (vector-maps.yml), the same way
        val vectorTag = if (releaseCatalogs) "vector-maps" else "vector-maps-beta"
        buildConfigField("String", "VECTOR_BASE", "\"https://github.com/jnyer27/noaa-osmand-charts/releases/download/$vectorTag/\"")
    }

    signingConfigs {
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("NOAAROUTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOAAROUTE_KEY_ALIAS")
                keyPassword = System.getenv("NOAAROUTE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The chart MBTiles and glyph PBFs are copied out with openFd, which needs them stored
    // uncompressed. SQLite cannot be opened from inside the APK either way.
    androidResources {
        noCompress += listOf("mbtiles", "pbf")
    }
}

dependencies {
    // In-app chart. BSD-2-Clause; no API key and no online base map.
    // AndroidX comes in with it (annotation, and whatever the renderer pulls). The rest of
    // the app stays on the platform widgets.
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    testImplementation("junit:junit:4.13.2")
}

// Show assertion messages (route summaries) in the CI log when a unit test fails
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}
