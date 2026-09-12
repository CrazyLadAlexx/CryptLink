plugins {
    `java-library`
}

dependencies {
    implementation(project(":cryptlink-api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.jar {
    from(project(":cryptlink-api").tasks.named("jar").map { zipTree(it.outputs.files.singleFile) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}
