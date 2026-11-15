package de.challengemod;

import java.util.ArrayDeque;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Welt-Randomizer für NEU erzeugte Chunks der Oberwelt (nur während eine Challenge läuft):
 *  - Chunk-Versatz (#m_cshift): der ganze Chunk wird zufällig nach oben oder unten geschoben
 *  - Zufalls-Biom pro Chunk (#m_cbiome): der Chunk bekommt ein zufälliges Biom und eine passende Oberfläche
 * Neue Chunks kommen in eine Warteschlange und werden mit Zeitlimit pro Tick abgearbeitet.
 */
final class WorldRandomizer {
    /** Blöcke leise setzen: an Clients schicken, keine Nachbar-Updates, keine Drops, kein "onPlace" (kein Wasser-/Sand-Chaos). */
    private static final int FLAGS = 2 | 16 | 32 | 512;
    private static final int MAX_QUEUE = 3000;
    private static final long BUDGET_NANOS = 12_000_000L;

    private record Theme(String biome, Block top, Block under) {
    }

    private static Theme[] themes;
    private static final ArrayDeque<int[]> queue = new ArrayDeque<>();
    private static boolean errorLogged;

    private WorldRandomizer() {
    }

    private static Theme[] themes() {
        if (themes == null) {
            themes = new Theme[] {
                    new Theme("plains", Blocks.GRASS_BLOCK, Blocks.DIRT),
                    new Theme("desert", Blocks.SAND, Blocks.SANDSTONE),
                    new Theme("badlands", Blocks.RED_SAND, Blocks.TERRACOTTA),
                    new Theme("snowy_plains", Blocks.SNOW_BLOCK, Blocks.DIRT),
                    new Theme("ice_spikes", Blocks.PACKED_ICE, Blocks.PACKED_ICE),
                    new Theme("mushroom_fields", Blocks.MYCELIUM, Blocks.DIRT),
                    new Theme("jungle", Blocks.GRASS_BLOCK, Blocks.DIRT),
                    new Theme("swamp", Blocks.MUD, Blocks.DIRT),
                    new Theme("cherry_grove", Blocks.GRASS_BLOCK, Blocks.DIRT),
                    new Theme("dark_forest", Blocks.PODZOL, Blocks.DIRT),
                    new Theme("savanna", Blocks.COARSE_DIRT, Blocks.DIRT),
                    new Theme("stony_peaks", Blocks.STONE, Blocks.STONE),
                    new Theme("lush_caves", Blocks.MOSS_BLOCK, Blocks.DIRT),
                    new Theme("crimson_forest", Blocks.CRIMSON_NYLIUM, Blocks.NETHERRACK),
                    new Theme("warped_forest", Blocks.WARPED_NYLIUM, Blocks.NETHERRACK),
                    new Theme("the_end", Blocks.END_STONE, Blocks.END_STONE),
                    new Theme("deep_dark", Blocks.SCULK, Blocks.DEEPSLATE),
                    new Theme("meadow", Blocks.GRASS_BLOCK, Blocks.DIRT),
            };
        }
        return themes;
    }

    static void reset() {
        queue.clear();
    }

    /** Wird von Fabric aufgerufen, sobald ein frisch erzeugter Chunk geladen ist. Hier nur vormerken. */
    static void onChunkGenerated(ServerLevel level, LevelChunk chunk) {
        try {
            if (level.dimension() != Level.OVERWORLD || queue.size() >= MAX_QUEUE) {
                return;
            }
            MinecraftServer server = level.getServer();
            if (Scores.get(server, "#state", "sb", 4) != 1) {
                return;
            }
            if (Scores.get(server, "#m_cshift", "sb.cfg", 0) != 1 && Scores.get(server, "#m_cbiome", "sb.cfg", 0) != 1) {
                return;
            }
            queue.add(new int[] {chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ()});
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    static void tick(MinecraftServer server) {
        if (queue.isEmpty()) {
            return;
        }
        try {
            if (Scores.get(server, "#state", "sb", 4) != 1) {
                queue.clear();
                return;
            }
            if (Scores.get(server, "#paused", "sb", 0) == 1) {
                return;
            }
            boolean shift = Scores.get(server, "#m_cshift", "sb.cfg", 0) == 1;
            boolean biome = Scores.get(server, "#m_cbiome", "sb.cfg", 0) == 1;
            int max = Math.max(4, Math.min(24, Scores.get(server, "#cshift_max", "sb.cfg", 12)));
            ServerLevel level = server.overworld();
            long deadline = System.nanoTime() + BUDGET_NANOS;
            do {
                int[] entry = queue.poll();
                process(server, level, entry[0], entry[1], shift, biome, max);
            } while (!queue.isEmpty() && System.nanoTime() < deadline);
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    private static void process(MinecraftServer server, ServerLevel level, int minX, int minZ, boolean shift, boolean biome, int max) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(minX >> 4, minZ >> 4);
        if (chunk == null || playerNear(server, level, minX + 8, minZ + 8)) {
            return; // schon wieder entladen bzw. ein Spieler steht daneben -> Chunk bleibt, wie er ist
        }
        Random random = new Random(level.getSeed() ^ (minX * 341873128712L + minZ * 132897987541L));
        int offset = random.nextInt(2 * max + 1) - max;
        Theme theme = themes()[random.nextInt(themes().length)];
        if (shift && Math.abs(offset) >= 2 && canShift(level, chunk, minX, minZ)) {
            shiftChunk(level, minX, minZ, offset);
        }
        if (biome) {
            applyTheme(server, level, minX, minZ, theme);
        }
    }

    private static boolean playerNear(MinecraftServer server, ServerLevel level, int x, int z) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level && Math.abs(player.getX() - x) < 40 && Math.abs(player.getZ() - z) < 40) {
                return true;
            }
        }
        return false;
    }

    /** Keine Bauwerke (Dorf, Festung …), keine Truhen/Spawner und kein Ozean/Fluss. */
    private static boolean canShift(ServerLevel level, LevelChunk chunk, int minX, int minZ) {
        if (!chunk.getBlockEntities().isEmpty()) {
            return false;
        }
        for (var references : chunk.getAllReferences().values()) {
            if (!references.isEmpty()) {
                return false;
            }
        }
        var biome = level.getBiome(new BlockPos(minX + 8, 63, minZ + 8));
        return !biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_RIVER);
    }

    private static void shiftChunk(ServerLevel level, int minX, int minZ, int offset) {
        int minY = level.getMinY();
        int maxY = minY + level.getHeight() - 1;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), maxY);
                if (offset > 0) {
                    // nach oben: von oben nach unten kopieren, unten mit Tiefenschiefer auffüllen
                    for (int y = Math.min(top, maxY - offset); y > minY; y--) {
                        BlockState state = level.getBlockState(pos.set(x, y, z));
                        put(level, pos, x, y + offset, z, state == bedrock ? deepslate : state);
                    }
                    for (int y = minY + offset; y > minY; y--) {
                        put(level, pos, x, y, z, deepslate);
                    }
                } else {
                    // nach unten: von unten nach oben kopieren, oben mit Luft auffüllen, Boden bleibt Grundgestein
                    int down = -offset;
                    for (int y = minY + down + 1; y <= top; y++) {
                        BlockState state = level.getBlockState(pos.set(x, y, z));
                        put(level, pos, x, y - down, z, state);
                    }
                    for (int y = Math.max(top - down + 1, minY + 1); y <= top; y++) {
                        put(level, pos, x, y, z, air);
                    }
                    put(level, pos, x, minY, z, bedrock);
                }
            }
        }
        // Tiere & Co. mitnehmen, damit sie nicht ersticken oder abstürzen
        for (Entity entity : level.getEntities((Entity) null, new AABB(minX, minY, minZ, minX + 16, maxY, minZ + 16))) {
            if (!(entity instanceof Player)) {
                entity.teleportTo(entity.getX(), entity.getY() + offset, entity.getZ());
            }
        }
    }

    private static void put(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState state) {
        if (level.getBlockState(pos.set(x, y, z)) != state) {
            level.setBlock(new BlockPos(x, y, z), state, FLAGS);
        }
    }

    private static void applyTheme(MinecraftServer server, ServerLevel level, int minX, int minZ, Theme theme) {
        int minY = level.getMinY();
        int maxY = minY + level.getHeight() - 1;
        // Biom setzen (in Scheiben, weil /fillbiome höchstens 32768 Blöcke auf einmal ändert)
        for (int y = minY; y <= maxY; y += 128) {
            ChallengeMod.runAsServer(server, "execute in minecraft:overworld run fillbiome " + minX + " " + y + " " + minZ + " "
                    + (minX + 15) + " " + Math.min(y + 127, maxY) + " " + (minZ + 15) + " minecraft:" + theme.biome());
        }
        // Oberfläche umfärben: oberster Bodenblock + 3 darunter
        BlockState top = theme.top().defaultBlockState();
        BlockState under = theme.under().defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                int start = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), maxY) - 1;
                for (int y = start; y > Math.max(start - 48, minY + 4); y--) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (!state.canOcclude() || state.is(BlockTags.LOGS)) {
                        continue; // Luft, Pflanzen, Laub, Wasser, Baumstämme überspringen
                    }
                    boolean underWater = !level.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty();
                    put(level, pos, x, y, z, underWater ? under : top);
                    for (int d = 1; d <= 3; d++) {
                        if (level.getBlockState(pos.set(x, y - d, z)).canOcclude()) {
                            put(level, pos, x, y - d, z, under);
                        }
                    }
                    break;
                }
            }
        }
    }

    private static void logOnce(Throwable t) {
        if (!errorLogged) {
            errorLogged = true;
            ChallengeMod.LOGGER.error("Welt-Randomizer ist ausgefallen (wird nur einmal gemeldet)", t);
        }
    }
}
