package de.challengemod;

import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Eigene Items des Challenge-Mods. Weil es eigene Items gibt, brauchen ALLE Spieler den Mod. */
final class ModItems {
    /** Seelenstein: Rechtsklick öffnet die gemeinsame Seelentruhe, Schleichen + Rechtsklick zeigt den Partner. */
    static final Item SOUL_STONE = register("soul_stone", Item::new, new Item.Properties().stacksTo(1));

    /** Die vier Siegel für das Tor zum Riss (Ziel „Seelenwelten“). Jedes braucht Zutaten aus beiden Welten. */
    static final Item SEAL_EMBER = register("seal_ember", Item::new, new Item.Properties().stacksTo(1));
    static final Item SEAL_DEEP = register("seal_deep", Item::new, new Item.Properties().stacksTo(1));
    static final Item SEAL_VOID = register("seal_void", Item::new, new Item.Properties().stacksTo(1));
    static final Item SEAL_SOUL = register("seal_soul", Item::new, new Item.Properties().stacksTo(1));
    static final Item[] SEALS = {SEAL_EMBER, SEAL_DEEP, SEAL_VOID, SEAL_SOUL};
    static final String[] SEAL_NAMES = {"Siegel der Glut", "Siegel der Tiefe", "Siegel der Leere", "Siegel der Seele"};
    static final String[] SEAL_IDS = {"seal_ember", "seal_deep", "seal_void", "seal_soul"};

    private ModItems() {
    }

    private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ChallengeMod.NS, name));
        Item item = factory.apply(properties.setId(key));
        Registry.register(BuiltInRegistries.ITEM, key, item);
        return item;
    }

    /** Sorgt dafür, dass die Klasse (und damit die Registrierung) beim Mod-Start geladen wird. */
    static void initialize() {
    }
}
