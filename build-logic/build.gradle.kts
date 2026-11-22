plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        register("java17Library") {
            id = "barusikozako.java17-library"
            implementationClass = "io.github.zct_studio.barusikozako.buildlogic.Java17Library"
        }
    }
}
