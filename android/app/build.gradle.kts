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
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // APKs built from a branch read the route-pack-beta release (packs from branch runs
        // of route-pack.yml); main and local builds read the route-pack release
        val branch = System.getenv("GITHUB_REF_NAME")
        val packTag = if (branch == null || branch == "main") "route-pack" else "route-pack-beta"
        buildConfigField("String", "PACK_BASE", "\"https://github.com/jnyer27/noaa-osmand-charts/releases/download/$packTag/\"")
        // Vector chart maps for OsmAnd (vector-maps.yml), the same way
        val vectorTag = if (branch == null || branch == "main") "vector-maps" else "vector-maps-beta"
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
