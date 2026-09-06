package com.example.antiespultimate.modules;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.example.antiespultimate.AntiESPUltimate;
import com.example.antiespultimate.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces "interesting" blocks (ores + all container types) with a plain
 * fake block inside outgoing chunk data, then reveals the real block to a
 * specific player (via a targeted BLOCK_CHANGE packet) once that player has
 * either gotten close enough or has genuine line of sight to it.
 *
 * This is the same family of technique Orebfuscator/AntiXray plugins use for
 * ores, generalised here to also cover chests/barrels/furnaces/etc so that
 * StorageESP-style hacks only ever see the fake block, never the real one,
 * for anyone who hasn't legitimately reached it.
 *
 * Known limitations (read before relying on this in production):
 *  - Revealed blocks are not automatically re-hidden if the player walks
 *    away; that avoids block-state desync but means a player who once got
 *    close to a chest can see it again from afar afterwards. Good enough to
 *    stop cold ESP-scouting, not a perfect information-theoretic seal.
 *  - Anyone with antiespu.bypass sees everything unobfuscated (use for staff).
 *  - This does not persist state across restarts; every player re-earns
 *    visibility naturally as they explore after a reload.
 */
public class BlockObfuscationModule extends PacketAdapter {

    private final AntiESPUltimate plugin;
    private final ProtocolManager protocolManager;
    private final Set<Material> obfuscatedMaterials = EnumSet.noneOf(Material.class);
    private final WrappedBlockData fakeBlockData;
    private final double revealDistance;
    private final long revealCheckIntervalTicks;

    // players who have already had a given block revealed this session, so we
    // don't spam BLOCK_CHANGE packets every tick.
    private final Set<String> revealedKeys = ConcurrentHashMap.newKeySet();

    private BukkitTask revealTask;

    // Diagnostic counter: how many MAP_CHUNK packets this listener has actually
    // processed since the module started. Exposed via /antiespu status so we
    // can tell instantly whether the packet listener is firing at all, without
    // needing to catch a debug line in the console at the right moment.
    private final java.util.concurrent.atomic.AtomicLong packetsProcessed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong blockEntitiesScanned = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong blockEntitiesStripped = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong nbtReadFailures = new java.util.concurrent.atomic.AtomicLong();

    public long getPacketsProcessed() {
        return packetsProcessed.get();
    }

    public long getBlockEntitiesScanned() {
        return blockEntitiesScanned.get();
    }

    public long getBlockEntitiesStripped() {
        return blockEntitiesStripped.get();
    }

    public long getNbtReadFailures() {
        return nbtReadFailures.get();
    }

    public BlockObfuscationModule(AntiESPUltimate plugin, ProtocolManager protocolManager) {
        super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.MAP_CHUNK);
        this.plugin = plugin;
        this.protocolManager = protocolManager;

        for (String name : plugin.getConfig().getStringList("block-obfuscation.obfuscated-blocks")) {
            try {
                obfuscatedMaterials.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown material in obfuscated-blocks: " + name);
            }
        }

        Material fake;
        try {
            fake = Material.valueOf(plugin.getConfig().getString("block-obfuscation.fake-block", "STONE"));
        } catch (IllegalArgumentException ex) {
            fake = Material.STONE;
        }
        this.fakeBlockData = WrappedBlockData.createData(fake);
        this.revealDistance = plugin.getConfig().getDouble("block-obfuscation.reveal-distance", 6.0);
        this.revealCheckIntervalTicks = plugin.getConfig().getLong("block-obfuscation.reveal-check-interval", 10);
    }

    public void start() {
        protocolManager.addPacketListener(this);
        revealTask = Bukkit.getScheduler().runTaskTimer(plugin, this::revealTick, 20L, revealCheckIntervalTicks);
    }

    public void shutdown() {
        protocolManager.removePacketListener(this);
        if (revealTask != null) revealTask.cancel();
        revealedKeys.clear();
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        packetsProcessed.incrementAndGet();
        Player receiver = event.getPlayer();
        if (receiver.hasPermission("antiespu.bypass")) return;

        PacketContainer packet = event.getPacket();
        try {
            // We deliberately don't assume whether ProtocolLib hands back a
            // WrappedBlockData[] or WrappedBlockData[][] here -- that shape
            // isn't guaranteed stable across ProtocolLib/Minecraft versions,
            // and getting it wrong is a compile error, not just a bug. Walking
            // it via reflection works no matter which shape it turns out to be.
            Object raw = packet.getBlockDataArrays().readSafely(0);
            if (raw == null) return;
            mutateBlockDataTree(raw);
            writeGeneric(packet.getBlockDataArrays(), 0, raw);

            // The block-state array above is NOT the whole story: MAP_CHUNK
            // also carries a separate list of block-entity NBT compounds
            // (one entry per chest/furnace/etc in the chunk, with its own x/y/z
            // and a "minecraft:chest"-style id). Many ESP hacks read straight
            // from this list instead of the block palette, so it has to be
            // filtered too or the fake-STONE trick above accomplishes nothing
            // for containers specifically.
            stripObfuscatedBlockEntities(packet);
        } catch (Exception ex) {
            plugin.debug("BlockObfuscationModule failed to rewrite chunk packet: " + ex);
        }
    }

    /** Recursively walks an array-of-arrays (any depth) looking for WrappedBlockData
     *  entries to obfuscate in place. */
    private void mutateBlockDataTree(Object node) {
        if (node == null || !node.getClass().isArray()) return;
        int len = java.lang.reflect.Array.getLength(node);
        for (int i = 0; i < len; i++) {
            Object elem = java.lang.reflect.Array.get(node, i);
            if (elem instanceof WrappedBlockData) {
                WrappedBlockData bd = (WrappedBlockData) elem;
                if (obfuscatedMaterials.contains(bd.getType())) {
                    java.lang.reflect.Array.set(node, i, fakeBlockData);
                }
            } else if (elem != null && elem.getClass().isArray()) {
                mutateBlockDataTree(elem);
            }
        }
    }

    /** Writes an Object we only know the runtime shape of back into a generically-typed
     *  StructureModifier<T>, whatever T actually is. */
    @SuppressWarnings("unchecked")
    private static <T> void writeGeneric(com.comphenix.protocol.reflect.StructureModifier<T> modifier, int index, Object value) {
        modifier.writeSafely(index, (T) value);
    }

    private boolean loggedNbtKeysOnce = false;

    /**
     * Removes any block-entity NBT compound (chest/furnace/barrel/etc) whose
     * "id" matches an obfuscated material from the packet's NBT list, so ESP
     * tools reading this list directly (rather than the block palette) see
     * nothing there either.
     *
     * ProtocolLib abstracts this as one List<NbtBase<?>> per chunk section via
     * getListNbtModifier(); each compound is expected to carry "x"/"y"/"z" and
     * "id" fields based on ProtocolLib's own examples. If your build's NBT
     * layout differs (field names do shift between MC versions occasionally),
     * turn on debug: true -- the first mismatch will log the actual keys seen
     * so the field names below can be corrected.
     */
    private void stripObfuscatedBlockEntities(PacketContainer packet) {
        try {
            StructureModifier<List<NbtBase<?>>> listMod = packet.getListNbtModifier();
            int size = listMod.size();

            if (plugin.isDebug() && !loggedNbtKeysOnce) {
                plugin.debug("getListNbtModifier() reports " + size + " section(s) in this MAP_CHUNK packet.");
            }

            for (int sectionIndex = 0; sectionIndex < size; sectionIndex++) {
                List<NbtBase<?>> list = listMod.readSafely(sectionIndex);
                if (list == null || list.isEmpty()) continue;

                // Diagnostic: dump exactly what's in the FIRST non-empty block-entity
                // list we ever see, once, so we can see ground truth of the NBT shape
                // instead of guessing at field names. Remove once confirmed working.
                if (plugin.isDebug() && !loggedNbtKeysOnce) {
                    loggedNbtKeysOnce = true;
                    for (NbtBase<?> base : list) {
                        try {
                            NbtCompound nbt = NbtFactory.asCompound(base);
                            plugin.debug("Block-entity NBT sample -- keys=" + nbt.getKeys() + " raw=" + nbt);
                        } catch (Exception dumpEx) {
                            plugin.debug("Block-entity NBT sample (not a compound) -- raw=" + base + " class=" + (base == null ? "null" : base.getClass()));
                        }
                    }
                }

                List<NbtBase<?>> filtered = new ArrayList<>(list.size());
                for (NbtBase<?> base : list) {
                    blockEntitiesScanned.incrementAndGet();
                    boolean keep = true;
                    try {
                        NbtCompound nbt = NbtFactory.asCompound(base);
                        String id = firstNonNull(
                                tryGetString(nbt, "id"),
                                tryGetString(nbt, "type"),
                                tryGetString(nbt, "Id")
                        );
                        if (id != null) {
                            Material mat = blockEntityIdToMaterial(id);
                            if (mat != null && obfuscatedMaterials.contains(mat)) {
                                keep = false;
                            }
                        }
                    } catch (Exception inner) {
                        nbtReadFailures.incrementAndGet();
                        plugin.debug("Failed to read block-entity NBT entry: " + inner);
                    }
                    if (!keep) blockEntitiesStripped.incrementAndGet();
                    if (keep) filtered.add(base);
                }
                if (filtered.size() != list.size()) {
                    listMod.writeSafely(sectionIndex, filtered);
                    plugin.debug("Stripped " + (list.size() - filtered.size()) + " obfuscated block-entity entr(y/ies) from section " + sectionIndex);
                }
            }
        } catch (Exception ex) {
            plugin.debug("stripObfuscatedBlockEntities failed: " + ex);
        }
    }

    private String tryGetString(NbtCompound nbt, String key) {
        try {
            return nbt.getString(key);
        } catch (Exception ex) {
            return null;
        }
    }

    @SafeVarargs
    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }

    /** "minecraft:chest" -> Material.CHEST, best-effort. */
    private Material blockEntityIdToMaterial(String id) {
        String key = id.replace("minecraft:", "").trim().toUpperCase();
        try {
            return Material.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Periodic scan: for each online player, look at obfuscated blocks within
     * revealDistance and send them the real block if they're close enough or
     * have genuine line of sight to it.
     */
    private void revealTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("antiespu.bypass")) continue;

            Location eye = player.getEyeLocation();
            World world = player.getWorld();
            int radius = (int) Math.ceil(revealDistance);

            int px = eye.getBlockX(), py = eye.getBlockY(), pz = eye.getBlockZ();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Block block = world.getBlockAt(px + dx, py + dy, pz + dz);
                        Material mat = block.getType();
                        if (!obfuscatedMaterials.contains(mat)) continue;

                        String key = player.getUniqueId() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
                        if (revealedKeys.contains(key)) continue;

                        double distSq = block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(eye);
                        boolean closeEnough = distSq <= revealDistance * revealDistance;
                        boolean canSee = closeEnough || RaycastUtils.canSeeBlock(
                                eye, block.getLocation().add(0.5, 0.5, 0.5), revealDistance);

                        if (canSee) {
                            sendRealBlock(player, block);
                            revealedKeys.add(key);
                        }
                    }
                }
            }
        }
    }

    private void sendRealBlock(Player player, Block block) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
        packet.getBlockPositionModifier().write(0,
                new BlockPosition(block.getX(), block.getY(), block.getZ()));
        packet.getBlockData().write(0, WrappedBlockData.createData(block.getBlockData()));
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception ex) {
            plugin.debug("Failed to send reveal packet: " + ex);
        }
    }
}
