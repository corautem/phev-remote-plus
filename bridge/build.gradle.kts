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
    manifest { attributes["Main-Class"] = "io.github.anwo91.phevlink.bridge.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}
