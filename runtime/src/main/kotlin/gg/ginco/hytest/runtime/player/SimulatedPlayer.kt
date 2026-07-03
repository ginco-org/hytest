package gg.ginco.hytest.runtime.player

import com.hypixel.hytale.event.EventRegistration
import com.hypixel.hytale.math.vector.Transform
import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.auth.PlayerAuthentication
import com.hypixel.hytale.server.core.command.system.CommandManager
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent
import com.hypixel.hytale.server.core.io.PacketHandler
import com.hypixel.hytale.server.core.io.ProtocolVersion
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import gg.ginco.hytest.runtime.HytestPlugin
import java.util.UUID
import java.util.concurrent.*

class SimulatedPlayer private constructor(
    private val playerRef: PlayerRef,
    val uuid: UUID,
    val name: String
) {
    fun ref(): PlayerRef = playerRef
    fun transform(): Transform = playerRef.transform

    fun runCommand(command: String): CompletableFuture<Void> {
        val stripped = if (command.startsWith("/")) command.substring(1) else command
        return CommandManager.get().handleCommand(playerRef, stripped)
            .exceptionally { throw CompletionException("command failed: $command", it) }
    }

    companion object {
        fun spawn(world: World, name: String): CompletableFuture<SimulatedPlayer> {
            val uuid = UUID.randomUUID()
            val channel = FakeChannelConnection()

            val routing = HytestPlugin.EVENTS!!.register(PlayerConnectEvent::class.java) { event ->
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
                    CompletableFuture.completedFuture(SimulatedPlayer(playerRef, uuid, name))
            }.whenComplete { _, _ ->
                readyCompleter.cancel(false)
                routing?.unregister()
            }
        }
    }
}
