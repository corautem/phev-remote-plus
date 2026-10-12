plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.anwo91.phevlink.bridge.MainKt")
    applicationName = "phev-bridge"
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}

// Single self-contained jar: java -jar phev-bridge.jar
tasks.register<Jar>("fatJar") {
    archiveFileName.set("phev-bridge.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The release number (same as the app's 1.0.N), read back by the bridge to offer updates.
    val buildNumber = System.getenv("GITHUB_RUN_NUMBER")
    manifest {
        attributes["Main-Class"] = "io.github.anwo91.phevlink.bridge.MainKt"
        attributes["Implementation-Title"] = "phev-bridge"
        attributes["Implementation-Version"] = if (buildNumber != null) "1.0.$buildNumber" else "dev"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}
