package de.challengemod;

import java.util.ArrayList;
import java.util.List;
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

/**
 * Ziel 6 „Seelenwelten“: Ein Spieler lebt in der Oberwelt (Rolle 1), der andere im Nether (Rolle 2).
 *  - Start: beide Tore bauen (Rift), den Nether-Spieler vor sein Tor bringen, Startpaket geben
 *  - Seelentruhe: Die Endertruhen-Inventare aller Spieler werden gleich gehalten (gemeinsames Lager)
 *  - Seelenstein: öffnet die Seelentruhe überall; Schleichen + Rechtsklick zeigt den Partner, das Tor und die Siegel
 *  - Siegel: Rechtsklick in der Nähe des eigenen Tors setzt sie ein (Rift.useSeal)
 */
final class SoulWorlds {
    private static List<ItemStack> shared;
    private static boolean errorLogged;

    private SoulWorlds() {
    }

    static void reset() {
        shared = null;
        Rift.reset();
        RiftBoss.reset();
    }

    private static boolean active(MinecraftServer server) {
        return Scores.get(server, "#state", "sb", 4) == 1 && Scores.get(server, "#goal", "sb.cfg", 1) == 6;
    }

    static void tick(MinecraftServer server) {
        try {
            Rift.tickAlways(server);
            if (!active(server)) {
                shared = null;
                RiftBoss.hideBarIfShown(server);
                return;
            }
            if (Scores.get(server, "#sw_start", "sb", 0) == 1) {
                ChallengeMod.runAsServer(server, "scoreboard players set #sw_start sb 0");
                setup(server);
            }
            syncChests(server);
            if (Scores.get(server, "#paused", "sb", 0) == 0) {
                Rift.tick(server);
            }
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    // ------------------------------------------------------------------ Start
    private static void setup(MinecraftServer server) {
        // Jeder Versuch nach dem ersten startet 100.000 Blöcke weiter östlich: unberührte Oberwelt,
        // und der Nether dazu liegt 12.500 Blöcke weiter – ebenfalls unberührt.
        int run = Scores.get(server, "#sw_run", "sb", 0);
        ChallengeMod.runAsServer(server, "scoreboard players set #sw_run sb " + (run + 1));
        int anchorX = 0;
        int anchorZ = 0;
        int[] fresh = null;
        ServerLevel overworld = Rift.findLevel(server, Rift.OVERWORLD);
        if (run > 0 && overworld != null) {
            fresh = Rift.freshSpot(overworld, (run % 250) * 100000, 0);
            anchorX = fresh[0];
            anchorZ = fresh[2];
            ChallengeMod.runAsServer(server, "execute in " + Rift.OVERWORLD + " run setworldspawn " + fresh[0] + " " + fresh[1] + " " + fresh[2]);
        } else {
            // erster Versuch: Das Oberwelt-Tor entsteht rund 100 Blöcke östlich vom Oberwelt-Spieler
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (Scores.get(server, player.getScoreboardName(), "sb.role", 0) == 1
                        && player.level().dimension().toString().contains(Rift.OVERWORLD)) {
                    anchorX = (int) Math.floor(player.getX());
                    anchorZ = (int) Math.floor(player.getZ());
                    break;
                }
            }
        }
        if (!Rift.setup(server, anchorX, anchorZ)) {
            return;
        }
        if (fresh != null) {
            Rift.broadcast(server, "§d☯ Versuch " + (run + 1) + ": §7Ihr startet in einem unberührten Gebiet von Oberwelt und Nether, weit weg vom letzten Versuch.");
        }
        int[] over = Rift.gate(server, 1);
        int[] nether = Rift.gate(server, 2);
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
            ChallengeMod.runAsServer(server, "give " + name + " " + ChallengeMod.NS + ":soul_stone");
            for (String seal : ModItems.SEAL_IDS) {
                ChallengeMod.runAsServer(server, "recipe give " + name + " " + ChallengeMod.NS + ":" + seal);
            }
            if (role == 2) {
                ChallengeMod.runAsServer(server, "execute in " + Rift.NETHER + " run tp " + name + " " + (nether[0] + 0.5) + " "
                        + nether[1] + " " + (nether[2] + 9.5) + " 180 0");
                ChallengeMod.runAsServer(server, "execute in " + Rift.NETHER + " run spawnpoint " + name + " " + nether[0] + " "
                        + nether[1] + " " + (nether[2] + 9));
                ChallengeMod.runAsServer(server, "give " + name + " minecraft:wooden_pickaxe");
                ChallengeMod.runAsServer(server, "give " + name + " minecraft:bread 8");
                player.sendSystemMessage(Component.literal("§c☯ Du lebst im Nether. §7Dein Tor steht direkt vor dir ("
                        + nether[0] + " / " + nether[1] + " / " + (nether[2] + 2) + ")."));
            } else {
                if (fresh != null) {
                    ChallengeMod.runAsServer(server, "execute in " + Rift.OVERWORLD + " run tp " + name + " " + (fresh[0] + 0.5) + " "
                            + fresh[1] + " " + (fresh[2] + 0.5));
                    ChallengeMod.runAsServer(server, "execute in " + Rift.OVERWORLD + " run spawnpoint " + name + " " + fresh[0] + " "
                            + fresh[1] + " " + fresh[2]);
                }
                player.sendSystemMessage(Component.literal("§b☯ Du lebst in der Oberwelt. §7Dein Tor steht bei "
                        + over[0] + " / " + over[1] + " / " + (over[2] + 2) + " (rund 100 Blöcke östlich)."));
            }
        }
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
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            MinecraftServer server = serverPlayer.level().getServer();
            if (server == null) {
                return InteractionResult.PASS;
            }
            for (int i = 0; i < ModItems.SEALS.length; i++) {
                if (stack.is(ModItems.SEALS[i])) {
                    Rift.useSeal(server, serverPlayer, stack, i);
                    return InteractionResult.SUCCESS;
                }
            }
            if (!stack.is(ModItems.SOUL_STONE)) {
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
                    String world = where.contains("the_nether") ? "Nether" : where.contains("overworld") ? "Oberwelt"
                            : where.contains("riss") ? "Der Riss" : "andere Welt";
                    serverPlayer.sendSystemMessage(Component.literal("☯ " + other.getScoreboardName() + ": "
                            + (int) Math.ceil(other.getHealth() / 2.0) + " Herzen, " + world + " bei "
                            + (int) other.getX() + " / " + (int) other.getY() + " / " + (int) other.getZ()));
                }
                if (!any) {
                    serverPlayer.sendSystemMessage(Component.literal("☯ Der Seelenstein bleibt still – niemand sonst ist da."));
                }
                int role = Scores.get(server, serverPlayer.getScoreboardName(), "sb.role", 0);
                if (Rift.score(server, "#rift_phase") == 1 && (role == 1 || role == 2)) {
                    int[] g = Rift.gate(server, role);
                    int distance = (int) Math.hypot(serverPlayer.getX() - g[0], serverPlayer.getZ() - (g[2] + 2));
                    serverPlayer.sendSystemMessage(Component.literal("§d✦ Dein Tor: " + g[0] + " / " + g[1] + " / " + (g[2] + 2)
                            + " §7(" + distance + " Blöcke entfernt)"));
                    serverPlayer.sendSystemMessage(Component.literal("§d✦ Siegel: §7" + Rift.sealStatus(server)));
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
