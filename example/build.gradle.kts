plugins {
    id("gg.ginco.hygradle") version "0.2.0"
    id("gg.ginco.hytest")
}

version = "1.0.0"

hytale {
    group = "Example"
    name = "HytestExample"
    mainClass = "com.example.ExamplePlugin"
    serverVersion = "0.5.6"
}

hytest {
    serverJar = file("../run/HytaleServer.jar").absolutePath
    assets = file("../run/Assets.zip").absolutePath
}
