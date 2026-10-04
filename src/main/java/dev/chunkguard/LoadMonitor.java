package dev.chunkguard;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures load and applies the dynamic chunk intake throttle.
 *
 * WHY THIS AVOIDS MAIN THREAD OVERHEAD
 *  - Each player runs a 1-tick heartbeat on their own EntityScheduler, i.e. on the region thread that owns them.
 *    Nothing here ever runs on a shared thread or touches another region's data.
 *  - Region load is stored in a ConcurrentHashMap keyed by a coarse 32x32 chunk cell (same grid as Folia regions),
 *    updated with a single volatile write. No locks, no cross-region calls.
 *  - Chunk generation itself cannot be cancelled by events in Paper/Folia, so the throttle works by shrinking the
 *    send view distance of moving players. Fewer requested chunks means fewer generation tasks reaching the pool.
 */
final class LoadMonitor {
    private static final boolean FOLIA = detectFolia();

    private static final class Cell {
        volatile long lastSurfaceMs; volatile double emaIntervalMs = 50; volatile long stampMs; volatile int newChunks; volatile long windowStartMs;
    }

    private final ChunkGuardPlugin plugin;
    private volatile Settings s;
    private final Map<Long, Cell> cells = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private volatile double paperMspt = 0;

    LoadMonitor(ChunkGuardPlugin plugin, Settings s) { this.plugin = plugin; this.s = s; }

    void reload(Settings s) { this.s = s; }
    PlayerState state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new PlayerState()); }

    /** Global scheduler: cheap housekeeping only (no world access). */
    void start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            if (!FOLIA) paperMspt = Bukkit.getAverageTickTime();   // not supported on Folia, hence the per-region probe
            long now = System.currentTimeMillis();
            cells.entrySet().removeIf(e -> now - e.getValue().stampMs > 10_000);   // drop idle regions
        }, 20, 20);
    }

    /** Called from the join event. Runs on the player's own region thread every tick until they leave. */
    void attach(Player p) {
        PlayerState st = state(p);
        long[] lastNanos = {System.nanoTime()};
        p.getScheduler().runAtFixedRate(plugin, (ScheduledTask task) -> {
            long now = System.nanoTime();
            double interval = (now - lastNanos[0]) / 1_000_000.0;   // time between our own ticks == region tick interval
            lastNanos[0] = now;
            Location loc = p.getLocation();
            Cell cell = cells.computeIfAbsent(cellKey(loc), k -> new Cell());
            cell.emaIntervalMs = cell.emaIntervalMs * 0.9 + interval * 0.1;
            cell.stampMs = System.currentTimeMillis();

            if (++st.tick % 20 == 0) {                               // evaluate once a second, not every tick
                st.stationary = st.last != null && st.last.getWorld() == loc.getWorld() && st.last.distanceSquared(loc) < 1.0;
                st.last = loc;
                updateUnderground(p, loc, st);
                apply(p, st, level(cell));
            }
        }, () -> states.remove(p.getUniqueId()), 1, 1);              // 'retired' callback cleans up on quit
    }

    /** Called from ChunkLoadEvent (region thread) for freshly generated chunks. */
    void recordNewChunk(Location where) {
        Cell c = cells.computeIfAbsent(cellKey(where), k -> new Cell());
        long now = System.currentTimeMillis();
        if (now - c.windowStartMs > 1000) { c.windowStartMs = now; c.newChunks = 0; }
        c.newChunks++;
    }

    /**
     * Cheap once-per-second check on the owning region thread: the heightmap lookup reads the player's own (loaded)
     * chunk and getLightFromSky reads one block, so there is no IO and no cross-region access.
     * Requires several consecutive hits to enter, but exits immediately so surface travel is never throttled.
     */
    private void updateUnderground(Player p, Location loc, PlayerState st) {
        Settings cfg = s;
        boolean deep = false;
        if (loc.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
            int surface = loc.getWorld().getHighestBlockYAt(loc);
            // Airborne: gliding, or well above the terrain. Takes priority so the two profiles never overlap.
            st.airborne = cfg.airOn() && (p.isGliding() || loc.getBlockY() > surface + cfg.airHeight());
            deep = !st.airborne && cfg.ugOn() && loc.getBlockY() < surface - cfg.ugDepth()
                    && loc.getBlock().getLightFromSky() == 0;
        } else st.airborne = false;
        st.underStreak = deep ? st.underStreak + 1 : 0;
        st.underground = st.underStreak >= cfg.ugConfirm();

        // "Nobody above ground": every surface or airborne player refreshes their area's timestamp. An underground
        // player only loses simulation distance once that timestamp is stale. Reducing it only drops THIS player's
        // ticking range, so chunks any other player covers keep ticking regardless.
        long now = System.currentTimeMillis();
        Cell cell = cells.get(cellKey(loc));
        if (cell != null && !st.underground) cell.lastSurfaceMs = now;
        boolean noSurface = cell == null || now - cell.lastSurfaceMs > 5000;
        boolean wantReduced = st.underground && cfg.ugSim() > 0 && (!cfg.ugSimGate() || noSurface);
        if (wantReduced != st.simReduced) {
            if (wantReduced) { st.baseSim = p.getSimulationDistance(); p.setSimulationDistance(cfg.ugSim()); }
            else if (st.baseSim > 0) p.setSimulationDistance(st.baseSim);
            st.simReduced = wantReduced;
        }
    }

    /** 0 = healthy, 1 = throttle, 2 = critical. */
    private int level(Cell c) {
        Settings cfg = s;
        double v = FOLIA ? c.emaIntervalMs : paperMspt;
        double warn = FOLIA ? cfg.foliaThrottleMs() : cfg.msptThrottle();
        double crit = FOLIA ? cfg.foliaCriticalMs() : cfg.msptCritical();
        int lvl = v >= crit ? 2 : v >= warn ? 1 : 0;
        if (lvl == 0 && c.newChunks > cfg.maxNewChunksPerSec()) lvl = 1;   // generation intake cap
        return lvl;
    }

    private void apply(Player p, PlayerState st, int level) {
        st.level = level;
        Settings cfg = s;
        int target = cfg.viewNormal();
        boolean combat = System.currentTimeMillis() < st.combatUntilMs;
        boolean protectedPlayer = cfg.priorityKeepsNormal() && (st.stationary || combat);   // priority: stationary or fighting
        boolean air = st.airborne;                                                           // separate profile for flyers
        if (st.tick < st.boostUntilTick) target = Math.min(air ? cfg.airBoost() : cfg.boostedView(), target);   // fast travel override
        else if (!protectedPlayer) {
            int crit = air ? cfg.airCritical() : cfg.viewCritical(), thr = air ? cfg.airThrottled() : cfg.viewThrottled();
            target = level == 2 ? crit : level == 1 ? thr : target;
        }
        // Underground clamp wins over "stationary" (the AFK miner is the whole point) but never over combat.
        if (st.underground && !combat) target = Math.min(target, cfg.ugView());
        if (target != st.appliedView) {
            st.appliedView = target;
            p.setSendViewDistance(target);   // safe: we are on the player's owning region thread
        }
    }

    /** Lock-free snapshot for /chunkguard status. Only reads concurrent maps, never touches worlds. */
    String summary() {
        double worst = 0; int hot = 0;
        for (Cell c : cells.values()) {
            worst = Math.max(worst, c.emaIntervalMs);
            if (c.emaIntervalMs >= (FOLIA ? s.foliaThrottleMs() : s.msptThrottle())) hot++;
        }
        long boosted = states.values().stream().filter(p -> p.boostUntilTick > p.tick).count();
        return "platform=" + (FOLIA ? "Folia" : "Paper") + (FOLIA ? "" : " mspt=" + String.format("%.1f", paperMspt))
                + " activeRegions=" + cells.size() + " throttledRegions=" + hot
                + " worstRegionIntervalMs=" + String.format("%.1f", worst)
                + " players=" + states.size() + " boosted=" + boosted;
    }

    private static long cellKey(Location l) {
        long cx = (l.getBlockX() >> 4) >> 5, cz = (l.getBlockZ() >> 4) >> 5;
        return (cx << 32) ^ (cz & 0xffffffffL) ^ ((long) l.getWorld().getUID().hashCode() << 48);
    }

    private static boolean detectFolia() {
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
