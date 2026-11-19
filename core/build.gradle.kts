plugins {
    id("barusikozako.java17-library")
}

dependencies {
    api(project(":platform-spi"))
    api(project(":backend-spi"))
}

val recoveryFixture = sourceSets.create("recoveryFixture")
dependencies {
    add(recoveryFixture.implementationConfigurationName, sourceSets.main.get().output)
    add(recoveryFixture.implementationConfigurationName, project(":platform-spi"))
    add(recoveryFixture.implementationConfigurationName, project(":backend-spi"))
}

val recoveryFaults = tasks.register<JavaExec>("recoveryFaults") {
    description = "Exercises offline recovery and rollback with independent child JVM crashes."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = (recoveryFixture.runtimeClasspath - sourceSets.main.get().output) + files(tasks.jar)
    mainClass.set("barusikozako.core.RestoreFaults")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.named("check") {
    dependsOn(recoveryFaults)
}
