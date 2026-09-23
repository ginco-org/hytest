package gg.ginco.hytest.runtime.player

import com.hypixel.hytale.event.EventRegistration
import com.hypixel.hytale.math.vector.Transform
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.auth.PlayerAuthentication
import com.hypixel.hytale.server.core.command.system.CommandManager
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent
import com.hypixel.hytale.server.core.io.PacketHandler
import com.hypixel.hytale.server.core.io.ProtocolVersion
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import gg.ginco.hytest.runtime.HytestPlugin
import java.util.Locale
import java.util.UUID
import java.util.concurrent.*

/**
 * A simulated player: a real server-side identity (uuid, name, `PlayerRef`)
 * driving a fake network connection, so plugins observe genuine joins,
 * disconnects, and commands without a game client.
 *
 * Handles are per-connection and **become stale after [disconnect]**: the
 * underlying `PlayerRef` and channel are gone. To bring the same identity
 * back, call [reconnect] on the stale handle and use the fresh handle it
 * completes with.
 */
class SimulatedPlayer private constructor(
    val world: World,
    private val playerRef: PlayerRef,
    val uuid: UUID,
    val name: String,
    private val channel: FakeChannelConnection,
) {
    /** Set by [disconnect] so [reconnect] can tell "disconnected, cleanup pending" from "never disconnected". */
    @Volatile private var disconnectRequested = false

    fun ref(): PlayerRef = playerRef

    fun transform(): Transform = playerRef.transform

    /**
     * True while the player is still registered in the universe.
     *
     * Note: [PlayerRef.isValid][com.hypixel.hytale.server.core.universe.PlayerRef.isValid]
     * is not usable for this since 0.6.x — `removeFromStore` keeps the removed
     * `Holder` set, so it never flips to false after a disconnect.
     */
    fun isValid(): Boolean = Universe.get().players.contains(playerRef)

    fun teleport(x: Double, y: Double, z: Double): CompletableFuture<Void> {
        val ref = playerRef.reference
            ?: return CompletableFuture.failedFuture(IllegalStateException("player is not in a world"))
        return CompletableFuture.runAsync({
            val tc = ref.store.getComponent(ref, TransformComponent.getComponentType())
            tc?.teleportPosition(Transform(x, y, z).position)
        }, world)
    }

    fun teleport(transform: Transform): CompletableFuture<Void> {
        val pos = transform.position
        return teleport(pos.x, pos.y, pos.z)
    }

    fun chat(message: String): CompletableFuture<Void> {
        val targets = ArrayList(Universe.get().players)
        val event = PlayerChatEvent(playerRef, targets, message)
        val dispatcher = HytaleServer.get().eventBus.dispatchForAsync(PlayerChatEvent::class.java)
        return dispatcher.dispatch(event).thenAccept { chatEvent ->
            if (!chatEvent.isCancelled) {
                val formatted = chatEvent.formatter.format(chatEvent.sender, chatEvent.content)
                for (target in chatEvent.targets) target.sendMessage(formatted)
            }
        }
    }

    fun sendMessage(message: String) {
        playerRef.sendMessage(Message.raw(message))
    }

    /**
     * Plain text of every message packet the server has written to this
     * player — chat, command responses, system messages — oldest first.
     *
     * Capture is synchronous with command execution: after `runCommand(...).join()`
     * the response text is already here. Bounded to the most recent
     * [FakeChannelConnection.MAX_CAPTURED_MESSAGES] entries.
     */
    fun receivedMessages(): List<String> = channel.receivedMessages()

    /** [receivedMessages], clearing the capture — use between commands to keep assertions scoped. */
    fun drainReceivedMessages(): List<String> = channel.drainReceivedMessages()

    /** True if any captured message contains [fragment]. */
    fun sawMessage(fragment: String): Boolean = receivedMessages().any { fragment in it }

    /**
     * Removes the player from the universe, firing the server's disconnect
     * pipeline (`PlayerDisconnectEvent`, entity-store removal).
     *
     * Removal is asynchronous server-side: the uuid stays registered until
     * the world store finishes cleanup (bounded at ~5 seconds by the server),
     * after which [isValid] reports false. This handle is stale from this
     * call on — keep testing the identity via [reconnect].
     */
    fun disconnect() {
        disconnectRequested = true
        Universe.get().removePlayer(playerRef)
    }

    /**
     * Rejoins [world] with this player's identity — same [uuid], same [name] —
     * on a fresh fake connection, completing with a NEW [SimulatedPlayer]
     * handle. `PlayerConnectEvent` fires again and the player is routed into
     * the same world, exactly like a first join.
     *
     * Call [disconnect] first. Reconnecting while still connected fails the
     * returned future with [IllegalStateException] — there is no implicit
     * disconnect, because a live session at reconnect time is almost always a
     * test bug and silently dropping it would hide that.
     *
     * [disconnect] removal settles asynchronously (see its KDoc), and the
     * universe rejects an `addPlayer` for a uuid that is still registered
     * (completing with `null`, not an exception). This method therefore waits
     * out the old registration — up to [IDENTITY_SETTLE_TIMEOUT_SECONDS] —
     * before rejoining, so `disconnect(); reconnect().join()` needs no manual
     * polling in between.
     *
     * @return a future completing with the fresh handle, or failing with
     *   [IllegalStateException] if the player is still connected or the old
     *   registration never settled
     */
    fun reconnect(): CompletableFuture<SimulatedPlayer> {
        if (isValid() && !disconnectRequested) {
            return CompletableFuture.failedFuture(IllegalStateException(
                "cannot reconnect '$name' ($uuid): player is still connected — call disconnect() first"))
        }
        return awaitIdentitySettled(uuid, name)
            .thenCompose { connect(world, name, uuid) }
    }

    /**
     * Execute a command as the player (leading `/` optional).
     *
     * Fails with [IllegalStateException] when the root command does not
     * resolve — the server would otherwise send the player a "not found"
     * message and complete normally, silently turning a typo (`minigame creat`)
     * into a passing test that asserted nothing. Outcomes the command itself
     * produces (permission denial, rejected arguments) still complete
     * normally; assert on them via [receivedMessages].
     */
    fun runCommand(command: String): CompletableFuture<Void> {
        val stripped = if (command.startsWith("/")) command.substring(1) else command
        // Root extraction mirrors CommandManager's own dispatch (text before
        // the first space, lowercased) so we reject exactly what it would
        // silently no-op on — general whitespace trimming would diverge.
        val root = stripped.substringBefore(' ').lowercase(Locale.ROOT)
        if (CommandManager.get().resolveCommand(root) == null) {
            return CompletableFuture.failedFuture(
                IllegalStateException("no command named '$root' (input: '$command')"))
        }
        return CommandManager.get().handleCommand(playerRef, stripped)
            .exceptionally { throw CompletionException("command failed: $command", it) }
    }

    companion object {
        /** Budget for a disconnected identity's async removal to settle (server bounds it at ~5s). */
        internal const val IDENTITY_SETTLE_TIMEOUT_SECONDS = 10L

        /**
         * Spawns a simulated player with a fresh random identity into
         * [world].
         */
        fun spawn(world: World, name: String): CompletableFuture<SimulatedPlayer> =
            spawn(world, name, UUID.randomUUID())

        /**
         * Spawns a simulated player with a fixed [uuid] — the identity a
         * later `reconnect()` restores. Fails with
         * [IllegalStateException] if the universe rejects the uuid because it
         * is already registered (still connected / mid-removal).
         */
        fun spawn(world: World, name: String, uuid: UUID): CompletableFuture<SimulatedPlayer> =
            connect(world, name, uuid)

        /**
         * Shared join flow behind [spawn] and [reconnect]: registers a
         * temporary `PlayerConnectEvent` routing for the uuid, joins via
         * `Universe.addPlayer`, and kicks `clientReadyForChunksFuture` so the
         * server starts streaming to the fake channel.
         *
         * Duplicate-uuid note (verified against 0.6.8): `Universe.addPlayer`
         * does NOT fail for a uuid that is already registered — it logs
         * "already joining from another connection", disconnects the new
         * channel, and completes with `null`. We translate that `null` into
         * an explicit failure so callers see a cause, not a silent null.
         */
        private fun connect(world: World, name: String, uuid: UUID): CompletableFuture<SimulatedPlayer> {
            val channel = FakeChannelConnection()

            val routing: EventRegistration<*, *>? = HytestPlugin.EVENTS!!.register(PlayerConnectEvent::class.java) { event ->
                if (uuid == event.playerRef.uuid) event.world = world
            }

            val auth = PlayerAuthentication(uuid, name)
            val join = Universe.get().addPlayer(channel, "en", ProtocolVersion(0), auth, 6)

            val readyCompleter = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate({
                channel.capturedHandler?.let { h ->
                    h.clientReadyForChunksFuture?.complete(null)
                }
            }, 0L, 20L, TimeUnit.MILLISECONDS)

            return join.thenCompose { playerRef ->
                if (playerRef == null)
                    CompletableFuture.failedFuture<SimulatedPlayer>(
                        IllegalStateException("simulated player rejected by Universe.addPlayer (uuid=$uuid, name=$name)" +
                            " — the uuid is still registered; still connected, or removal not settled yet?"))
                else
                    CompletableFuture.completedFuture(SimulatedPlayer(world, playerRef, uuid, name, channel))
            }.whenComplete { _, _ ->
                readyCompleter.cancel(false)
                routing?.unregister()
            }
        }

        /**
         * Waits until the universe no longer has ANY player registered under
         * [uuid]. After `removePlayer` the registration lingers until the
         * world store finishes cleanup (server-bounded at ~5s), and
         * `addPlayer` rejects a duplicate uuid during that window — so a
         * reconnect must wait for the slot to free up.
         */
        private fun awaitIdentitySettled(uuid: UUID, name: String): CompletableFuture<Void> {
            val settled = CompletableFuture<Void>()
            val poll = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate({
                if (Universe.get().getPlayer(uuid) == null) settled.complete(null)
            }, 0L, 20L, TimeUnit.MILLISECONDS)
            settled.whenComplete { _, _ -> poll.cancel(false) }
            return settled
                .orTimeout(IDENTITY_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally { t ->
                    throw IllegalStateException(
                        "previous session of '$name' ($uuid) was not fully removed from the universe " +
                            "within ${IDENTITY_SETTLE_TIMEOUT_SECONDS}s; cannot reconnect", t)
                }
        }
    }
}
