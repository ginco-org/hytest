# hytest

A testing framework for [Hytale](https://hytale.com) plugins. Write tests as
`@HytaleTest` methods and run them **inside a real Hytale server** — no client,
no mocks. Results flow through JUnit 5, so they show up in IntelliJ and CI just
like ordinary tests.

```java
public class MyFeatureTest {
    @HytaleTest
    void worldIsAlive(HytaleTestHelper t) {
        t.assertTrue(t.world().isAlive(), "world should be alive");
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void simulatedPlayerJoins(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");
        t.assertPlayerCount(1);                 // a real player is in the world
        alice.runCommand("help").join();        // executed as Alice
        t.succeed();
    }
}
```

## Why

Hytale plugins are hard to test automatically: the interesting logic touches the
world, entities, and players, and the only way to exercise it has traditionally
been to start the game and play. **hytest** is a GameTest-style framework
(inspired by Minecraft's [GameTest](https://minecraft.wiki/w/GameTest)) that
boots an ephemeral, in-memory server per run, drives real gameplay code, and
reports results as JUnit tests — including **simulated players** that join over
a fake connection with no game client required.

Everything runs against the **published server jar** using only public API — no
source patches, no reflection, no NMS.

## How it works

```
./gradlew hytaleTest
        │
        ▼
┌─ Gradle / test JVM ───────────────────────────────────┐
│  hytest-junit5   (custom JUnit 5 TestEngine)          │
│   • ASM scan discovers @HytaleTest (no class loading)  │
│   • launches the server subprocess                     │
│   • relays result frames → JUnit (IDE/CI XML)          │
└──────────────────┬─────────────────────────────────────┘
                   │  stdout JSON-lines
┌──────────────────▼─────────────────────────────────────┐
│  Inside the server process                             │
│  hytest-runtime  (a plugin, deployed to mods/)         │
│   • spins up an ephemeral void world per test          │
│   • runs each test off the world thread                │
│   • SimulatedPlayer joins via a fake ChannelConnection │
│   • prints results on raw stdout, shuts down on done   │
└────────────────────────────────────────────────────────┘
```

Three artifacts:

| Module | Runs in | Role |
|--------|---------|------|
| `hytest-runtime` | the server (a plugin in `mods/`) | `HytaleTestHelper`, ephemeral worlds, `SimulatedPlayer`, result reporting |
| `hytest-junit5` | the Gradle/test JVM | JUnit 5 `TestEngine` — discovery + server subprocess + result relay |
| `hytest-gradle` | the build | Gradle plugin `gg.ginco.hytest` — `hytaleTest` source set, merged plugin jar, `hytaleTest` task |

## Requirements

- **Java 25** (the Hytale server requires JRE 25)
- **Gradle** (the plugin applies the Java plugin and a 25 toolchain)
- A **Hytale server** (`HytaleServer.jar` + `Assets.zip`) — provisioned by
  [`hygradle`](https://github.com/ginco-org/hygradle)'s `runServer`, or any
  existing server directory. hytest cooperates with hygradle automatically.

## Setup

**`settings.gradle.kts`**
```kotlin
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
```

**`build.gradle.kts`**
```kotlin
plugins {
    id("gg.ginco.hygradle") version "0.4.0"   // plugin build setup + server provisioning
    id("gg.ginco.hytest")   version "0.1.0"   // this framework
}

hytale {
    group = "MyGroup"
    mainClass = "com.example.myplugin.MyPlugin"
    serverVersion = "0.6.2"
}
```

That's it — hytest adds a `hytaleTest` source set and a `hytaleTest` task, and
reuses hygradle's server directory and compile-only server API.

## Writing tests

Tests live in `src/hytaleTest/java/`. Each is an instance method annotated
`@HytaleTest` taking a single `HytaleTestHelper`. Its declaring class needs a
public no-arg constructor.

```java
package com.example;

import gg.ginco.hytest.runtime.api.HytaleTest;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;

public class WorldTest {
    @HytaleTest
    void worldIsAlive(HytaleTestHelper t) {
        t.assertTrue(t.world().isAlive(), "world should be alive");
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 10)
    void tickAdvances(HytaleTestHelper t) {
        long start = t.tick();
        t.succeedWhen(() -> t.assertTrue(t.tick() > start, "tick should advance"));
    }
}
```

A fresh ephemeral world is created for each test and torn down afterwards.

### Completing a test

A test passes when it calls `succeed()` (or a success-condition helper passes),
and fails if it throws or calls `fail(...)`. If neither happens before
`timeoutSeconds`, it fails as a timeout.

```java
t.succeed();                              // pass now
t.fail("not what I expected");            // fail now
t.succeedWhen(() -> t.assertTrue(...));   // poll until the condition stops throwing
t.runAfterDelay(20, () -> { ... });       // ~1 second later (1 tick ≈ 50ms)
```

> **Note on threading:** the test body runs **off** the world thread so it can
> block (e.g. wait for a join) without deadlocking the world. Use
> `t.runOnWorld(...)` for anything that must run on the world thread. Reads like
> `world()`, `tick()`, and `transform()` are safe from the test thread.

## Simulated players

The headline feature. A simulated player joins the world for real —
`PlayerConnectEvent` and `AddPlayerToWorldEvent` fire, the player exists in the
`Universe` and the target world, and commands run as that player — but there is
no game client.

```java
@HytaleTest(timeoutSeconds = 60)
void simulatedPlayerJoins(HytaleTestHelper t) {
    SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");

    t.assertNotNull(alice.ref(), "player should exist");
    t.assertPlayerCount(1);                       // exactly one player in the world

    alice.runCommand("help").join();              // runs as Alice (CommandSender)
    System.out.println(alice.transform());        // position/rotation
    t.succeed();
}
```

`SimulatedPlayer` exposes:

| Method | Description |
|--------|-------------|
| `ref()` | The underlying `PlayerRef` (a `CommandSender`) |
| `uuid()` / `name()` | Identity |
| `transform()` | Current `Transform` (position/rotation) |
| `runCommand(String)` | Execute a command as the player (leading `/` optional) |
| `spawn(World, String)` | Lower-level construction for advanced cases |

## Running tests

```bash
./gradlew hytaleTest
```

The task boots the server once, runs every discovered `@HytaleTest`, and maps
each to a JUnit result. Reports land in the usual places:

- `build/test-results/hytaleTest/*.xml` (JUnit XML for CI)
- `build/reports/tests/hytaleTest/index.html` (HTML report)
- the IntelliJ / VS Code test view (green/red per test, click-to-run)

Each server run is isolated in a fresh temp directory, so runs never collide.

To see the server's own logs inline (useful when debugging a boot failure):

```kotlin
hytest { showServerLogs = true }
```

## `HytaleTestHelper` reference

| Method | Description |
|--------|-------------|
| `world()` | The ephemeral test `World` |
| `tick()` | Current world tick counter |
| `succeed()` / `fail(msg)` / `fail(throwable)` | Complete the test |
| `succeedWhen(Runnable)` | Poll until `condition` stops throwing, then succeed |
| `runAfterDelay(ticks, action)` | Run `action` after approximately `ticks` ticks |
| `assertTrue` / `assertFalse` / `assertEquals` / `assertNotNull` | Throwing assertions (usable inside `succeedWhen`) |
| `spawnSimulatedPlayer()` / `spawnSimulatedPlayer(name)` | Inject a simulated player |
| `assertPlayerCount(expected)` | Assert the test world holds `expected` players |
| `runOnWorld(action)` | Run `action` on the world thread and block until done |

The `@HytaleTest` annotation:

| Element | Default | Description |
|---------|---------|-------------|
| `value` | `""` | Optional display name (defaults to `Class.method`) |
| `timeoutSeconds` | `30` | Wall-clock budget before the test fails as a timeout |

## Configuration

The `hytest { }` extension. Defaults mirror hygradle when it is applied.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `version` | `String` | `0.1.0` | Hytest artifact version to depend on |
| `serverDir` | `Directory` | hygradle's `run/` | Where to find `HytaleServer.jar` + `Assets.zip` |
| `serverJar` | `String` | `<serverDir>/HytaleServer.jar` | Explicit server jar (overrides `serverDir`) |
| `assets` | `String` | `<serverDir>/Assets.zip` | Explicit assets dir or zip (overrides `serverDir`) |
| `javaExecutable` | `String` | build JDK | Java used to launch the server |
| `jvmArgs` | `List<String>` | `-Xmx4G -Xms1G` | JVM args for the server process |
| `timeoutSeconds` | `Long` | `300` | Per-run server budget |
| `showServerLogs` | `Boolean` | `false` | Forward server stdout/stderr to the build console |

```kotlin
hytest {
    serverJar = file("/opt/hytale/HytaleServer.jar").absolutePath
    assets    = file("/opt/hytale/Assets.zip").absolutePath
    timeoutSeconds = 120
    showServerLogs = true
}
```

## Discovery & execution

- **Discovery** scans the `hytaleTest` classpath for `@HytaleTest` using ASM,
  reading bytecode without loading classes — so the server's classes are never
  needed in the test JVM. JUnit's standard class/method filters work as usual.
- **Execution** merges `hytest-runtime` with your compiled tests into a single
  plugin jar, drops it into a fresh server's `mods/`, and launches the server
  with `hytest.run=true`. The runtime discovers tests in-server (where the
  classes live), runs each in its own void world, and streams compact JSON
  frames on stdout; the engine maps them back to JUnit results.

## Project layout

```
hytest/
├── runtime/         hytest-runtime — the in-server plugin
├── junit5/          hytest-junit5 — the JUnit 5 TestEngine
├── gradle-plugin/   hytest-gradle — the gg.ginco.hytest Gradle plugin
├── example/         composite-build demo (./gradlew hytaleTest works there)
└── flake.nix        Nix dev shell (JDK 25)
```

## Development

A [Nix](https://nixos.org) flake provides JDK 25 and tooling:

```bash
nix develop          # JDK 25 on PATH, JAVA_HOME set
./gradlew build
./gradlew -p example hytaleTest   # end-to-end smoke test
```

## License

[MIT](LICENSE).
