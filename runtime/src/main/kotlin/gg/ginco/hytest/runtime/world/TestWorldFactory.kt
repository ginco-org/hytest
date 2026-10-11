package gg.ginco.hytest.runtime.world

import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.WorldConfig
import com.hypixel.hytale.server.core.universe.world.storage.provider.EmptyChunkStorageProvider
import com.hypixel.hytale.server.core.universe.world.storage.resources.EmptyResourceStorageProvider
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.DisabledWorldMapProvider
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
                // Throwaway worlds: everything a test needs lives in memory
                // (EmptyChunkStorage + chunk-store resources). With saving on,
                // world removal runs ChunkSavingSystems.saveChunksInWorld and
                // blocks on its in-flight semaphore — measured ~30s per world
                // on 2-core CI runners (68 worlds ≈ half the suite's wall
                // time). The WorldRemoved hook checks canSaveChunks() and
                // skips the save entirely when false; the Ticking autosave
                // hook gates on the same flag, so ticks do less work too.
                // (Kotlin cannot assign the private field directly — the
                // Java-bean setter pairs with it.)
                setCanSaveChunks(false)
                // Simulated players have no client, so nothing ever reads the
                // world map — but the default map provider drives
                // "WorldMapTracker" chunk scans at world init and on every
                // join. That load backlog is what world teardown then drains:
                // removing a world with chunk loads still in flight blocks in
                // the server's "Waiting for loading chunks..." phase against a
                // fixed ~5s cap (measured on a 2-core CI suite: 37 of 77 world
                // teardowns + several in-body instance-world stops ≈ 3.5 min
                // of pure waiting). DisabledWorldMapProvider removes those
                // requests at the source.
                worldMapProvider = DisabledWorldMapProvider()
                // Void test worlds read no terrain, and the default pregenerate
                // region is ±512 blocks (9×9 = 81 chunks queued per world).
                // WorldPregenerateSystem skips itself entirely when the region
                // is null — that leaves teardown's "Waiting for loading
                // chunks..." queue empty instead of waiting out its 5s budget.
                // Deliberately NOT touching chunkConfig.keepLoadedRegion: an
                // empty keep-loaded region breaks player placement (join then
                // stalls forever; bisected locally), so the server's default
                // stays in place.
                getChunkConfig().setPregenerateRegion(null)
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
