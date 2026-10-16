# hytest

A testing framework for [Hytale](https://hytale.com) plugins. Write tests as
`@HytaleTest` methods and run them **inside a real Hytale server** — no client,
no mocks. Results flow through JUnit 5, so they show up in IntelliJ and CI just
like ordinary tests.

```java
public class MyFeatureTest {
    @HytaleTest
    void worldIsAlive(HytaleTestHelper t) {
        t.assertTrue(t.getWorld().isAlive(), "world should be alive");
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
- A **Hytale server** (`HytaleServer.jar` + `Assets.zip`). When the
  [`hygradle`](https://github.com/ginco-org/hygradle) plugin is applied, hytest
  declares a dependency on its `provisionServer` task, so `hytaleTest` downloads
  the server files automatically on a clean checkout. Without hygradle, point
  hytest at any existing server directory via `hytest { serverJar } / serverDir`.

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
    id("gg.ginco.hygradle") version "0.7.1"   // plugin build setup + server provisioning
    id("gg.ginco.hytest")   version "0.3.0"   // this framework
}

hytale {
    group = "MyGroup"
    mainClass = "com.example.myplugin.MyPlugin"
    serverVersion = "0.7.2"
}
```

That's it — hytest adds a `hytaleTest` source set and a `hytaleTest` task,
reuses hygradle's server directory and compile-only server API, and runs
hygradle's `provisionServer` first when the server files are missing.

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
        t.assertTrue(t.getWorld().isAlive(), "world should be alive");
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
t.assertEventually(() -> t.assertTrue(...)); // BLOCK until the condition holds, then continue
t.runAfterDelay(20, () -> { ... });       // ~1 second later (1 tick ≈ 50ms)
```

> **Note on threading:** the test body runs **off** the world thread so it can
> block (e.g. wait for a join) without deadlocking the world. Use
> `t.runOnWorld(...)` for anything that must run on the world thread. Reads like
> `getWorld()`, `tick()`, and `transform()` are safe from the test thread.

### `succeedWhen` vs `assertEventually`

They share the same throwing contract — `AssertionError` means "not yet",
anything else fails immediately — but serve different jobs:

- `succeedWhen(condition)` is for the test's **terminal** success condition. It
  returns immediately and completes the test in the background once the
  condition holds. Assertions placed *after* it race the condition — a
  recurring trap.
- `assertEventually(timeoutSeconds = 10, condition)` **blocks** the test body
  (~50ms poll loop) until the condition holds, then returns, so code after it
  runs only once the awaited state is true. It never touches the test's
  verdict, so it composes freely with a later `succeedWhen(...)`/`succeed()`.

On timeout it throws the last `AssertionError` wrapped as
`"condition not met within Ns: <message>"`.

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
| `getUuid()` / `getName()` | Identity |
| `transform()` | Current `Transform` (position/rotation) |
| `runCommand(String)` | Execute a command as the player (leading `/` optional; root lookup is case-insensitive) |
| `disconnect()` | Remove the player from the universe; the handle becomes **stale** (removal settles asynchronously, bounded ~5s server-side) |
| `reconnect()` | Rejoin the same world with the same uuid/name on a fresh connection; completes with a **new** handle |
| `spawn(World, String)` / `spawn(World, String, UUID)` | Lower-level construction for advanced cases (fixed uuid for reconnect flows) |

### Disconnect & reconnect

Spawn with a fixed uuid to test disconnect/rejoin flows:

```java
@HytaleTest(timeoutSeconds = 60)
void rejoinsWithSameIdentity(HytaleTestHelper t) {
    UUID aliceId = UUID.randomUUID();
    SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice", aliceId);

    alice.disconnect();
    t.assertEventually(() -> t.assertPlayerCount(0)); // removal settles a tick later

    SimulatedPlayer aliceAgain = alice.reconnect().join();
    t.assertEquals(aliceId, aliceAgain.getUuid(), "same identity, fresh handle");
    t.succeed();
}
```

Behavior, pinned against server 0.7.2:

- **Reconnect while still connected fails fast** with `IllegalStateException`
  ("player is still connected — call disconnect() first"). There is no implicit
  disconnect: a live session at reconnect time is almost always a test bug.
- **`disconnect()` removal is asynchronous** — the uuid stays registered until
  the world store finishes cleanup (bounded ~5s by the server), and
  `Universe.addPlayer` *rejects* a duplicate uuid during that window (it
  completes with `null`, it does not throw). `reconnect()` therefore waits out
  the old registration itself (up to 10s), so `disconnect(); reconnect().join()`
  works without any manual polling in between.
- `reconnect()` fires `PlayerConnectEvent` again and routes the player into the
  **same world**, exactly like a first join; the old handle stays stale.

### `runCommand` failure contract

A typo'd root command (`minigame creat`, `foo bar`) **fails the returned
future** with an `IllegalStateException` (`no command named '...' (input: '...')`)
instead of silently doing nothing — the server's own dispatcher would just send
the player a "command not found" message and complete normally, which turned
misspelled commands into passing tests that asserted nothing.

Outcomes the command itself produces at runtime still complete **normally**, so
tests can assert on them via `receivedMessages()`: permission denials and
rejected arguments surface as messages to the player, not as future failures.
Note that rejected-argument outcomes are not distinguishable from successful
execution at the future level — if you need to pin argument handling, assert on
the captured messages (e.g. `t.assertTrue(alice.sawMessage("..."), ...)`).

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
| `getWorld()` | The ephemeral test `World` |
| `tick()` | Current world tick counter |
| `succeed()` / `fail(msg)` / `fail(throwable)` | Complete the test |
| `succeedWhen(Runnable)` | Poll until `condition` stops throwing, then succeed |
| `assertEventually(timeoutSeconds = 10, condition)` | **Block** until `condition` stops throwing, then continue the test |
| `runAfterDelay(ticks, action)` | Run `action` after approximately `ticks` ticks |
| `assertTrue` / `assertFalse` / `assertEquals` / `assertNotNull` | Throwing assertions (usable inside `succeedWhen`/`assertEventually`) |
| `spawnSimulatedPlayer()` / `spawnSimulatedPlayer(name)` / `spawnSimulatedPlayer(name, uuid)` | Inject a simulated player (fixed uuid for reconnect flows) |
| `assertPlayerCount(expected)` | Assert the test world holds `expected` players |
| `runOnWorld(action)` | Run `action` on the world thread and block until done |

The `@HytaleTest` annotation:

| Element | Default | Description |
|---------|---------|-------------|
| `value` | `""` | Optional display name (defaults to `Class.method`) |
| `timeoutSeconds` | `30` | Wall-clock budget before the test fails as a timeout |

## Configuration

The `hytest { }` extension. Defaults mirror hygradle when it is applied, so with
hygradle you normally need no `hytest { }` block at all — leave `serverJar` and
`assets` unset and let `provisionServer` populate hygradle's `run/`. Overrides
are only for pointing hytest at a server outside that directory.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `version` | `String` | `0.3.0` | Hytest artifact version to depend on (`runtime` + `junit5`); set it to pin a different release than the plugin's default |
| `serverDir` | `Directory` | hygradle's `run/` | Where to find `HytaleServer.jar` + `Assets.zip` |
| `serverJar` | `String` | `<serverDir>/HytaleServer.jar` | Explicit server jar (overrides `serverDir`; bypasses provisioning) |
| `assets` | `String` | `<serverDir>/Assets.zip` | Explicit assets dir or zip (overrides `serverDir`; bypasses provisioning) |
| `javaExecutable` | `String` | build JDK | Java used to launch the server |
| `jvmArgs` | `List<String>` | `-Xmx4G -Xms1G` | JVM args for the server process |
| `timeoutSeconds` | `Long` | `300` | Per-run server budget |
| `firstFrameGraceSeconds` | `Long` | `90` | Fail fast when the test plugin emits nothing within this window of server start (rejected jar); distinct from `timeoutSeconds`, which keeps covering mid-run stalls |
| `showServerLogs` | `Boolean` | `false` | Forward server stdout/stderr to the build console |

Only when using a server that hygradle did not provision:

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
  needed in the test JVM. Class/method filters (`--tests`, IDE single-test
  runs) propagate to the server: the engine hands the runtime its pruned
  selection, so only those tests actually execute.
- **Execution** merges `hytest-runtime` with your compiled tests into a single
  plugin jar, drops it into a fresh server's `mods/`, and launches the server
  with `hytest.run=true`. The jar's manifest is **your plugin's manifest** (from
  the `hytale { }` extension) with hytest attached as a `SubPlugins` entry, so
  the server loads your real plugin — identity, data directory and full
  `setup()`/`start()` lifecycle — plus hytest from the same jar and classloader.
  Without hygradle the jar stays a plain hytest plugin (your plugin is not
  loaded; mount it yourself if needed). The runtime discovers tests in-server
  (where the classes live), runs each in its own void world, and streams
  compact JSON frames on stdout; the engine maps them back to JUnit results.

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

> **Run the suite locally when you can.** On CI the integration job takes
> ~30 minutes (2-core runner + the server's world-disposal pipeline costs
> ~30s per per-test world); locally it finishes in well under a minute. CI
> cancels superseded runs per branch, but a pushed commit still burns a
> 30-minute runner before it reports — so iterate locally, let CI confirm.
> Use `-PhytestLogs` on the example suite to see the full server + per-test
> timeline.

## License

[MIT](LICENSE).
