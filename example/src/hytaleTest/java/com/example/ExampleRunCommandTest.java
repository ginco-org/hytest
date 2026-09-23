package com.example;

import gg.ginco.hytest.runtime.api.HytaleTest;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;
import gg.ginco.hytest.runtime.player.SimulatedPlayer;

import java.util.concurrent.CompletionException;

/**
 * Contract of {@link SimulatedPlayer#runCommand}: unresolvable root commands
 * fail the returned future; commands the server actually dispatched complete
 * normally regardless of their runtime outcome.
 */
public class ExampleRunCommandTest {

    @HytaleTest(timeoutSeconds = 60)
    void runCommandFailsOnUnknownRoot(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");

        try {
            alice.runCommand("definitelynotacommand arg").join();
            t.fail("runCommand should fail on an unknown root command");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            t.assertTrue(cause instanceof IllegalStateException,
                    "cause should be IllegalStateException, got: " + cause);
            t.assertTrue(cause.getMessage().contains("no command named 'definitelynotacommand'"),
                    "unexpected message: " + cause.getMessage());
            t.assertTrue(cause.getMessage().contains("input: 'definitelynotacommand arg'"),
                    "unexpected message: " + cause.getMessage());
        }
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void runCommandStillCompletesOnTypoAfterKnownRoot(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");

        // "help" exists, "creat" is not a valid argument for it: dispatched,
        // rejected at runtime, future still completes — the rejection is an
        // observable player message, not a transport failure.
        alice.runCommand("help creat").join();
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void runCommandCompletesNormallyOnKnownRoot(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");

        // Root lookup is case-insensitive, like the server's own dispatcher.
        alice.runCommand("help").join();
        alice.runCommand("/HELP").join();
        t.succeed();
    }
}
