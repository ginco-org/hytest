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

class SimulatedPlayer private constructor(
    val world: World,
    private val playerRef: PlayerRef,
    val uuid: UUID,
    val name: String,
    private val channel: FakeChannelConnection,
) {
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

    fun disconnect() {
        Universe.get().removePlayer(playerRef)
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
        fun spawn(world: World, name: String): CompletableFuture<SimulatedPlayer> {
            val uuid = UUID.randomUUID()
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
                        IllegalStateException("simulated player rejected by Universe.addPlayer (uuid=$uuid, name=$name)"))
                else
                    CompletableFuture.completedFuture(SimulatedPlayer(world, playerRef, uuid, name, channel))
            }.whenComplete { _, _ ->
                readyCompleter.cancel(false)
                routing?.unregister()
            }
        }
    }
}
