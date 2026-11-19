plugins {
    id("barusikozako.java17-library")
}


val apiModJar = tasks.register<Jar>("apiModJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds the standalone Fabric public API mod."
    archiveBaseName.set("zakobak_api-fabric")
    val metadata = mapOf("productVersion" to project.version.toString())
    inputs.properties(metadata)
    from(sourceSets.main.map { it.output })
    from("src/fabric/fabric.mod.json") { expand(metadata) }
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
tasks.assemble { dependsOn(apiModJar) }
