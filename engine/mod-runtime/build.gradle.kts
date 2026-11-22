plugins {
    id("barusikozako.java17-library")
}

// Keep the internal runtime and the external API binary types distinct when both mods are loaded.
val runtimePackages = linkedMapOf(
    "io.github.zct_studio.barusikozako.apihost" to "io.github.zct_studio.barusikozako.runtime.host",
    "io.github.zct_studio.barusikozako.api" to "io.github.zct_studio.barusikozako.runtime",
)
val runtimeSources = tasks.register<Sync>("generateRuntimeSources") {
    from(rootProject.file("api/common/src/main/java"), rootProject.file("api/host/src/main/java"))
    include("**/*.java")
    filteringCharset = "UTF-8"
    inputs.property("runtimePackages", runtimePackages)
    filter { line: String ->
        runtimePackages.entries.fold(line) { source, (original, runtime) -> source.replace(original, runtime) }
    }
    into(layout.buildDirectory.dir("generated/sources/runtime"))
}

sourceSets.main { java.srcDir(runtimeSources) }
tasks.jar { archiveBaseName.set("barusikozako_mod_runtime") }
