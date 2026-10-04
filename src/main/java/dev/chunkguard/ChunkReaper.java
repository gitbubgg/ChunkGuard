package dev.chunkguard;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flushes briefly visited chunks (rtp landings, fly-bys) ahead of the normal unload timer.
 *
 * ASYNC SAVING: Chunk#unload(true) hands the save to the server's chunk system, which serializes and writes on its
 * dedicated IO threads. The region thread only flips the chunk state, so disk IOPS never block ticking.
 * FOLIA SAFETY: the sweep runs on the GlobalRegionScheduler and touches no world state. Each chunk is then handled
 * by RegionScheduler#run at that chunk's coordinates, which executes on the thread that owns it.
 */
final class ChunkReaper {
    private record Key(UUID world, int x, int z) {}

    private final ChunkGuardPlugin plugin;
    private volatile Settings s;
    private final Map<Key, Long> tracked = new ConcurrentHashMap<>();

    ChunkReaper(ChunkGuardPlugin plugin, Settings s) { this.plugin = plugin; this.s = s; }
    void reload(Settings s) { this.s = s; }

    final java.util.concurrent.atomic.LongAdder unloaded = new java.util.concurrent.atomic.LongAdder();

    int trackedCount() { return tracked.size(); }

    /** Value stored = earliest time the chunk may be flushed. */
    void track(Chunk c) { tracked.putIfAbsent(new Key(c.getWorld().getUID(), c.getX(), c.getZ()), System.currentTimeMillis() + s.minDwellMs()); }

    /** Trail chunk behind a fast flyer: short delay. Keeps the earlier deadline, capped so the map cannot grow unbounded. */
    void trackTrail(UUID world, int x, int z) {
        if (tracked.size() >= s.maxTracked()) return;
        tracked.merge(new Key(world, x, z), System.currentTimeMillis() + s.trailDwellMs(), Math::min);
    }
    void untrack(Chunk c) { tracked.remove(new Key(c.getWorld().getUID(), c.getX(), c.getZ())); }

    void start() {
        long period = s.sweepTicks();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> sweep(), period, period);
    }

    private void sweep() {
        Settings cfg = s;
        if (!cfg.reaperOn()) return;
        long now = System.currentTimeMillis();
        int budget = cfg.maxPerSweep();                       // bounded work per sweep keeps the global thread idle
        for (Iterator<Map.Entry<Key, Long>> it = tracked.entrySet().iterator(); it.hasNext() && budget > 0; ) {
            Map.Entry<Key, Long> en = it.next();
            if (now < en.getValue()) continue;   // not yet eligible
            budget--;
            Key k = en.getKey();
            it.remove();
            World w = Bukkit.getWorld(k.world());
            if (w == null) continue;
            Bukkit.getRegionScheduler().run(plugin, w, k.x(), k.z(), task -> tryUnload(w, k, cfg));   // owner thread
        }
    }

    private void tryUnload(World w, Key k, Settings cfg) {
        if (!w.isChunkLoaded(k.x(), k.z())) return;
        Chunk c = w.getChunkAt(k.x(), k.z());                 // already loaded, so this does not generate or block
        if (c.isForceLoaded() || !c.getPlayersSeeingChunk().isEmpty()) { track(c); return; }   // still in use, re-check later
        if (c.getInhabitedTime() > cfg.maxInhabited()) return; // genuinely played in, leave to vanilla unload logic
        if (c.unload(true)) unloaded.increment();                                        // save is performed by the async chunk IO system
    }
}
