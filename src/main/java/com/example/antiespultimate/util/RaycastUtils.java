package com.example.antiespultimate.util;

import org.bukkit.Location;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class RaycastUtils {

    private RaycastUtils() {}

    /**
     * True if there is an unobstructed line of sight between two eye locations,
     * i.e. LESS than blockingThreshold of the path is blocked by opaque blocks.
     * blockingThreshold in [0,1]; 0.7 means "70% of sampled points along the
     * path must be inside solid blocks before we call it blocked".
     */
    public static boolean hasLineOfSight(Location from, Location to, double blockingThreshold) {
        if (!from.getWorld().equals(to.getWorld())) return false;

        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance < 0.01) return true;
        direction.normalize();

        double step = 0.5;
        int samples = (int) Math.ceil(distance / step);
        int blocked = 0;

        Location cursor = from.clone();
        for (int i = 0; i < samples; i++) {
            cursor.add(direction.clone().multiply(step));
            if (cursor.getBlock().getType().isOccluding()) {
                blocked++;
            }
        }

        double blockedRatio = samples == 0 ? 0 : (double) blocked / samples;
        return blockedRatio < blockingThreshold;
    }

    /** Convenience wrapper using Bukkit's built-in ray trace for a single block target. */
    public static boolean canSeeBlock(Location eye, Location blockCenter, double maxDistance) {
        Vector dir = blockCenter.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist > maxDistance) return false;
        RayTraceResult result = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), dist, null, true);
        if (result == null || result.getHitBlock() == null) return true; // nothing solid in the way
        return result.getHitBlock().getLocation().distanceSquared(blockCenter) < 2.0;
    }
}
