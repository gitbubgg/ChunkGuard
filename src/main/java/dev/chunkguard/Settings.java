package dev.chunkguard;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml. Immutable means it is safely readable from every region thread without locks. */
public record Settings(
        double msptThrottle, double msptCritical, double foliaThrottleMs, double foliaCriticalMs, int maxNewChunksPerSec,
        int viewNormal, int viewThrottled, int viewCritical, boolean priorityKeepsNormal, long combatGraceMs,
        double speedBps, double teleportBlocks, int lookahead, int boostedView, long boostTicks, double dampen,
        boolean reaperOn, long sweepTicks, long minDwellMs, long maxInhabited, int maxPerSweep,
        boolean trailOn, boolean trailUnderLoad, long trailDwellMs, int maxTracked,
        boolean ugOn, int ugDepth, int ugView, int ugConfirm,
        boolean airOn, int airHeight, int airThrottled, int airCritical, int airBoost,
        int ugSim, boolean ugSimGate) {

    static Settings load(FileConfiguration c) {
        return new Settings(
                c.getDouble("load.mspt-throttle", 45), c.getDouble("load.mspt-critical", 50),
                c.getDouble("load.folia-interval-throttle-ms", 52), c.getDouble("load.folia-interval-critical-ms", 60),
                c.getInt("load.max-new-chunks-per-second-per-region", 24),
                c.getInt("view-distance.normal", 10), c.getInt("view-distance.throttled", 6), c.getInt("view-distance.critical", 3),
                c.getBoolean("view-distance.stationary-or-combat-keeps-normal", true),
                c.getLong("view-distance.combat-grace-seconds", 10) * 1000,
                c.getDouble("travel.speed-threshold-bps", 40), c.getDouble("travel.teleport-threshold-blocks", 512),
                c.getInt("travel.lookahead-chunks", 4), c.getInt("travel.boosted-view-distance", 4),
                c.getLong("travel.boost-ticks", 100), c.getDouble("travel.dampen-gliding-velocity", 0.7),
                c.getBoolean("reaper.enabled", true), c.getLong("reaper.sweep-period-ticks", 200),
                c.getLong("reaper.min-dwell-seconds", 20) * 1000, c.getLong("reaper.max-inhabited-ticks", 200),
                c.getInt("reaper.max-per-sweep", 512),
                c.getBoolean("trail.enabled", true), c.getBoolean("trail.only-under-load", true),
                c.getLong("trail.dwell-seconds", 6) * 1000, c.getInt("trail.max-tracked-chunks", 20000),
                c.getBoolean("underground.enabled", true), c.getInt("underground.min-depth", 30),
                c.getInt("underground.view-distance", 3), c.getInt("underground.confirm-seconds", 3),
                c.getBoolean("airborne.enabled", true), c.getInt("airborne.min-height", 40),
                c.getInt("airborne.throttled-view", 8), c.getInt("airborne.critical-view", 5), c.getInt("airborne.boosted-view", 6),
                c.getInt("underground.simulation-distance", 4), c.getBoolean("underground.only-if-no-surface-players", true));
    }
}
