plugins {
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("java17Library") {
            id = "barusikozako.java17-library"
            implementationClass = "io.github.zct_studio.barusikozako.buildlogic.Java17Library"
        }
        register("minecraftSources") {
            id = "barusikozako.minecraft-sources"
            implementationClass = "io.github.zct_studio.barusikozako.buildlogic.MinecraftSourcesPlugin"
        }
    }
}
