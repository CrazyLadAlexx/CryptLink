plugins {
    base
}

allprojects {
    group = "me.alex.cryptlink"
    version = "2.0.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:6.1.3"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" }, "releaseChecksums")
}

val releaseJars = subprojects.map { it.tasks.named<Jar>("jar").flatMap { jar -> jar.archiveFile } }

tasks.register("releaseChecksums") {
    dependsOn(releaseJars)
    inputs.files(releaseJars)
    val outputDirectory = layout.buildDirectory.dir("checksums")
    outputs.dir(outputDirectory)
    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        inputs.files.files.sortedBy { it.name }.forEach { jar ->
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
            directory.resolve("${jar.name}.sha256").writeText("${java.util.HexFormat.of().formatHex(digest)}  ${jar.name}\n")
        }
    }
}
