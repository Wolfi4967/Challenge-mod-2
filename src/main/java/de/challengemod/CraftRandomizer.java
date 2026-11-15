package de.challengemod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Crafting-Randomizer (#m_craft): Jedes Rezept ergibt ein anderes, festes Item.
 * Jeden Tick wird das Ergebnisfeld der Werkbank bzw. des 2x2-Felds im Inventar geprüft und,
 * falls dort ein frisches Ergebnis liegt, gegen das zugeordnete Item getauscht.
 */
final class CraftRandomizer {
    /** Das zuletzt von uns hingelegte Ergebnis pro Spieler – damit es nicht noch einmal getauscht wird. */
    private static final Map<UUID, ItemStack> placed = new HashMap<>();
    private static boolean errorLogged;

    private CraftRandomizer() {
    }

    static void reset() {
        placed.clear();
    }

    static void tick(MinecraftServer server) {
        try {
            // auch während der Pause aktiv, sonst könnte man in der Pause normal craften
            if (Scores.get(server, "#state", "sb", 4) != 1 || Scores.get(server, "#m_craft", "sb.cfg", 0) != 1) {
                if (!placed.isEmpty()) {
                    placed.clear();
                }
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                AbstractContainerMenu menu = player.containerMenu;
                if (!(menu instanceof CraftingMenu) && !(menu instanceof InventoryMenu)) {
                    continue;
                }
                Slot result = menu.getSlot(0); // Ergebnisfeld ist in beiden Menüs Feld 0
                ItemStack stack = result.getItem();
                UUID id = player.getUUID();
                if (stack.isEmpty()) {
                    placed.remove(id);
                    continue;
                }
                if (placed.get(id) == stack) {
                    continue; // liegt schon unser getauschtes Item
                }
                Item target = BlockRandomizer.craftItem(server, stack.getItem());
                if (target == stack.getItem()) {
                    placed.put(id, stack);
                    continue;
                }
                int count = Math.max(1, Math.min(stack.getCount(), new ItemStack(target).getMaxStackSize()));
                ItemStack replacement = new ItemStack(target, count);
                result.set(replacement);
                placed.put(id, replacement);
            }
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Crafting-Randomizer ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }
}
