package gg.ginco.hytest.runtime.world

import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.WorldConfig
import com.hypixel.hytale.server.core.universe.world.storage.provider.EmptyChunkStorageProvider
import com.hypixel.hytale.server.core.universe.world.storage.resources.EmptyResourceStorageProvider
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.VoidWorldGenProvider
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object TestWorldFactory {

    fun createVoid(): CompletableFuture<World> {
        return try {
            val config = WorldConfig().apply {
                worldGenProvider = VoidWorldGenProvider()
                chunkStorageProvider = EmptyChunkStorageProvider.INSTANCE
                resourceStorageProvider = EmptyResourceStorageProvider.INSTANCE
                isSavingConfig = false
                isDeleteOnRemove = false
            }
            Universe.get()
                .makeWorld("hytest-${UUID.randomUUID()}", Files.createTempDirectory("hytest-world"), config)
                .orTimeout(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            CompletableFuture.failedFuture(e)
        }
    }

    fun dispose(world: World) {
        try { Universe.get().removeWorldExceptionally(world.name, emptyMap()) }
        catch (_: Throwable) { /* best-effort */ }
    }
}
