import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    id("barusikozako.java17-library")
}

val common = configurations.create("publicApiPackage") { isTransitive = false }
dependencies.add(common.name, project(":api:common"))

tasks.jar {
    from(rootProject.file("resource/barusikozako_api/icon.png"))
    archiveBaseName.set("barusikozako_api-fabric")
    dependsOn(common)
    from(common.map { zipTree(it) }) { include("io/github/zct_studio/barusikozako/**") }
    val metadata = mapOf("productVersion" to project.version.toString())
    @Suppress("UNCHECKED_CAST")
    val info = (rootProject.extra["productMetadata"] as Map<*, *>)["api"] as Map<String, Any?>
    val metadataFields = mapOf("descriptionJson" to groovy.json.JsonOutput.toJson(info["description"]).removeSurrounding("\""),
        "authorsJson" to (info["authors"] as List<*>).joinToString("\",\"") { groovy.json.JsonOutput.toJson(it).removeSurrounding("\"") },
        "homepageJson" to groovy.json.JsonOutput.toJson(info["homepage"]).removeSurrounding("\""),
        "sourcesJson" to groovy.json.JsonOutput.toJson(info["sources"]).removeSurrounding("\""),
        "issuesJson" to groovy.json.JsonOutput.toJson(info["issues"]).removeSurrounding("\""))
    inputs.properties(metadata)
    inputs.properties(metadataFields)
    from(rootProject.file("api/shared/fabric/src/main/fabric.mod.json")) { expand(metadata + metadataFields) }
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
val game = parent!!.name.removePrefix("mc").replace('_', '.')
PublishProductJar.register(project, "publishApiProduct", "api", "barusikozako_api", "${extra["fabricPackageLoader"]}$game", false,
    tasks.jar.flatMap { it.archiveFile })
