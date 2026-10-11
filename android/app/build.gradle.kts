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

    defaultConfig {
        applicationId = "com.jnyer27.noaaroute"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
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
