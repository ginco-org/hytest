plugins {
    id("gg.ginco.hygradle") version "0.5.0"
    id("gg.ginco.hytest")
}

version = "1.0.0"

hytale {
    group = "Example"
    name = "HytestExample"
    mainClass = "com.example.ExamplePlugin"
    serverVersion = "0.6.8"
    server {
        serverDir = file("../run")
    }
}
