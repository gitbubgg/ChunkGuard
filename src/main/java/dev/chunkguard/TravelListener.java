package dev.chunkguard;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * High-speed travel mitigation. All handlers fire on the region thread that owns the player, so touching the
 * player and PlayerState is thread-safe on Folia without any extra synchronization.
 */
final class TravelListener implements Listener {
    private final LoadMonitor monitor;
    private final ChunkReaper reaper;
    private volatile Settings s;

    TravelListener(LoadMonitor monitor, ChunkReaper reaper, Settings s) { this.monitor = monitor; this.reaper = reaper; this.s = s; }
    void reload(Settings s) { this.s = s; }

    @EventHandler public void onJoin(PlayerJoinEvent e) { monitor.attach(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double d2 = dx * dx + dz * dz;
        if (d2 < 0.01) return;                                     // cheapest possible exit for look-only/idle events
        Settings cfg = s;
        double len = Math.sqrt(d2);
        if (len * 20.0 < cfg.speedBps()) return;                   // blocks per tick * 20 = blocks per second

        Player p = e.getPlayer();
        PlayerState st = monitor.state(p);

        // Trailing unload: queue the chunk we just left. It is only dropped once nobody can see it (see ChunkReaper),
        // so backtracking inside view distance never reloads anything.
        if (cfg.trailOn() && (!cfg.trailUnderLoad() || st.level > 0)) {
            int cx = from.getBlockX() >> 4, cz = from.getBlockZ() >> 4;
            if (cx != st.lastCx || cz != st.lastCz) {              // one map write per chunk crossed, not per tick
                st.lastCx = cx; st.lastCz = cz;
                reaper.trackTrail(from.getWorld().getUID(), cx, cz);
            }
        }

        // Frontier probe: isChunkLoaded is a lock-free in-memory lookup, it never triggers IO or generation.
        int ax = (int) Math.floor(to.getX() + dx / len * 16.0 * cfg.lookahead()) >> 4;
        int az = (int) Math.floor(to.getZ() + dz / len * 16.0 * cfg.lookahead()) >> 4;
        if (to.getWorld().isChunkLoaded(ax, az)) return;           // heading into known territory, nothing to do

        st.boostUntilTick = st.tick + cfg.boostTicks();            // heartbeat clamps send distance until this expires
        if (cfg.dampen() < 1.0 && p.isGliding()) {                 // brief velocity pause so pools can catch up
            Vector v = p.getVelocity();
            p.setVelocity(v.multiply(cfg.dampen()));
        }
    }

    /** /rtp and other long teleports: clamp view distance immediately, before the destination floods the generator. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        boolean far = from.getWorld() != to.getWorld() || from.distanceSquared(to) > Math.pow(s.teleportBlocks(), 2);
        if (!far) return;
        PlayerState st = monitor.state(e.getPlayer());
        st.boostUntilTick = st.tick + s.boostTicks();
        e.getPlayer().setSendViewDistance(s.boostedView());
        st.appliedView = s.boostedView();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent e) {
        long until = System.currentTimeMillis() + s.combatGraceMs();
        if (e.getEntity() instanceof Player v) monitor.state(v).combatUntilMs = until;
        if (e.getDamager() instanceof Player d) monitor.state(d).combatUntilMs = until;
    }
}
