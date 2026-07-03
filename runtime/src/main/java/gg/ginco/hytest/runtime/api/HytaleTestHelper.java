package gg.ginco.hytest.runtime.api;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.world.World;
import gg.ginco.hytest.runtime.player.SimulatedPlayer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Per-test context handed to every {@link HytaleTest} method. Exposes the
 * ephemeral {@link World} the test runs in plus assertion/action helpers.
 *
 * <p>Terminal state is reached exactly once via {@link #succeed()} or
 * {@link #fail(String)} (or by an assertion throwing). Assertions throw
 * {@link AssertionError} rather than finalizing, so they can be used inside
 * {@link #succeedWhen(Runnable)} conditions for poll-until-true semantics.
 */
public final class HytaleTestHelper {
    private static final long POLL_INTERVAL_MS = 50L;

    private final World world;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private volatile boolean finished;
    private volatile Throwable failure;

    public HytaleTestHelper(World world) {
        this.world = world;
    }

    public World world() {
        return world;
    }

    public long tick() {
        return world.getTick();
    }

    // ---- simulated players ----------------------------------------------------

    /** Spawns a simulated player named {@code "SimulatedPlayer"} into the test world. */
    public SimulatedPlayer spawnSimulatedPlayer() {
        return spawnSimulatedPlayer("SimulatedPlayer");
    }

    /** Spawns a simulated player with the given display name into the test world. */
    public SimulatedPlayer spawnSimulatedPlayer(String name) {
        try {
            return SimulatedPlayer.spawn(world, name).orTimeout(30, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : e;
        }
    }

    /** Asserts the test world currently holds exactly {@code expected} players. */
    public void assertPlayerCount(int expected) {
        int actual = world.getPlayers().size();
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " player(s) in world, got " + actual);
        }
    }

    /**
     * Runs {@code action} on the world thread and blocks until it completes. Use this for
     * mutations that must happen on the world's own thread; reads are generally safe from
     * the test thread.
     */
    public void runOnWorld(Runnable action) {
        try {
            CompletableFuture.runAsync(action, world).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    // ---- terminal transitions -------------------------------------------------

    public void succeed() {
        finishOk();
    }

    public void fail(String message) {
        finishFail(new AssertionError(message));
    }

    public void fail(Throwable throwable) {
        finishFail(throwable);
    }

    /**
     * Polls {@code condition} until it completes without throwing, then succeeds.
     * {@link AssertionError}s thrown by the condition are treated as "not yet"
     * and retried; any other {@link Throwable} fails the test immediately.
     */
    public void succeedWhen(Runnable condition) {
        ScheduledFuture<?> poll = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                condition.run();
                finishOk();
            } catch (AssertionError ignored) {
                // keep polling until the test timeout fires
            } catch (Throwable t) {
                finishFail(t);
            }
        }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        done.whenComplete((v, e) -> poll.cancel(false));
    }

    /** Runs {@code action} after approximately {@code ticks} game ticks (1 tick ~= 50ms). */
    public void runAfterDelay(long ticks, Runnable action) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                finishFail(t);
            }
        }, Math.max(0, ticks) * 50L, TimeUnit.MILLISECONDS);
    }

    // ---- assertions (throw, do not finalize) ----------------------------------

    public void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    public void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    public void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    // ---- internal -------------------------------------------------------------

    public Throwable failure() {
        return failure;
    }

    public CompletableFuture<Void> doneFuture() {
        return done;
    }

    private synchronized void finishOk() {
        if (!finished) {
            finished = true;
            done.complete(null);
        }
    }

    private synchronized void finishFail(Throwable throwable) {
        if (!finished) {
            finished = true;
            this.failure = throwable;
            done.completeExceptionally(throwable);
        }
    }
}
