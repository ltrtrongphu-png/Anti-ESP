package com.example.antiespultimate;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.example.antiespultimate.commands.AntiESPCommand;
import com.example.antiespultimate.modules.BlockObfuscationModule;
import com.example.antiespultimate.modules.PlayerVisibilityModule;
import com.example.antiespultimate.modules.ViewDistanceModule;
import org.bukkit.plugin.java.JavaPlugin;

public class AntiESPUltimate extends JavaPlugin {

    private ProtocolManager protocolManager;
    private BlockObfuscationModule blockObfuscationModule;
    private PlayerVisibilityModule playerVisibilityModule;
    private ViewDistanceModule viewDistanceModule;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.protocolManager = ProtocolLibrary.getProtocolManager();

        reloadModules();

        AntiESPCommand cmd = new AntiESPCommand(this);
        getCommand("antiespu").setExecutor(cmd);
        getCommand("antiespu").setTabCompleter(cmd);

        getLogger().info("AntiESPUltimate enabled.");
    }

    @Override
    public void onDisable() {
        if (blockObfuscationModule != null) blockObfuscationModule.shutdown();
        if (playerVisibilityModule != null) playerVisibilityModule.shutdown();
        if (viewDistanceModule != null) viewDistanceModule.shutdown();
        getLogger().info("AntiESPUltimate disabled.");
    }

    /** Tears down and re-registers all modules from the current config. Used by /antiespu reload. */
    public void reloadModules() {
        if (blockObfuscationModule != null) blockObfuscationModule.shutdown();
        if (playerVisibilityModule != null) playerVisibilityModule.shutdown();
        if (viewDistanceModule != null) viewDistanceModule.shutdown();

        if (getConfig().getBoolean("block-obfuscation.enabled", true)) {
            blockObfuscationModule = new BlockObfuscationModule(this, protocolManager);
            blockObfuscationModule.start();
        } else {
            blockObfuscationModule = null;
        }

        if (getConfig().getBoolean("player-visibility.enabled", true)) {
            playerVisibilityModule = new PlayerVisibilityModule(this, protocolManager);
            playerVisibilityModule.start();
        } else {
            playerVisibilityModule = null;
        }

        if (getConfig().getBoolean("view-distance-limit.enabled", true)) {
            viewDistanceModule = new ViewDistanceModule(this);
            viewDistanceModule.start();
        } else {
            viewDistanceModule = null;
        }
    }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }

    public void debug(String msg) {
        if (isDebug()) getLogger().info("[debug] " + msg);
    }
}
