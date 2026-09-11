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
import com.example.antiespultimate.AntiESPUltimate;
import com.example.antiespultimate.util.RaycastUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.IdentityHashMap;
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
    private final java.util.concurrent.atomic.AtomicLong reflectionFieldAccessFailures = new java.util.concurrent.atomic.AtomicLong();

    // Stored diagnostic strings, exposed via /antiespu status -- console
    // logging has proven unreliable to observe (build mismatches, timing,
    // possibly filtered by another plugin), so we keep the most useful
    // findings as plain plugin state that a command can always read back.
    private volatile String lastHandleClassName = "(none seen yet)";
    private volatile String lastTopLevelFields = "(none seen yet)";
    private volatile String lastOuterError = "(none)";
    private final java.util.concurrent.atomic.AtomicLong coordsNullCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong handleNullCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong outerExceptionCount = new java.util.concurrent.atomic.AtomicLong();

    public String getLastHandleClassName() {
        return lastHandleClassName;
    }

    public String getLastTopLevelFields() {
        return lastTopLevelFields;
    }

    public String getLastOuterError() {
        return lastOuterError;
    }

    public long getCoordsNullCount() {
        return coordsNullCount.get();
    }

    public long getHandleNullCount() {
        return handleNullCount.get();
    }

    public long getOuterExceptionCount() {
        return outerExceptionCount.get();
    }

    public long getReflectionFieldAccessFailures() {
        return reflectionFieldAccessFailures.get();
    }

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
            stripObfuscatedBlockEntities(packet, receiver);
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

    /**
     * Removes any block-entity (chest/furnace/barrel/etc) info from the raw
     * NMS chunk packet, so ESP tools reading it directly (rather than the
     * block palette) see nothing there either.
     *
     * WHY REFLECTION INSTEAD OF PROTOCOLLIB'S getListNbtModifier(): since
     * Minecraft 1.20.2, block-entity data in MAP_CHUNK is sent as a compact
     * record (ClientboundLevelChunkPacketData$BlockEntityInfo: packedXZ, y,
     * type, tag) rather than the flat NBT-compound-with-"id" list that
     * ProtocolLib's getListNbtModifier() was built for. That old API silently
     * returns nothing on modern versions -- confirmed via the
     * blockEntitiesScanned counter staying at 0 across hundreds of real
     * packets. So instead we walk the actual NMS packet object with
     * reflection, find whatever List holds BlockEntityInfo-shaped elements
     * (matched by class name, not a hardcoded field path, since exact nesting
     * can vary), read each entry's packedXZ/y fields (stable, official Mojang
     * mapping field names), resolve that to a real world coordinate, and ask
     * Bukkit directly whether the REAL block there is one we're obfuscating
     * -- sidestepping the need to decode the "type" field via NMS registries
     * entirely.
     */
    private void stripObfuscatedBlockEntities(PacketContainer packet, Player receiver) {
        try {
            com.comphenix.protocol.wrappers.ChunkCoordIntPair coords =
                    packet.getChunkCoordIntPairs().readSafely(0);
            if (coords == null) {
                coordsNullCount.incrementAndGet();
                return;
            }
            int chunkX = coords.getChunkX();
            int chunkZ = coords.getChunkZ();
            World world = receiver.getWorld();

            Object handle = packet.getHandle();
            if (handle == null) {
                handleNullCount.incrementAndGet();
                return;
            }
            List<Object> blockEntities = findBlockEntityInfoList(handle, 0, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            if (blockEntities == null) {
                return;
            }

            List<Object> filtered = new ArrayList<>(blockEntities.size());
            for (Object entry : blockEntities) {
                blockEntitiesScanned.incrementAndGet();
                boolean keep = true;
                try {
                    int packedXZ = getIntField(entry, "packedXZ");
                    int y = getIntField(entry, "y");
                    int localX = (packedXZ >> 4) & 15;
                    int localZ = packedXZ & 15;
                    int worldX = (chunkX << 4) + localX;
                    int worldZ = (chunkZ << 4) + localZ;
                    Material actual = world.getBlockAt(worldX, y, worldZ).getType();
                    if (obfuscatedMaterials.contains(actual)) {
                        keep = false;
                    }
                } catch (Exception inner) {
                    nbtReadFailures.incrementAndGet();
                    plugin.debug("Failed reading BlockEntityInfo entry (" + entry.getClass() + "): " + inner);
                }
                if (!keep) blockEntitiesStripped.incrementAndGet();
                if (keep) filtered.add(entry);
            }

            if (filtered.size() != blockEntities.size()) {
                // Mutate the list in place -- it's a plain mutable list from
                // packet deserialization, so no need to reassign the field.
                blockEntities.clear();
                blockEntities.addAll(filtered);
            }
        } catch (Exception ex) {
            outerExceptionCount.incrementAndGet();
            lastOuterError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            plugin.debug("stripObfuscatedBlockEntities failed: " + ex);
        }
    }

    /**
     * Recursively searches root's fields (and, if not found, one level into
     * its non-trivial object fields) for a List whose elements are
     * BlockEntityInfo-shaped, identified by class name rather than an exact
     * field path so this survives packet-structure changes across versions.
     */
    @SuppressWarnings("unchecked")
    private List<Object> findBlockEntityInfoList(Object root, int depth, Set<Object> visited) {
        if (root == null || depth > 3 || !visited.add(root)) return null;

        if (depth == 0) {
            lastHandleClassName = root.getClass().getName();
        }

        List<Field> fields = allDeclaredFields(root.getClass());

        // Pass 1: does this object directly hold the list we want?
        for (Field f : fields) {
            f.setAccessible(true);
            Object val;
            try {
                val = f.get(root);
            } catch (Exception ex) {
                reflectionFieldAccessFailures.incrementAndGet();
                if (plugin.isDebug() && reflectionFieldAccessFailures.get() <= 3) {
                    plugin.debug("Reflection field access failed on " + root.getClass().getSimpleName()
                            + "." + f.getName() + ": " + ex);
                }
                continue;
            }
            if (val instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first != null && first.getClass().getName().contains("BlockEntityInfo")) {
                    return (List<Object>) list;
                }
            }
        }

        if (depth == 0) {
            StringBuilder sb = new StringBuilder();
            for (Field f : fields) {
                sb.append(f.getName()).append(":").append(f.getType().getSimpleName()).append(", ");
            }
            lastTopLevelFields = sb.toString();
        }

        // Pass 2: recurse into plausible nested objects (skip primitives,
        // boxed types, collections we already checked, and JDK/Netty/Bukkit
        // internals that would never contain this).
        for (Field f : fields) {
            f.setAccessible(true);
            Object val;
            try {
                val = f.get(root);
            } catch (Exception ex) {
                continue;
            }
            if (val == null) continue;
            Class<?> vc = val.getClass();
            if (vc.isPrimitive() || val instanceof Number || val instanceof String
                    || val instanceof Boolean || val instanceof Enum
                    || val instanceof List || val instanceof java.util.Map) continue;
            String pkg = vc.getName();
            if (pkg.startsWith("java.") || pkg.startsWith("javax.")
                    || pkg.startsWith("io.netty") || pkg.startsWith("org.bukkit")
                    || pkg.startsWith("com.comphenix")) continue;

            List<Object> nested = findBlockEntityInfoList(val, depth + 1, visited);
            if (nested != null) return nested;
        }
        return null;
    }

    private List<Field> allDeclaredFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        while (cls != null && cls != Object.class) {
            fields.addAll(Arrays.asList(cls.getDeclaredFields()));
            cls = cls.getSuperclass();
        }
        return fields;
    }

    private int getIntField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(obj);
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
