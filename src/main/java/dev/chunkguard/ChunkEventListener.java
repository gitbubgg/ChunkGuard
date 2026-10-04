package dev.chunkguard;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Chunk events fire on the region thread that owns the chunk, after the chunk system has already done the heavy
 * work on its async worker/IO pools. These handlers only do O(1) bookkeeping so they never add main-thread cost.
 * NOTE: ChunkLoadEvent is not cancellable. Generation cannot be vetoed from a plugin, only throttled upstream
 * (see LoadMonitor) by reducing how many chunks players request.
 */
final class ChunkEventListener implements Listener {
    private final LoadMonitor monitor;
    private final ChunkReaper reaper;

    ChunkEventListener(LoadMonitor monitor, ChunkReaper reaper) { this.monitor = monitor; this.reaper = reaper; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk()) return;                    // only freshly generated chunks matter for both systems
        Chunk c = e.getChunk();
        monitor.recordNewChunk(new Location(c.getWorld(), c.getX() << 4, 64, c.getZ() << 4));
        reaper.track(c);                                // candidate for early flush if nobody lingers
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent e) { reaper.untrack(e.getChunk()); }
}
