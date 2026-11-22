plugins {
    id("barusikozako.java17-library")
}

val accessFixture = sourceSets.create("accessFixture")
dependencies {
    api(project(":api:common"))
    add(accessFixture.implementationConfigurationName, project(":api:common"))
    add(accessFixture.implementationConfigurationName, sourceSets.main.get().output)
}

val accessChecks = tasks.register<JavaExec>("accessChecks") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies authorization, admission, cancellation, and retention for the API host."
    classpath = (accessFixture.runtimeClasspath - sourceSets.main.get().output) + files(tasks.jar)
    mainClass.set("io.github.zct_studio.barusikozako.apihost.AccessChecks")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}

tasks.named("check") {
    dependsOn(accessChecks)
}

tasks.jar { archiveBaseName.set("barusikozako_api_host") }
