package de.challengemod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ziel 6 „Seelenwelten“: Die Spieler starten getrennt im Himmelsreich (Rolle 1) und im Tiefenreich (Rolle 2).
 * Die beiden Dimensionen kommen aus dem Zusatz-Datapack (challenge:himmel, challenge:tiefe).
 *  - Start: sicheren Platz suchen bzw. bauen, hinteleportieren, Startpaket geben
 *  - Seelentruhe: Die Endertruhen-Inventare aller Spieler werden gleich gehalten (gemeinsames Lager)
 *  - Seelenstein: öffnet die Seelentruhe überall; Schleichen + Rechtsklick zeigt den Zustand der anderen
 */
final class SoulWorlds {
    private static final int FLAGS = 2 | 16 | 32 | 512;
    private static List<ItemStack> shared;
    private static boolean errorLogged;

    private SoulWorlds() {
    }

    static void reset() {
        shared = null;
    }

    private static boolean active(MinecraftServer server) {
        return Scores.get(server, "#state", "sb", 4) == 1 && Scores.get(server, "#goal", "sb.cfg", 1) == 6;
    }

    static void tick(MinecraftServer server) {
        try {
            if (!active(server)) {
                shared = null;
                return;
            }
            if (Scores.get(server, "#sw_start", "sb", 0) == 1) {
                ChallengeMod.runAsServer(server, "scoreboard players set #sw_start sb 0");
                setup(server);
            }
            syncChests(server);
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    // ------------------------------------------------------------------ Start
    private static ServerLevel findLevel(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().toString().contains(id)) {
                return level;
            }
        }
        return null;
    }

    private static void setup(MinecraftServer server) {
        ServerLevel sky = findLevel(server, "challenge:himmel");
        ServerLevel deep = findLevel(server, "challenge:tiefe");
        if (sky == null || deep == null) {
            ChallengeMod.runAsServer(server, "function " + ChallengeMod.NS + ":welten/missing");
            return;
        }
        BlockPos skySpot = findSkySpot(sky);
        BlockPos deepSpot = findDeepSpot(deep);
        shared = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            shared.add(ItemStack.EMPTY);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getScoreboardName();
            int role = Scores.get(server, name, "sb.role", 0);
            if (role != 1 && role != 2) {
                continue;
            }
            var chest = player.getEnderChestInventory();
            for (int i = 0; i < chest.getContainerSize(); i++) {
                chest.setItem(i, ItemStack.EMPTY);
            }
            BlockPos spot = role == 1 ? skySpot : deepSpot;
            String dimension = role == 1 ? "challenge:himmel" : "challenge:tiefe";
            ChallengeMod.runAsServer(server, "execute in " + dimension + " run tp " + name + " "
                    + (spot.getX() + 0.5) + " " + spot.getY() + " " + (spot.getZ() + 0.5));
            ChallengeMod.runAsServer(server, "give " + name + " " + ChallengeMod.NS + ":soul_stone");
            if (role == 2) {
                ChallengeMod.runAsServer(server, "give " + name + " minecraft:wooden_pickaxe");
                ChallengeMod.runAsServer(server, "give " + name + " minecraft:torch 16");
                ChallengeMod.runAsServer(server, "give " + name + " minecraft:bread 8");
            }
        }
    }

    /** Himmelsreich: nächstgelegene Insel-Oberfläche um den Nullpunkt, sonst eine kleine Startinsel bauen. */
    private static BlockPos findSkySpot(ServerLevel level) {
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                level.getChunk(cx, cz); // erzeugt/lädt den Chunk sofort
            }
        }
        int minY = level.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = -14; x <= 30; x++) {
            for (int z = -14; z <= 30; z++) {
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (top <= minY + 8) {
                    continue; // hier ist nur Leere
                }
                int distance = (x - 8) * (x - 8) + (z - 8) * (z - 8);
                if (distance < bestDistance && level.getBlockState(pos.set(x, top - 1, z)).canOcclude()) {
                    bestDistance = distance;
                    best = new BlockPos(x, top, z);
                }
            }
        }
        if (best != null) {
            return best;
        }
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int x = 5; x <= 11; x++) {
            for (int z = 5; z <= 11; z++) {
                level.setBlock(new BlockPos(x, 127, z), dirt, FLAGS);
                level.setBlock(new BlockPos(x, 128, z), grass, FLAGS);
            }
        }
        return new BlockPos(8, 129, 8);
    }

    /** Tiefenreich: ein Höhlenboden mit Platz zum Stehen, sonst einen kleinen Raum in den Stein hauen. */
    private static BlockPos findDeepSpot(ServerLevel level) {
        level.getChunk(0, 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 70; y >= -40; y--) {
            for (int x = 3; x <= 12; x++) {
                for (int z = 3; z <= 12; z++) {
                    if (level.getBlockState(pos.set(x, y, z)).canOcclude()
                            && level.getBlockState(pos.set(x, y + 1, z)).isAir()
                            && level.getBlockState(pos.set(x, y + 2, z)).isAir()
                            && level.getBlockState(pos.set(x, y + 3, z)).isAir()) {
                        return new BlockPos(x, y + 1, z);
                    }
                }
            }
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                level.setBlock(new BlockPos(x, 39, z), Blocks.DEEPSLATE.defaultBlockState(), FLAGS);
                for (int y = 40; y <= 42; y++) {
                    level.setBlock(new BlockPos(x, y, z), air, FLAGS);
                }
            }
        }
        return new BlockPos(8, 40, 8);
    }

    // ------------------------------------------------------------------ Seelentruhe
    private static void syncChests(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int role = Scores.get(server, player.getScoreboardName(), "sb.role", 0);
            if (role == 1 || role == 2) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        if (shared == null) {
            // nach einem Neustart: Stand vom ersten Spieler übernehmen
            shared = new ArrayList<>();
            var chest = players.get(0).getEnderChestInventory();
            for (int i = 0; i < 27; i++) {
                shared.add(chest.getItem(i).copy());
            }
        }
        for (ServerPlayer changer : players) {
            var chest = changer.getEnderChestInventory();
            boolean changed = false;
            for (int i = 0; i < 27; i++) {
                if (!ItemStack.matches(chest.getItem(i), shared.get(i))) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                continue;
            }
            for (int i = 0; i < 27; i++) {
                shared.set(i, chest.getItem(i).copy());
            }
            for (ServerPlayer other : players) {
                if (other != changer) {
                    var target = other.getEnderChestInventory();
                    for (int i = 0; i < 27; i++) {
                        target.setItem(i, shared.get(i).copy());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Seelenstein
    static InteractionResult useItem(Player player, Level level, InteractionHand hand) {
        try {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(ModItems.SOUL_STONE) || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            MinecraftServer server = serverPlayer.level().getServer();
            if (server == null) {
                return InteractionResult.PASS;
            }
            if (serverPlayer.isShiftKeyDown()) {
                boolean any = false;
                for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                    if (other == serverPlayer) {
                        continue;
                    }
                    any = true;
                    String where = other.level().dimension().toString();
                    String world = where.contains("himmel") ? "Himmelsreich" : where.contains("tiefe") ? "Tiefenreich"
                            : where.contains("thron") ? "Thron der Leere" : "andere Welt";
                    serverPlayer.sendSystemMessage(Component.literal("☯ " + other.getScoreboardName() + ": "
                            + (int) Math.ceil(other.getHealth() / 2.0) + " Herzen, " + world + " bei "
                            + (int) other.getX() + " / " + (int) other.getY() + " / " + (int) other.getZ()));
                }
                if (!any) {
                    serverPlayer.sendSystemMessage(Component.literal("☯ Der Seelenstein bleibt still – niemand sonst ist da."));
                }
            } else {
                serverPlayer.openMenu(new SimpleMenuProvider(
                        (id, inventory, opener) -> ChestMenu.threeRows(id, inventory, opener.getEnderChestInventory()),
                        Component.literal("Seelentruhe")));
            }
            return InteractionResult.SUCCESS;
        } catch (Throwable t) {
            logOnce(t);
            return InteractionResult.PASS;
        }
    }

    private static void logOnce(Throwable t) {
        if (!errorLogged) {
            errorLogged = true;
            ChallengeMod.LOGGER.error("Seelenwelten sind ausgefallen (wird nur einmal gemeldet)", t);
        }
    }
}
