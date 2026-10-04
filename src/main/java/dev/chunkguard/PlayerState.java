package dev.chunkguard;

import org.bukkit.Location;

/** Per-player scratch state. Only ever touched by the region thread that owns the player (plus volatile reads), so no locks. */
final class PlayerState {
    Location last;                 // location at previous heartbeat
    boolean stationary = true;
    int appliedView = -1;          // avoids redundant setSendViewDistance calls (each one re-sends chunk packets)
    volatile long combatUntilMs;
    volatile long boostUntilTick;  // travel mitigation override, measured in heartbeat ticks
    long tick;
    boolean simReduced; int baseSim = -1;   // simulation distance bookkeeping, so we can restore the exact original
    boolean airborne;
    boolean underground; int underStreak;   // underground = confirmed deep and enclosed
    volatile int level;            // last computed load level (0 healthy, 1 throttle, 2 critical)
    int lastCx = Integer.MIN_VALUE, lastCz;   // last chunk this player passed, dedupes trail tracking
}
