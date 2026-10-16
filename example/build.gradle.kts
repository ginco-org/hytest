plugins {
    id("gg.ginco.hygradle") version "0.7.1"
    id("gg.ginco.hytest")
}

version = "1.0.0"

hytale {
    group = "Example"
    name = "HytestExample"
    mainClass = "com.example.ExamplePlugin"
    serverVersion = "0.7.2"
    server {
        serverDir = file("../run")
    }
}

// CI runners are 2-core: boot + 14 fresh worlds can exceed the 300s default.
// These are caps, not delays — local green runs still finish in ~30s.
hytest {
    timeoutSeconds = 900
    firstFrameGraceSeconds = 180
    showServerLogs = providers.gradleProperty("hytestLogs").isPresent
}
