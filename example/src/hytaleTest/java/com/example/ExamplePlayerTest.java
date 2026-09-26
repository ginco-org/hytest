package com.example;

import gg.ginco.hytest.runtime.api.HytaleTest;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;
import gg.ginco.hytest.runtime.player.SimulatedPlayer;

public class ExamplePlayerTest {

    @HytaleTest(timeoutSeconds = 60)
    void simulatedPlayerJoinsWorld(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");
        t.assertNotNull(alice, "simulated player should be created");
        t.assertPlayerCount(1);
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void simulatedPlayerTeleports(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");

        alice.teleport(100, 64, 200).join();

        // Position settle is asynchronous and CI runners are slow — poll
        // instead of assuming a fixed tick count and tight tolerance.
        t.assertEventually(10, () ->
            t.assertPlayerNear(alice, 100, 64, 200, 0.5, "player should be at teleport location"));
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void simulatedPlayerRunsCommand(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");
        alice.runCommand("help").join();
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void simulatedPlayerDisconnects(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");
        t.assertPlayerCount(1);

        alice.disconnect();

        // Player removal is processed asynchronously by the server (0.6.x) —
        // poll instead of assuming it completes within a fixed number of ticks.
        // The server bounds the removal at 5 seconds, so poll at most that long.
        boolean removed = false;
        for (int i = 0; i < 100 && !removed; i++) {
            t.waitForTicks(1);
            removed = !alice.isValid();
        }
        t.assertFalse(alice.isValid(), "player should be invalid after disconnect");
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void simulatedPlayerChat(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");
        alice.chat("Hello world!").join();
        t.succeed();
    }
}
