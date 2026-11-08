// noaa-route: the app's planner (:core) on the command line, for any OS with Java 17+.
// gradle :cli:run --args="-f 39.32371,-76.39998 -t 38.9784,-76.4922 -d 3 -a 10"
// gradle :cli:cliZip builds build/dist/noaa-route.zip: noaa-route.jar (everything in one jar)
// and the launchers noaa-route (macOS, Linux, Termux) and noaa-route.cmd (Windows).
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

// The app's numbering: 0.1.<CI run>, 0.1.0 for a local build
version = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

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

// One runnable jar: the CLI, :core, sqlite-jdbc (with its native SQLite) and slf4j-nop
val cliJar = tasks.register<Jar>("cliJar") {
    archiveFileName.set("noaa-route.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "noaa-route",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

// The jar and its launchers, for the cli release
tasks.register<Zip>("cliZip") {
    archiveFileName.set("noaa-route.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    into("noaa-route") {
        from(cliJar)
        from("launchers") { filePermissions { unix("rwxr-xr-x") } }
    }
}
