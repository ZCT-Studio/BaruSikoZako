import java.io.File

plugins {
    id("barusikozako.java17-library")
}

val jarTask = tasks.jar
val compatibilityFixture = sourceSets.create("compatibilityFixture")
dependencies {
    add(compatibilityFixture.implementationConfigurationName, sourceSets.main.get().output)
}
val gitIgnoreCompatibility = tasks.register<JavaExec>("gitIgnoreCompatibility") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compares the packaged matcher against native Git"
    classpath = files(compatibilityFixture.output, jarTask)
    mainClass.set("io.github.zct_studio.barusikozako.internal.gitignore.GitIgnoreCompatibility")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    providers.gradleProperty("comparisonJgitClasspath").orNull?.let {
        classpath(files(it.split(File.pathSeparator)))
        args("--check-jgit-coexistence")
    }
}

tasks.jar { archiveBaseName.set("barusikozako_gitignore") }
tasks.named("check") { dependsOn(gitIgnoreCompatibility) }
