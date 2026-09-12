plugins {
    `java-library`
}

dependencies {
    implementation(project(":cryptlink-api"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}

tasks.jar {
    from(project(":cryptlink-api").tasks.named("jar").map { zipTree(it.outputs.files.singleFile) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("velocity-plugin.json") {
        expand("version" to pluginVersion)
    }
}
