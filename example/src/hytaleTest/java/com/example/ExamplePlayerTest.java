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
}
