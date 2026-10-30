plugins {
    id("com.android.application") version "8.7.3" apply false
    // 2.2 matches MapLibre Android 13, which ships Kotlin 2.2 metadata. 2.0 cannot read it.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
