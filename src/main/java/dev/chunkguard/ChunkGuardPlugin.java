package dev.chunkguard;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. No BukkitScheduler usage anywhere: every task goes through Global, Region or Entity schedulers,
 * which exist on both Folia and modern Paper, so one code path serves both.
 */
public final class ChunkGuardPlugin extends JavaPlugin {
    private LoadMonitor monitor;
    private TravelListener travel;
    private ChunkReaper reaper;

    @Override public void onEnable() {
        saveDefaultConfig();
        Settings s = Settings.load(getConfig());
        monitor = new LoadMonitor(this, s);
        reaper = new ChunkReaper(this, s);
        travel = new TravelListener(monitor, reaper, s);

        getServer().getPluginManager().registerEvents(travel, this);
        getServer().getPluginManager().registerEvents(new ChunkEventListener(monitor, reaper), this);
        monitor.start();
        reaper.start();
        // Players already online after a /reload.
        for (var p : Bukkit.getOnlinePlayers()) monitor.attach(p);
        getLogger().info("ChunkGuard enabled.");
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender who, org.bukkit.command.Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        if (!who.hasPermission("chunkguard.admin")) { who.sendMessage("No permission."); return true; }
        switch (sub) {
            case "reload" -> { reloadSettings(); who.sendMessage("ChunkGuard config reloaded."); }
            case "status", "metrics" -> {
                who.sendMessage("ChunkGuard: " + monitor.summary());
                who.sendMessage("Reaper: tracked=" + reaper.trackedCount() + " unloadedTotal=" + reaper.unloaded.sum());
            }
            default -> who.sendMessage("Usage: /" + label + " <status|metrics|reload>");
        }
        return true;
    }

    /** Hot reload of config without restart; settings are swapped atomically through volatile fields. */
    public void reloadSettings() {
        reloadConfig();
        Settings s = Settings.load(getConfig());
        monitor.reload(s); reaper.reload(s); travel.reload(s);
    }
}
