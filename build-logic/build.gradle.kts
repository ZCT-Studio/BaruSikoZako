plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        register("java17Library") {
            id = "barusikozako.java17-library"
            implementationClass = "barusikozako.buildlogic.Java17Library"
        }
    }
}
