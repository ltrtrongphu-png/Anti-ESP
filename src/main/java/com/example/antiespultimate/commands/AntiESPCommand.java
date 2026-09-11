package com.example.antiespultimate.commands;

import com.example.antiespultimate.AntiESPUltimate;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;

public class AntiESPCommand implements CommandExecutor, TabCompleter {

    private final AntiESPUltimate plugin;

    public AntiESPCommand(AntiESPUltimate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antiespu.admin")) {
            sender.sendMessage(color(plugin.getConfig().getString("messages.no-permission")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "/antiespu <reload|status>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                plugin.reloadModules();
                sender.sendMessage(color(plugin.getConfig().getString("messages.reloaded")));
                return true;
            case "status":
                sender.sendMessage(ChatColor.GOLD + "=== AntiESPUltimate === " + ChatColor.YELLOW + "[build-marker: reflection-v3]");
                sender.sendMessage("Debug: " + plugin.isDebug());
                sender.sendMessage("Block obfuscation: " + plugin.getConfig().getBoolean("block-obfuscation.enabled"));
                if (plugin.getBlockObfuscationModule() != null) {
                    var mod = plugin.getBlockObfuscationModule();
                    sender.sendMessage("  MAP_CHUNK packets processed: " + mod.getPacketsProcessed());
                    sender.sendMessage("  Block-entities scanned: " + mod.getBlockEntitiesScanned()
                            + "  |  stripped: " + mod.getBlockEntitiesStripped()
                            + "  |  NBT read failures: " + mod.getNbtReadFailures()
                            + "  |  reflection field-access failures: " + mod.getReflectionFieldAccessFailures());
                    sender.sendMessage(ChatColor.GRAY + "  (packets=0 -> listener never fires. scanned=0 -> reflection couldn't"
                            + " locate the BlockEntityInfo list on this packet (check console for 'could not locate a"
                            + " BlockEntityInfo list' with debug:true). scanned>0 but stripped=0 -> the real block at that"
                            + " position isn't in your obfuscated-blocks list, or coordinate math is off.)");
                } else {
                    sender.sendMessage(ChatColor.RED + "  Module is not running (check earlier console errors).");
                }
                sender.sendMessage("Player visibility: " + plugin.getConfig().getBoolean("player-visibility.enabled"));
                sender.sendMessage("View distance limit: " + plugin.getConfig().getBoolean("view-distance-limit.enabled")
                        + " (max " + plugin.getConfig().getInt("view-distance-limit.max-sent-view-distance") + ")");
                sender.sendMessage(ChatColor.GRAY + "Note: FreeCam cannot be detected/blocked server-side; "
                        + "view-distance-limit only reduces how far it has anything to look at.");
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("reload", "status");
        return List.of();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
