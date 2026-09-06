package com.example.antiespultimate.modules;

import com.comphenix.protocol.ProtocolManager;
import com.example.antiespultimate.AntiESPUltimate;
import com.example.antiespultimate.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Team;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Uses Bukkit's own Player#hidePlayer / showPlayer (backed by the server's
 * normal entity spawn/destroy packets) rather than raw NMS reflection, so it
 * keeps working across server version bumps without needing to be rewritten
 * against new mapped class names each time.
 */
public class PlayerVisibilityModule {

    private final AntiESPUltimate plugin;
    private final ProtocolManager protocolManager; // kept for parity/future packet-level needs
    private final long checkIntervalTicks;
    private final double maxDistance;
    private final double minDistance;
    private final double blockingThreshold;
    private final boolean teamExemption;

    // viewer -> set of targets currently hidden from them
    private final Map<String, Boolean> hiddenPairs = new ConcurrentHashMap<>();

    private BukkitTask task;

    public PlayerVisibilityModule(AntiESPUltimate plugin, ProtocolManager protocolManager) {
        this.plugin = plugin;
        this.protocolManager = protocolManager;
        this.checkIntervalTicks = plugin.getConfig().getLong("player-visibility.check-interval", 2);
        this.maxDistance = plugin.getConfig().getDouble("player-visibility.max-distance", 64.0);
        this.minDistance = plugin.getConfig().getDouble("player-visibility.min-distance", 5.0);
        this.blockingThreshold = plugin.getConfig().getDouble("player-visibility.blocking-threshold", 0.7);
        this.teamExemption = plugin.getConfig().getBoolean("player-visibility.team-exemption", true);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, checkIntervalTicks);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        // restore visibility for everyone so nobody stays stuck hidden
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (viewer != target) viewer.showPlayer(plugin, target);
            }
        }
        hiddenPairs.clear();
    }

    private void tick() {
        Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        for (Player viewer : players) {
            if (viewer.hasPermission("antiespu.bypass")) continue;
            for (Player target : players) {
                if (viewer == target) continue;
                if (target.hasPermission("antiespu.bypass")) continue;
                evaluatePair(viewer, target);
            }
        }
    }

    private void evaluatePair(Player viewer, Player target) {
        String key = viewer.getUniqueId() + ":" + target.getUniqueId();

        if (teamExemption && sameTeam(viewer, target)) {
            setHidden(viewer, target, key, false);
            return;
        }

        Location viewerEye = viewer.getEyeLocation();
        Location targetEye = target.getEyeLocation();
        double distance = viewerEye.distance(targetEye);

        if (distance > maxDistance || distance <= minDistance) {
            setHidden(viewer, target, key, false);
            return;
        }

        boolean visible = RaycastUtils.hasLineOfSight(viewerEye, targetEye, blockingThreshold);
        setHidden(viewer, target, key, !visible);
    }

    private void setHidden(Player viewer, Player target, String key, boolean shouldBeHidden) {
        boolean currentlyHidden = hiddenPairs.getOrDefault(key, false);
        if (shouldBeHidden == currentlyHidden) return;

        if (shouldBeHidden) {
            viewer.hidePlayer(plugin, target);
            hiddenPairs.put(key, true);
            plugin.debug(viewer.getName() + " can no longer see " + target.getName());
        } else {
            viewer.showPlayer(plugin, target);
            hiddenPairs.put(key, false);
            plugin.debug(viewer.getName() + " can see " + target.getName() + " again");
        }
    }

    private boolean sameTeam(Player a, Player b) {
        Team teamA = a.getScoreboard().getEntryTeam(a.getName());
        Team teamB = b.getScoreboard().getEntryTeam(b.getName());
        return teamA != null && teamA.equals(teamB);
    }
}
