plugins {
    id("barusikozako.java17-library")
}

tasks.jar { archiveBaseName.set("barusikozako_backend_spi") }

val configFixture = sourceSets.create("configFixture")
dependencies.add(configFixture.implementationConfigurationName, sourceSets.main.get().output)

val checkConfigUnits = tasks.register<JavaExec>("checkConfigUnits") {
    classpath = configFixture.runtimeClasspath
    mainClass.set("io.github.zct_studio.barusikozako.backend.ConfigUnitsChecks")
}

tasks.check { dependsOn(checkConfigUnits) }
