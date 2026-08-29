package gg.ginco.hytest.runtime.api

import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.universe.world.World
import gg.ginco.hytest.runtime.player.SimulatedPlayer
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

    fun spawnSimulatedPlayer(name: String): SimulatedPlayer {
        return try {
            SimulatedPlayer.spawn(world, name).orTimeout(30, TimeUnit.SECONDS).join()
        } catch (e: CompletionException) {
            val cause = e.cause ?: e
            throw if (cause is RuntimeException) cause else e
        }
    }

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
            val cause = e.cause ?: e
            throw if (cause is RuntimeException) cause else e
        }
    }

    fun failure(): Throwable? = failure

    fun doneFuture(): CompletableFuture<Void> = done

    private fun finishOk() = synchronized(this) {
        if (!finished) { finished = true; done.complete(null) }
    }

    private fun finishFail(throwable: Throwable) = synchronized(this) {
        if (!finished) { finished = true; this.failure = throwable; done.completeExceptionally(throwable) }
    }
}
