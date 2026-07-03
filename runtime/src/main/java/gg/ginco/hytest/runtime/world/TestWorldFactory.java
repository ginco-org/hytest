package gg.ginco.hytest.runtime.world;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.provider.EmptyChunkStorageProvider;
import com.hypixel.hytale.server.core.universe.world.storage.resources.EmptyResourceStorageProvider;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.VoidWorldGenProvider;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Creates and tears down ephemeral, in-memory test worlds using the server's
 * shipped test storage/generation providers — no disk I/O, no terrain.
 */
public final class TestWorldFactory {

    private TestWorldFactory() {
    }

    public static CompletableFuture<World> createVoid() {
        try {
            WorldConfig config = new WorldConfig();
            config.setWorldGenProvider(new VoidWorldGenProvider());
            config.setChunkStorageProvider(EmptyChunkStorageProvider.INSTANCE);
            config.setResourceStorageProvider(EmptyResourceStorageProvider.INSTANCE);
            config.setSavingConfig(false);
            config.setDeleteOnRemove(false);

            var savePath = Files.createTempDirectory("hytest-world");
            return Universe.get()
                    .makeWorld("hytest-" + UUID.randomUUID(), savePath, config)
                    .orTimeout(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Unconditionally removes a world (bypasses {@code RemoveWorldEvent} cancellation). */
    public static void dispose(World world) {
        try {
            Universe.get().removeWorldExceptionally(world.getName(), Map.of());
        } catch (Throwable ignored) {
            // best-effort during teardown
        }
    }
}
