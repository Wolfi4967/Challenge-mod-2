package de.challengemod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ziel 6 „Seelenwelten“: das Tor zum Riss.
 *  - In der Oberwelt und im Nether steht dasselbe Tor an der passenden Stelle (Oberwelt-Koordinaten / 8).
 *  - Jedes Tor braucht vier Siegel (ModItems.SEALS). Rechtsklick mit einem Siegel in der Nähe des eigenen Tors setzt es ein.
 *  - Sind alle acht Siegel drin, öffnen sich beide Tore. Stehen dann alle gleichzeitig im Tor, geht es auf die Insel.
 * Der Fortschritt liegt im Scoreboard (Ziel "sb"), damit er einen Neustart übersteht:
 *  #rift_phase 0 = nichts, 1 = Siegel sammeln, 2 = auf der Insel, 3 = Bosskampf, 4 = geschafft
 */
final class Rift {
    static final int FLAGS = 2 | 16 | 32 | 512;
    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    static final String ARENA = "challenge:riss";
    private static final int[][] SOCKETS = {{-4, 6}, {4, 6}, {-4, 12}, {4, 12}};
    private static int clock;
    private static int countdown;
    private static int returnTimer;

    private Rift() {
    }

    static void reset() {
        clock = 0;
        countdown = 0;
        returnTimer = 0;
    }

    // ------------------------------------------------------------------ Helfer
    static int score(MinecraftServer server, String holder) {
        return Scores.get(server, holder, "sb", 0);
    }

    static void set(MinecraftServer server, String holder, int value) {
        ChallengeMod.runAsServer(server, "scoreboard players set " + holder + " sb " + value);
    }

    static void cmd(MinecraftServer server, String command) {
        ChallengeMod.runAsServer(server, command);
    }

    static ServerLevel findLevel(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().toString().contains(id)) {
                return level;
            }
        }
        return null;
    }

    static int role(MinecraftServer server, ServerPlayer player) {
        return Scores.get(server, player.getScoreboardName(), "sb.role", 0);
    }

    /** Alle lebenden Spieler mit Rolle (1 = Oberwelt, 2 = Nether). */
    static List<ServerPlayer> rolePlayers(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int role = role(server, player);
            if ((role == 1 || role == 2) && player.isAlive() && !player.isSpectator()) {
                players.add(player);
            }
        }
        return players;
    }

    static void broadcast(MinecraftServer server, String text) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    static void title(MinecraftServer server, String title, String color, String subtitle) {
        cmd(server, "title @a times 5 50 15");
        cmd(server, "title @a subtitle {text:\"" + subtitle + "\",color:\"gray\"}");
        cmd(server, "title @a title {text:\"" + title + "\",color:\"" + color + "\",bold:true}");
    }

    static void sound(MinecraftServer server, String sound, double pitch) {
        cmd(server, "execute as @a at @s run playsound " + sound + " master @s ~ ~ ~ 0.8 " + pitch);
    }

    /** Tor einer Rolle: {x der Mitte, y des Sockels, z der Rückseite}. Die Portalfläche liegt bei z + 2. */
    static int[] gate(MinecraftServer server, int role) {
        String p = role == 2 ? "#ngate_" : "#gate_";
        return new int[] {score(server, p + "x"), score(server, p + "y"), score(server, p + "z")};
    }

    private static void loadChunks(ServerLevel level, int x1, int z1, int x2, int z2) {
        for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
            for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
                level.getChunk(cx, cz); // erzeugt/lädt den Chunk sofort
            }
        }
    }

    // ------------------------------------------------------------------ Frisches Gebiet für einen neuen Versuch
    /**
     * Sucht in der Nähe von (x, z) einen trockenen Startplatz an der Oberfläche: {x, y, z}.
     * Liegt dort überall Wasser, entsteht eine kleine Plattform.
     */
    static int[] freshSpot(ServerLevel level, int x, int z) {
        int[][] offsets = {{0, 0}, {64, 0}, {0, 64}, {-64, 0}, {0, -64}, {128, 128}, {-128, 128}, {128, -128}, {-128, -128}};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] first = null;
        for (int[] o : offsets) {
            int px = x + o[0];
            int pz = z + o[1];
            level.getChunk(px >> 4, pz >> 4); // erzeugt/lädt den Chunk sofort
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
            if (first == null) {
                first = new int[] {px, y, pz};
            }
            if (y > 62 && level.getBlockState(pos.set(px, y - 1, pz)).canOcclude()) {
                return new int[] {px, y, pz};
            }
        }
        BlockState ground = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(new BlockPos(first[0] + dx, first[1] - 1, first[2] + dz), ground, FLAGS);
            }
        }
        return first;
    }

    // ------------------------------------------------------------------ Start: beide Tore bauen
    static boolean setup(MinecraftServer server, int anchorX, int anchorZ) {
        ServerLevel over = findLevel(server, OVERWORLD);
        ServerLevel nether = findLevel(server, NETHER);
        if (over == null || nether == null) {
            broadcast(server, "§c✖ Für „Seelenwelten“ werden Oberwelt und Nether gebraucht – der Nether ist auf diesem Server abgeschaltet.");
            return false;
        }
        int gx = Math.floorDiv(anchorX + 96, 8) * 8;
        int gz = Math.floorDiv(anchorZ, 8) * 8;
        int oy = prepareOverworld(over, gx, gz - 2);
        buildGate(over, gx, oy, gz - 2);
        int nx = gx / 8;
        int nz = gz / 8;
        int ny = prepareNether(nether, nx, nz - 2);
        buildGate(nether, nx, ny, nz - 2);
        set(server, "#gate_x", gx);
        set(server, "#gate_y", oy);
        set(server, "#gate_z", gz - 2);
        set(server, "#ngate_x", nx);
        set(server, "#ngate_y", ny);
        set(server, "#ngate_z", nz - 2);
        set(server, "#seals_ow", 0);
        set(server, "#seals_ne", 0);
        set(server, "#rift_open", 0);
        set(server, "#rift_phase", 1);
        RiftBoss.prepare(server); // Reste eines früheren Versuchs (Boss, Bossleiste) wegräumen
        countdown = 0;
        returnTimer = 0;
        return true;
    }

    private static int prepareOverworld(ServerLevel level, int cx, int z0) {
        loadChunks(level, cx - 14, z0 - 5, cx + 14, z0 + 12);
        int sum = 0;
        int[][] samples = {{0, 2}, {-8, 0}, {8, 0}, {-8, 6}, {8, 6}, {0, 8}};
        for (int[] s : samples) {
            sum += level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + s[0], z0 + s[1]);
        }
        int y0 = Math.round((float) sum / samples.length);
        y0 = Math.max(level.getMinY() + 16, Math.min(y0, 250));
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.BLACKSTONE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -13; x <= 13; x++) {
            for (int z = -4; z <= 11; z++) {
                for (int y = 0; y <= 40; y++) {
                    if (!level.getBlockState(pos.set(cx + x, y0 + y, z0 + z)).isAir()) {
                        level.setBlock(new BlockPos(cx + x, y0 + y, z0 + z), air, FLAGS);
                    }
                }
                // Fundament: Lücken unter dem Tor auffüllen (Hang, Wasser, Schlucht)
                for (int d = 1; d <= 14; d++) {
                    if (level.getBlockState(pos.set(cx + x, y0 - d, z0 + z)).canOcclude()) {
                        break;
                    }
                    level.setBlock(new BlockPos(cx + x, y0 - d, z0 + z), stone, FLAGS);
                }
            }
        }
        return y0;
    }

    private static int prepareNether(ServerLevel level, int cx, int z0) {
        loadChunks(level, cx - 15, z0 - 6, cx + 15, z0 + 13);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y0 = 64;
        for (int y = 90; y >= 36; y--) {
            if (level.getBlockState(pos.set(cx, y, z0 + 6)).canOcclude()
                    && level.getBlockState(pos.set(cx, y + 1, z0 + 6)).isAir()
                    && level.getBlockState(pos.set(cx, y + 2, z0 + 6)).isAir()
                    && level.getBlockState(pos.set(cx, y + 3, z0 + 6)).isAir()) {
                y0 = y + 1;
                break;
            }
        }
        y0 = Math.min(y0, 78);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState floor = Blocks.BLACKSTONE.defaultBlockState();
        BlockState rack = Blocks.NETHERRACK.defaultBlockState();
        // eine Halle für das Tor aus dem Fels schneiden; Lava in den Wänden wird zugemauert
        for (int x = -14; x <= 14; x++) {
            for (int z = -5; z <= 12; z++) {
                for (int y = -1; y <= 40; y++) {
                    boolean shell = Math.abs(x) == 14 || z == -5 || z == 12 || y == 40;
                    BlockState state = level.getBlockState(pos.set(cx + x, y0 + y, z0 + z));
                    BlockPos target = new BlockPos(cx + x, y0 + y, z0 + z);
                    if (y == -1) {
                        if (!state.canOcclude()) {
                            level.setBlock(target, floor, FLAGS);
                        }
                    } else if (shell) {
                        if (state.getBlock() == Blocks.LAVA) {
                            level.setBlock(target, rack, FLAGS);
                        }
                    } else if (!state.isAir()) {
                        level.setBlock(target, air, FLAGS);
                    }
                }
            }
        }
        return y0;
    }

    // ------------------------------------------------------------------ Das Tor (ca. 21 breit, 36 hoch)
    private static int halfWidth(int y) {
        if (y == 2) {
            return 2;
        }
        if (y <= 15) {
            return 3;
        }
        if (y <= 17) {
            return 2;
        }
        if (y <= 19) {
            return 1;
        }
        return y == 20 ? 0 : -1;
    }

    private static boolean inside(int x, int y) {
        return y >= 2 && y <= 20 && Math.abs(x) <= halfWidth(y);
    }

    private static void buildGate(ServerLevel level, int cx, int y0, int z0) {
        BlockState brick = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState black = Blocks.BLACKSTONE.defaultBlockState();
        BlockState tile = Blocks.DEEPSLATE_TILES.defaultBlockState();
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState crying = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        BlockState amethyst = Blocks.AMETHYST_BLOCK.defaultBlockState();
        BlockState window = Blocks.SHROOMLIGHT.defaultBlockState();
        BlockState lantern = Blocks.LANTERN.defaultBlockState();
        Placer s = (x, y, z, state) -> level.setBlock(new BlockPos(cx + x, y0 + y, z0 + z), state, FLAGS);
        // Sockel und Stufe
        for (int x = -10; x <= 10; x++) {
            for (int z = -1; z <= 5; z++) {
                s.put(x, 0, z, brick);
                s.put(x, 1, z, z == -1 || z == 5 || Math.abs(x) == 10 ? tile : brick);
            }
        }
        for (int x = -5; x <= 5; x++) {
            s.put(x, 0, 6, brick);
        }
        // Mittelwand mit Rahmen
        for (int y = 2; y <= 22; y++) {
            for (int x = -4; x <= 4; x++) {
                if (inside(x, y)) {
                    continue;
                }
                boolean near = false;
                for (int a = -1; a <= 1; a++) {
                    for (int b = -1; b <= 1; b++) {
                        near |= inside(x + a, y + b);
                    }
                }
                for (int z = 1; z <= 3; z++) {
                    s.put(x, y, z, !near ? brick : RiftIsland.hash(x, y, z + 40) < 0.18 ? crying : obsidian);
                }
            }
        }
        // Giebel mit Rosette
        int[] gable = {4, 4, 3, 3, 2, 2, 1, 1};
        for (int i = 0; i < gable.length; i++) {
            for (int x = -gable[i]; x <= gable[i]; x++) {
                s.put(x, 23 + i, 1, brick);
                s.put(x, 23 + i, 2, brick);
                s.put(x, 23 + i, 3, black);
            }
        }
        int[][] ring = {{-1, 24}, {1, 24}, {-1, 26}, {1, 26}, {-2, 25}, {2, 25}, {0, 27}, {0, 23}};
        for (int[] r : ring) {
            s.put(r[0], r[1], 3, tile);
        }
        int[][] rose = {{0, 24}, {0, 25}, {0, 26}, {-1, 25}, {1, 25}};
        for (int[] r : rose) {
            s.put(r[0], r[1], 3, amethyst);
        }
        for (int y = 31; y <= 35; y++) {
            s.put(0, y, 2, black);
        }
        s.put(0, 31, 3, amethyst);
        for (int side = -1; side <= 1; side += 2) {
            for (int y = 25; y <= 27; y++) {
                s.put(4 * side, y, 2, black);
            }
            for (int y = 29; y <= 31; y++) {
                s.put(2 * side, y, 2, black);
            }
            // Turm
            for (int x = 5; x <= 7; x++) {
                for (int y = 2; y <= 23; y++) {
                    for (int z = 0; z <= 4; z++) {
                        s.put(x * side, y, z, y == 10 || y == 17 || y == 23 ? tile : (x + y + z) % 5 == 0 ? black : brick);
                    }
                }
                for (int z = 0; z <= 4; z++) {
                    s.put(x * side, 24, z, tile);
                }
            }
            for (int y : new int[] {6, 7, 20, 21}) {
                s.put(6 * side, y, 4, window);
            }
            s.put(6 * side, 13, 4, amethyst);
            s.put(6 * side, 14, 4, amethyst);
            for (int x = 5; x <= 7; x += 2) {
                s.put(x * side, 25, 0, black);
                s.put(x * side, 25, 4, black);
            }
            for (int y = 25; y <= 27; y++) {
                for (int x = 5; x <= 7; x++) {
                    for (int z = 1; z <= 3; z++) {
                        s.put(x * side, y, z, black);
                    }
                }
            }
            s.put(6 * side, 26, 3, amethyst);
            for (int y = 28; y <= 33; y++) {
                s.put(6 * side, y, 2, black);
            }
            // Strebepfeiler und Laternen
            for (int z = 1; z <= 3; z++) {
                for (int y = 2; y <= 12; y++) {
                    s.put(8 * side, y, z, brick);
                }
                for (int y = 2; y <= 5; y++) {
                    s.put(9 * side, y, z, brick);
                }
            }
            for (int y = 13; y <= 15; y++) {
                s.put(8 * side, y, 2, black);
            }
            s.put(8 * side, 16, 2, lantern);
            s.put(9 * side, 6, 2, black);
            s.put(9 * side, 2, 5, lantern);
        }
        setSockets(level, cx, y0, z0, 0);
        setFill(level, cx, y0, z0, false);
    }

    private static void setSockets(ServerLevel level, int cx, int y0, int z0, int seals) {
        for (int i = 0; i < 4; i++) {
            boolean lit = (seals & (1 << i)) != 0;
            level.setBlock(new BlockPos(cx + SOCKETS[i][0], y0 + SOCKETS[i][1], z0 + 3),
                    (lit ? Blocks.PEARLESCENT_FROGLIGHT : Blocks.CHISELED_POLISHED_BLACKSTONE).defaultBlockState(), FLAGS);
        }
    }

    private static void setFill(ServerLevel level, int cx, int y0, int z0, boolean open) {
        BlockState fill = (open ? ModBlocks.RIFT_PORTAL : Blocks.TINTED_GLASS).defaultBlockState();
        for (int y = 2; y <= 20; y++) {
            for (int x = -3; x <= 3; x++) {
                if (inside(x, y)) {
                    level.setBlock(new BlockPos(cx + x, y0 + y, z0 + 2), fill, FLAGS);
                }
            }
        }
        level.setBlock(new BlockPos(cx, y0 + 25, z0 + 3),
                (open ? Blocks.PEARLESCENT_FROGLIGHT : Blocks.AMETHYST_BLOCK).defaultBlockState(), FLAGS);
    }

    private interface Placer {
        void put(int x, int y, int z, BlockState state);
    }

    // ------------------------------------------------------------------ Siegel einsetzen
    static void useSeal(MinecraftServer server, ServerPlayer player, ItemStack stack, int index) {
        int role = role(server, player);
        if (score(server, "#rift_phase") != 1 || (role != 1 && role != 2)) {
            player.sendSystemMessage(Component.literal("§7Das Siegel reagiert nicht. Es gehört in ein Tor der Challenge „Seelenwelten“."));
            return;
        }
        int[] g = gate(server, role);
        boolean rightWorld = player.level().dimension().toString().contains(role == 1 ? OVERWORLD : NETHER);
        if (!rightWorld || Math.abs(player.getX() - g[0]) > 16 || Math.abs(player.getZ() - g[2]) > 16
                || Math.abs(player.getY() - g[1]) > 45) {
            player.sendSystemMessage(Component.literal("§7Geh näher an dein Tor (" + g[0] + " / " + g[1] + " / " + (g[2] + 2)
                    + "), dann setzt ein Rechtsklick das Siegel ein."));
            return;
        }
        String holder = role == 1 ? "#seals_ow" : "#seals_ne";
        int seals = score(server, holder);
        if ((seals & (1 << index)) != 0) {
            player.sendSystemMessage(Component.literal("§7Das " + ModItems.SEAL_NAMES[index] + " sitzt in diesem Tor schon. Dein Partner braucht es vielleicht noch."));
            return;
        }
        seals |= 1 << index;
        stack.shrink(1);
        set(server, holder, seals);
        setSockets((ServerLevel) player.level(), g[0], g[1], g[2], seals);
        cmd(server, "execute at " + player.getScoreboardName() + " run playsound minecraft:block.end_portal_frame.fill master @a ~ ~ ~ 1 1");
        broadcast(server, "§d✦ " + player.getScoreboardName() + " hat das " + ModItems.SEAL_NAMES[index] + " eingesetzt §7("
                + (role == 1 ? "Oberwelt-Tor " : "Nether-Tor ") + Integer.bitCount(seals) + "/4)");
    }

    static String sealStatus(MinecraftServer server) {
        return "Oberwelt-Tor " + Integer.bitCount(score(server, "#seals_ow")) + "/4, Nether-Tor "
                + Integer.bitCount(score(server, "#seals_ne")) + "/4";
    }

    /** Zum Ausprobieren: setzt alle Siegel in beide Tore ein. */
    static void testOpen(MinecraftServer server) {
        if (score(server, "#rift_phase") != 1) {
            return;
        }
        set(server, "#seals_ow", 15);
        set(server, "#seals_ne", 15);
        ServerLevel over = findLevel(server, OVERWORLD);
        ServerLevel nether = findLevel(server, NETHER);
        int[] a = gate(server, 1);
        int[] b = gate(server, 2);
        if (over != null) {
            setSockets(over, a[0], a[1], a[2], 15);
        }
        if (nether != null) {
            setSockets(nether, b[0], b[1], b[2], 15);
        }
    }

    // ------------------------------------------------------------------ Tick
    static void tick(MinecraftServer server) {
        clock++;
        int phase = score(server, "#rift_phase");
        if (phase == 1) {
            if (clock % 20 == 0) {
                keepWorlds(server);
            }
            if (clock % 2 == 0) {
                checkEntry(server);
            }
        } else if (phase == 2 || phase == 3) {
            RiftBoss.tick(server, phase);
        }
    }

    /** Läuft auch nach dem Sieg weiter: bringt alle nach kurzer Zeit zurück in die Oberwelt. */
    static void tickAlways(MinecraftServer server) {
        if (returnTimer > 0 && --returnTimer == 0) {
            int[] g = gate(server, 1);
            cmd(server, "execute in " + OVERWORLD + " run tp @a " + (g[0] + 0.5) + " " + g[1] + " " + (g[2] + 9.5) + " 180 0");
            broadcast(server, "§d☯ Der Riss schließt sich. Ihr steht wieder vor dem Tor in der Oberwelt – diesmal zusammen.");
        }
    }

    static void scheduleReturn(int ticks) {
        returnTimer = ticks;
    }

    private static void tpFront(MinecraftServer server, ServerPlayer player, int role) {
        int[] g = gate(server, role);
        cmd(server, "execute in " + (role == 1 ? OVERWORLD : NETHER) + " run tp " + player.getScoreboardName() + " "
                + (g[0] + 0.5) + " " + g[1] + " " + (g[2] + 9.5) + " 180 0");
    }

    /** Jeder bleibt in seiner Welt: Wer in der falschen landet, kommt zurück vor sein Tor. */
    private static void keepWorlds(MinecraftServer server) {
        for (ServerPlayer player : rolePlayers(server)) {
            int role = role(server, player);
            String dimension = player.level().dimension().toString();
            if ((role == 1 && dimension.contains(NETHER)) || (role == 2 && dimension.contains(OVERWORLD))) {
                tpFront(server, player, role);
                player.sendSystemMessage(Component.literal("§7Diese Welt gehört deinem Partner. Du bist zurück vor deinem Tor."));
            }
        }
    }

    private static boolean inZone(MinecraftServer server, ServerPlayer player, int role) {
        int[] g = gate(server, role);
        if (!player.level().dimension().toString().contains(role == 1 ? OVERWORLD : NETHER)) {
            return false;
        }
        int x = (int) Math.floor(player.getX()) - g[0];
        int y = (int) Math.floor(player.getY()) - g[1];
        int z = (int) Math.floor(player.getZ()) - g[2];
        return Math.abs(x) <= 3 && y >= 2 && y <= 5 && z >= 1 && z <= 4;
    }

    private static void checkEntry(MinecraftServer server) {
        if (score(server, "#seals_ow") != 15 || score(server, "#seals_ne") != 15) {
            countdown = 0;
            return;
        }
        if (score(server, "#rift_open") != 1) {
            openGates(server);
        }
        List<ServerPlayer> players = rolePlayers(server);
        boolean all = !players.isEmpty();
        boolean has1 = false;
        boolean has2 = false;
        for (ServerPlayer player : players) {
            int role = role(server, player);
            has1 |= role == 1;
            has2 |= role == 2;
            all &= inZone(server, player, role);
        }
        if (!all || !(players.size() == 1 || (has1 && has2))) {
            if (countdown > 0) {
                countdown = 0;
                cmd(server, "title @a actionbar {text:\"Das Tor wartet – ihr müsst gleichzeitig darin stehen.\",color:\"gray\"}");
            }
            return;
        }
        if (countdown == 0) {
            title(server, "3", "light_purple", "Bleibt im Tor stehen");
            sound(server, "minecraft:block.note_block.pling", 0.8);
        } else if (countdown == 20) {
            title(server, "2", "light_purple", "Bleibt im Tor stehen");
            sound(server, "minecraft:block.note_block.pling", 1.0);
        } else if (countdown == 40) {
            title(server, "1", "light_purple", "Bleibt im Tor stehen");
            sound(server, "minecraft:block.note_block.pling", 1.2);
        }
        countdown += 2;
        if (countdown >= 60) {
            enter(server);
        }
    }

    private static void openGates(MinecraftServer server) {
        ServerLevel over = findLevel(server, OVERWORLD);
        ServerLevel nether = findLevel(server, NETHER);
        int[] a = gate(server, 1);
        int[] b = gate(server, 2);
        if (over != null) {
            setFill(over, a[0], a[1], a[2], true);
        }
        if (nether != null) {
            setFill(nether, b[0], b[1], b[2], true);
        }
        set(server, "#rift_open", 1);
        title(server, "Die Tore sind offen", "light_purple", "Tretet gleichzeitig hinein");
        sound(server, "minecraft:block.end_portal.spawn", 1.0);
        broadcast(server, "§d✦ Alle acht Siegel sitzen. Beide Tore sind offen – stellt euch gleichzeitig in euer Tor und bleibt drei Sekunden stehen.");
    }

    // ------------------------------------------------------------------ Betreten der Insel
    static void enter(MinecraftServer server) {
        countdown = 0;
        ServerLevel arena = findLevel(server, ARENA);
        String dimension = ARENA;
        int cx = 0;
        int top = 100;
        int cz = 0;
        if (arena == null) {
            // Ersatz, falls die eigene Dimension fehlt: die Insel schwebt hoch über der Oberwelt
            arena = findLevel(server, OVERWORLD);
            dimension = OVERWORLD;
            cx = score(server, "#gate_x");
            cz = score(server, "#gate_z") + 600;
            top = 240;
        }
        if (arena == null) {
            return;
        }
        set(server, "#arena_dim", dimension.equals(ARENA) ? 0 : 1);
        set(server, "#arena_x", cx);
        set(server, "#arena_y", top);
        set(server, "#arena_z", cz);
        loadChunks(arena, cx - 72, cz - 72, cx + 72, cz + 72);
        if (score(server, "#riss_built") != 1) {
            RiftIsland.generate(arena, cx, top, cz);
            set(server, "#riss_built", 1);
        }
        RiftBoss.prepare(server);
        RiftBoss.resetCracks(server, cx, top, cz); // falls ein früherer Kampf mitten im Leuchten endete
        int i = 0;
        for (ServerPlayer player : rolePlayers(server)) {
            int px = cx + (i % 2 == 0 ? -2 : 2);
            int pz = cz + 27 + (i / 2) * 2;
            int py = arena.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz);
            if (py < top - 3 || py > top + 8) {
                py = top;
            }
            cmd(server, "execute in " + dimension + " run tp " + player.getScoreboardName() + " " + (px + 0.5) + " " + py + " "
                    + (pz + 0.5) + " 180 0");
            i++;
        }
        cmd(server, "effect give @a minecraft:slow_falling 4 0 true");
        cmd(server, "effect give @a minecraft:resistance 6 4 true");
        set(server, "#rift_phase", 2);
        title(server, "DER RISS", "light_purple", "Geht zur Blüte in der Mitte");
        sound(server, "minecraft:block.end_portal.spawn", 0.7);
        broadcast(server, "§d☯ Ihr steht zum ersten Mal nebeneinander. §7Geht zur Blüte in der Mitte – dort wartet der Zerrissene.");
    }
}
