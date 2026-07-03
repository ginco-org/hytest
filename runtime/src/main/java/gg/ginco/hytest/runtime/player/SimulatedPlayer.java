package gg.ginco.hytest.runtime.player;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.auth.PlayerAuthentication;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import gg.ginco.hytest.runtime.HytestPlugin;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * A fully server-side player with no real network client, injected via a
 * {@link FakeChannelConnection}. The join pipeline runs for real —
 * {@code PlayerConnectEvent} and {@code AddPlayerToWorldEvent} fire, the player
 * exists in the {@link Universe} and the target {@link World}, and commands can
 * be executed as the player.
 *
 * <p>Construction is entirely public API: a one-shot {@link PlayerConnectEvent}
 * listener routes the player into the requested world, {@link Universe#addPlayer}
 * builds the {@link PlayerRef}, and a short poller completes the client-ready
 * gate that {@code World.addPlayer} waits on.
 */
public final class SimulatedPlayer {

    private final PlayerRef playerRef;
    private final UUID uuid;
    private final String name;

    private SimulatedPlayer(PlayerRef playerRef, UUID uuid, String name) {
        this.playerRef = playerRef;
        this.uuid = uuid;
        this.name = name;
    }

    /**
     * Spawns a simulated player with a random UUID into {@code world} and resolves
     * once it is fully joined.
     */
    public static CompletableFuture<SimulatedPlayer> spawn(World world, String name) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(name, "name");
        UUID uuid = UUID.randomUUID();
        FakeChannelConnection channel = new FakeChannelConnection();

        EventRegistration<?, ?> routing = HytestPlugin.EVENTS.register(PlayerConnectEvent.class, event -> {
            if (uuid.equals(event.getPlayerRef().getUuid())) {
                event.setWorld(world);
            }
        });

        var auth = new PlayerAuthentication(uuid, name);
        CompletableFuture<PlayerRef> join = Universe.get()
                .addPlayer(channel, "en", new ProtocolVersion(0), auth, 6);

        var readyCompleter = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            PacketHandler handler = channel.capturedHandler();
            if (handler != null) {
                CompletableFuture<Void> ready = handler.getClientReadyForChunksFuture();
                if (ready != null) {
                    ready.complete(null);
                }
            }
        }, 0L, 20L, TimeUnit.MILLISECONDS);

        return join.thenCompose(playerRef -> playerRef == null
                        ? CompletableFuture.failedFuture(new IllegalStateException(
                                "simulated player rejected by Universe.addPlayer (uuid=" + uuid + ", name=" + name + ")"))
                        : CompletableFuture.completedFuture(new SimulatedPlayer(playerRef, uuid, name)))
                .whenComplete((player, error) -> {
                    readyCompleter.cancel(false);
                    routing.unregister();
                });
    }

    public PlayerRef ref() {
        return playerRef;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /** The player's current transform (position/rotation) in the world. */
    public Transform transform() {
        return playerRef.getTransform();
    }

    /**
     * Runs a command as this player. The leading {@code /} is optional. The player
     * is a {@link com.hypixel.hytale.server.core.command.system.CommandSender}, so
     * permission checks run normally.
     */
    public CompletableFuture<Void> runCommand(String command) {
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        return CommandManager.get().handleCommand(playerRef, stripped)
                .exceptionally(throwable -> {
                    throw new CompletionException("command failed: " + command, throwable);
                });
    }
}
