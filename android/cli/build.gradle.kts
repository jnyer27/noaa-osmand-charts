// noaa-route: the app's planner (:core) on the command line, for any OS with Java 17+.
// gradle :cli:run --args="-f 39.32371,-76.39998 -t 38.9784,-76.4922 -d 3 -a 10"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.jnyer27.noaaroute.cli.MainKt")
    applicationName = "noaa-route"
    applicationDefaultJvmArgs = listOf("-Xmx1g")
}

// Files are written relative to where the command is run, not the cli folder
tasks.named<JavaExec>("run") {
    workingDir = File(System.getenv("NOAA_ROUTE_CWD") ?: rootProject.projectDir.path)
}

dependencies {
    implementation(project(":core"))
    // Reads the region chart (MBTiles, SQLite) for the depth check. Ships native SQLite for
    // Windows, macOS and Linux; slf4j-nop keeps its logging quiet.
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}
