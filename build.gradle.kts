allprojects {
    group = "gg.ginco.hytest"
    version = "0.1.0"
}

subprojects {
    repositories {
        maven("https://maven.hytale.com/release")
        maven("https://maven.hytale.com/pre-release")
        mavenCentral()
    }
}
