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
}
