package gg.ginco.hytest.runtime.api

import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.universe.world.World
import gg.ginco.hytest.runtime.HytestPlugin
import gg.ginco.hytest.runtime.player.SimulatedPlayer
import java.util.UUID
import java.util.concurrent.*

class HytaleTestHelper(val world: World) {

    private val done = CompletableFuture<Void>()
    @Volatile private var finished = false
    @Volatile private var failure: Throwable? = null

    fun tick(): Long = world.tick

    fun succeed() { finishOk() }
    fun fail(message: String) { finishFail(AssertionError(message)) }
    fun fail(throwable: Throwable) { finishFail(throwable) }

    fun succeedWhen(condition: Runnable) {
        val poll = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate({
            try { condition.run(); finishOk() }
            catch (_: AssertionError) { /* keep polling */ }
            catch (t: Throwable) { finishFail(t) }
        }, 0L, 50L, TimeUnit.MILLISECONDS)
        done.whenComplete { _, _ -> poll.cancel(false) }
    }

    fun runAfterDelay(ticks: Long, action: Runnable) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule({
            try { action.run() }
            catch (t: Throwable) { finishFail(t) }
        }, maxOf(0L, ticks) * 50L, TimeUnit.MILLISECONDS)
    }

    /**
     * Blocks the test body until [condition] stops throwing [AssertionError],
     * using the default [ASSERT_EVENTUALLY_TIMEOUT_SECONDS] budget.
     */
    fun assertEventually(condition: () -> Unit) =
        assertEventually(ASSERT_EVENTUALLY_TIMEOUT_SECONDS, condition)

    /**
     * Blocks the test body, re-running [condition] roughly every 50ms until
     * it stops throwing [AssertionError], then returns so the test can keep
     * going.
     *
     * Use this when further code must wait for asynchronous server state —
     * "wait until this holds, THEN keep testing". Use [succeedWhen] for the
     * test's terminal success condition instead: it returns immediately and
     * completes the test in the background as soon as the condition holds,
     * which means assertions placed after it race the condition (a recurring
     * trap). The two compose legally: `assertEventually` never touches the
     * test's done future, so a later `succeedWhen` (or `succeed()`) still
     * decides the verdict.
     *
     * This helper is allowed to block: test bodies run off the world thread.
     * The throwing contract matches [succeedWhen]: an [AssertionError] from
     * [condition] means "not yet" (keep polling); any other throwable
     * propagates immediately and fails the test.
     *
     * When the budget expires, the LAST [AssertionError] is rethrown wrapped
     * as `"condition not met within Ns: <message>"` with the original as the
     * cause.
     *
     * @param timeoutSeconds wall-clock budget; the condition always runs at
     *   least once, so `0` means "assert now, never poll"
     * @param condition throwing assertions that describe the awaited state
     */
    fun assertEventually(timeoutSeconds: Long, condition: () -> Unit) {
        require(timeoutSeconds >= 0) { "timeoutSeconds must be >= 0, got $timeoutSeconds" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val lastError = try {
                condition()
                return
            } catch (e: AssertionError) {
                e
            }
            if (System.nanoTime() >= deadline) {
                throw AssertionError(
                    "condition not met within ${timeoutSeconds}s: ${lastError.message ?: lastError}",
                    lastError)
            }
            Thread.sleep(ASSERT_EVENTUALLY_POLL_MILLIS)
        }
    }

    // Java bridging. `() -> Unit` is the natural Kotlin parameter, but Java
    // lambdas returning void cannot target it (void is not Unit), so Java
    // tests get Runnable overloads — same shape as succeedWhen. Kotlin
    // callers resolve to the `() -> Unit` forms above.

    /** [assertEventually] for Java callers: blocks until [condition] stops throwing. */
    fun assertEventually(condition: Runnable) =
        assertEventually(ASSERT_EVENTUALLY_TIMEOUT_SECONDS) { condition.run() }

    /** [assertEventually] for Java callers with an explicit budget in seconds. */
    fun assertEventually(timeoutSeconds: Long, condition: Runnable) =
        assertEventually(timeoutSeconds) { condition.run() }

    fun assertTrue(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    fun assertFalse(condition: Boolean, message: String) {
        if (condition) throw AssertionError(message)
    }

    fun assertEquals(expected: Any?, actual: Any?, message: String) {
        if (expected != actual) throw AssertionError("$message (expected $expected, got $actual)")
    }

    fun assertNotNull(value: Any?, message: String) {
        if (value == null) throw AssertionError(message)
    }

    fun spawnSimulatedPlayer(): SimulatedPlayer = spawnSimulatedPlayer("SimulatedPlayer")

    /**
     * Spawns a simulated player with a fixed identity, for disconnect/
     * reconnect flows: `spawnSimulatedPlayer("Alice", uuid)` produces exactly
     * the same identity a later [SimulatedPlayer.reconnect] restores.
     */
    fun spawnSimulatedPlayer(name: String, uuid: UUID): SimulatedPlayer {
        return try {
            SimulatedPlayer.spawn(world, name, uuid).orTimeout(30, TimeUnit.SECONDS).join()
        } catch (e: CompletionException) {
            val cause = e.cause ?: e
            throw if (cause is RuntimeException) cause else e
        }
    }

    fun spawnSimulatedPlayer(name: String): SimulatedPlayer =
        spawnSimulatedPlayer(name, UUID.randomUUID())

    fun assertPlayerCount(expected: Int) {
        @Suppress("DEPRECATION")
        val actual = world.players.size
        if (actual != expected) throw AssertionError("expected $expected player(s) in world, got $actual")
    }

    fun assertPlayerValid(player: SimulatedPlayer, message: String = "player should be valid") {
        if (!player.isValid()) throw AssertionError(message)
    }

    fun assertPlayerNear(
        player: SimulatedPlayer, x: Double, y: Double, z: Double,
        tolerance: Double, message: String = "player position"
    ) {
        val pos = player.transform().position
        val dx = pos.x - x
        val dy = pos.y - y
        val dz = pos.z - z
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq > tolerance * tolerance) {
            throw AssertionError("$message: expected near ($x, $y, $z) within $tolerance, " +
                "got (${pos.x}, ${pos.y}, ${pos.z})")
        }
    }

    fun waitForTicks(ticks: Long) {
        Thread.sleep(maxOf(0L, ticks) * 50L)
    }

    fun runOnWorld(action: Runnable) {
        try { CompletableFuture.runAsync(action, world).join() }
        catch (e: CompletionException) {
            // Always rethrow the CAUSE. The old code rethrew the raw
            // CompletionException for non-RuntimeException causes, which broke
            // succeedWhen: it catches AssertionError to keep polling, but the
            // wrapper is a CompletionException, so the first failed poll failed
            // the whole test instead of retrying.
            throw (e.cause ?: e)
        }
    }

    /**
     * Like [runOnWorld] but returns the action's value: read store/entity state
     * on the world thread and hand it back to the (off-thread) test body. The
     * result should be an immutable snapshot — entity objects can only be
     * touched again on the world thread.
     */
    fun <T> runOnWorldReturning(action: () -> T): T {
        return try { CompletableFuture.supplyAsync(action, world).join() }
        catch (e: CompletionException) { throw (e.cause ?: e) }
    }

    fun failure(): Throwable? = failure

    fun doneFuture(): CompletableFuture<Void> = done

    /**
     * Registers a listener for [eventClass] on the server's event bus and
     * returns the list it appends fired events to. The registration is
     * unregistered automatically when the test finishes — tests assert on
     * the returned list (thread-safe; events arrive on the world thread).
     *
     * Works for any event type the server or plugins dispatch, which makes
     * it the harness's event-capture primitive for consumers that expose
     * their own events.
     */
    fun <E : Any> captureEvents(eventClass: Class<E>): MutableList<E> {
        val list = java.util.concurrent.CopyOnWriteArrayList<E>()
        onEvent(eventClass) { event -> list.add(event) }
        return list
    }

    /** [captureEvents] for Kotlin callers using a reified type. */
    inline fun <reified E : Any> captureEvents(): MutableList<E> = captureEvents(E::class.java)

    /**
     * Registers [handler] as a listener for [eventClass] (any event type the
     * server or plugins dispatch) and auto-unregisters it when the test
     * finishes. The handler runs on the thread that dispatched the event —
     * for plugin events that is usually the world thread.
     */
    fun <E : Any> onEvent(eventClass: Class<E>, handler: (E) -> Unit) {
        val registration = HytestPlugin.EVENTS!!
            .register(eventClass) { event -> handler(event) }!!
        done.whenComplete { _, _ -> runCatching { registration.unregister() } }
    }

    private companion object {
        /** Default budget for [assertEventually], in seconds. */
        const val ASSERT_EVENTUALLY_TIMEOUT_SECONDS = 10L

        /** How often [assertEventually] re-runs its condition. */
        const val ASSERT_EVENTUALLY_POLL_MILLIS = 50L
    }

    private fun finishOk() = synchronized(this) {
        if (!finished) { finished = true; done.complete(null) }
    }

    private fun finishFail(throwable: Throwable) = synchronized(this) {
        if (!finished) { finished = true; this.failure = throwable; done.completeExceptionally(throwable) }
    }
}
