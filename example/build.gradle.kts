plugins {
    id("gg.ginco.hygradle") version "0.4.0"
    id("gg.ginco.hytest")
}

version = "1.0.0"

hytale {
    group = "Example"
    name = "HytestExample"
    mainClass = "com.example.ExamplePlugin"
    serverVersion = "0.6.2"
    server {
        serverDir = file("../run")
    }
}

hytest {
    serverJar = file("../run/HytaleServer.jar").absolutePath
    assets = file("../run/Assets.zip").absolutePath
}
