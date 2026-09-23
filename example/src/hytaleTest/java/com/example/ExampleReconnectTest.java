package com.example;

import gg.ginco.hytest.runtime.api.HytaleTest;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;
import gg.ginco.hytest.runtime.player.SimulatedPlayer;

import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Disconnect/rejoin flows: a player spawned with a fixed uuid can leave and
 * come back as the SAME identity on a fresh connection.
 */
public class ExampleReconnectTest {

    @HytaleTest(timeoutSeconds = 60)
    void playerReconnectsWithSameIdentity(HytaleTestHelper t) {
        UUID aliceId = UUID.randomUUID();
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice", aliceId);
        t.assertEquals(aliceId, alice.getUuid(), "spawned player should carry the requested uuid");
        t.assertPlayerCount(1);

        alice.disconnect();

        // Removal settles a tick later; assertEventually blocks until it did,
        // so the reconnect below cannot race the cleanup.
        t.assertEventually(() -> t.assertPlayerCount(0));

        SimulatedPlayer aliceAgain = alice.reconnect().join();
        t.assertPlayerCount(1);
        t.assertEquals(aliceId, aliceAgain.getUuid(), "reconnected player should keep the same uuid");
        t.assertEquals("Alice", aliceAgain.getName(), "reconnected player should keep the same name");

        // The fresh handle is fully functional.
        aliceAgain.runCommand("help").join();
        t.succeed();
    }

    @HytaleTest(timeoutSeconds = 60)
    void reconnectWaitsForAsynchronousRemovalByItself(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");
        t.assertPlayerCount(1);

        alice.disconnect();

        // No manual wait between the two calls: reconnect() itself waits out
        // the server's async disconnect cleanup before re-adding the uuid.
        SimulatedPlayer aliceAgain = alice.reconnect().join();

        t.assertPlayerCount(1);
        t.assertEquals(alice.getUuid(), aliceAgain.getUuid(), "reconnected player should keep the same uuid");
        aliceAgain.runCommand("help").join();
        t.succeed();
    }

    // Edge case: reconnecting a session that is still live fails the returned
    // future with a clear IllegalStateException instead of implicitly
    // dropping the connection (hytest decision: a live session at reconnect
    // time is a test bug — fail loudly, don't hide it).
    @HytaleTest(timeoutSeconds = 60)
    void reconnectWhileStillConnectedFailsFast(HytaleTestHelper t) {
        SimulatedPlayer alice = t.spawnSimulatedPlayer("Alice");

        try {
            alice.reconnect().join();
            t.fail("reconnect() while still connected should fail the future");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            t.assertTrue(cause instanceof IllegalStateException,
                    "cause should be IllegalStateException, got: " + cause);
            t.assertTrue(cause.getMessage().contains("still connected"),
                    "unexpected message: " + cause.getMessage());
        }

        // The rejected reconnect must not have disturbed the live session.
        t.assertPlayerValid(alice, "player should still be connected after a rejected reconnect");
        t.assertPlayerCount(1);
        t.succeed();
    }
}
