plugins {
    id("barusikozako.java17-library")
}

val gitignoreBundle = configurations.create("gitignoreBundle") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    api(project(":platforms:spi"))
    api(project(":backends:spi"))
    compileOnly(project(":engine:gitignore"))
    testImplementation(project(":engine:gitignore"))
    add(gitignoreBundle.name, project(":engine:gitignore"))
}

val recoveryFixture = sourceSets.create("recoveryFixture")
dependencies {
    add(recoveryFixture.implementationConfigurationName, sourceSets.main.get().output)
    add(recoveryFixture.implementationConfigurationName, project(":platforms:spi"))
    add(recoveryFixture.implementationConfigurationName, project(":backends:spi"))
}

val recoveryFaults = tasks.register<JavaExec>("recoveryFaults") {
    description = "Exercises offline recovery and rollback with independent child JVM crashes."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = (recoveryFixture.runtimeClasspath - sourceSets.main.get().output) + files(tasks.jar)
    mainClass.set("io.github.zct_studio.barusikozako.core.RestoreFaults")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.named("check") {
    dependsOn(recoveryFaults)
}

tasks.jar {
    archiveBaseName.set("barusikozako_core")
    from(gitignoreBundle.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        include("io/github/zct_studio/barusikozako/internal/gitignore/**")
        include("META-INF/barusikozako/gitignore/**")
    }
}
