package com.example;

import gg.ginco.hytest.runtime.api.HytaleTest;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;

public class ExampleTest {

    @HytaleTest
    void worldIsAlive(HytaleTestHelper t) {
        t.assertTrue(t.getWorld().isAlive(), "freshly created world should be alive");
        t.succeed();
    }

    @HytaleTest
    void tickAdvances(HytaleTestHelper t) {
        long start = t.tick();
        t.succeedWhen(() -> t.assertTrue(t.tick() > start, "tick should advance beyond " + start));
    }

    // Same condition as tickAdvances, but in the "wait, then keep testing"
    // style: assertEventually BLOCKS until the condition holds, so code after
    // it runs only once the tick counter has actually moved. Assertions
    // placed after succeedWhen would race the condition instead.
    @HytaleTest
    void tickAdvancesThenTestContinues(HytaleTestHelper t) {
        long start = t.tick();

        t.assertEventually(() -> t.assertTrue(t.tick() > start, "tick should advance beyond " + start));

        // We only reach this line once the condition held, so this cannot race.
        long observed = t.tick();
        t.assertTrue(observed > start, "tick should remain past the start after assertEventually returns");
        t.succeed();
    }
}
