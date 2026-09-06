package com.example.antiespultimate.modules;

import com.example.antiespultimate.AntiESPUltimate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Clamps the per-player sent view distance (Paper API). This does not detect
 * or block FreeCam -- nothing server-side can, since freecam sends no packets
 * of its own. It only limits how much world data a client is ever given in
 * the first place, which caps how far any client (freecam included) can look.
 *
 * If you're on plain Spigot (not Paper), Player#setSendViewDistance doesn't
 * exist; drop this module or replace it with lowering the global
 * server.properties view-distance/simulation-distance instead.
 */
public class ViewDistanceModule implements Listener {

    private final AntiESPUltimate plugin;
    private final int maxViewDistance;

    public ViewDistanceModule(AntiESPUltimate plugin) {
        this.plugin = plugin;
        this.maxViewDistance = plugin.getConfig().getInt("view-distance-limit.max-sent-view-distance", 6);
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    public void shutdown() {
        PlayerJoinEvent.getHandlerList().unregister(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    private void apply(Player player) {
        try {
            // Paper-only API. Comment out / remove this call if building against
            // plain Spigot, and rely on server.properties view-distance instead.
            player.setSendViewDistance(maxViewDistance);
        } catch (NoSuchMethodError | Exception ex) {
            plugin.debug("setSendViewDistance unavailable (probably not running Paper): " + ex);
        }
    }
}
