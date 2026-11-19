plugins {
    base
}

allprojects {
    version = providers.gradleProperty("productVersion").get()
}
