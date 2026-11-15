package de.challengemod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Eigene Blöcke des Challenge-Mods. */
final class ModBlocks {
    /** Die leuchtende Fläche im Tor zum Riss. Unzerstörbar, leuchtet, man kann hindurchgehen. */
    static final Block RIFT_PORTAL = register("rift_portal");

    private ModBlocks() {
    }

    private static Block register(String name) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ChallengeMod.NS, name));
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().setId(key)
                .strength(-1.0F, 3600000.0F).lightLevel(state -> 12).noOcclusion();
        properties = withoutCollision(properties);
        Block block = new Block(properties);
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        return block;
    }

    /**
     * „Keine Kollision“ heißt je nach Spielversion noCollision oder (alter Tippfehler) noCollission.
     * Deshalb wird die Methode zur Laufzeit gesucht; gibt es keine, bleibt der Block eben fest.
     */
    private static BlockBehaviour.Properties withoutCollision(BlockBehaviour.Properties properties) {
        for (String method : new String[] {"noCollision", "noCollission"}) {
            try {
                Object result = properties.getClass().getMethod(method).invoke(properties);
                if (result instanceof BlockBehaviour.Properties changed) {
                    return changed;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // nächste Schreibweise versuchen
            }
        }
        return properties;
    }

    static void initialize() {
    }
}
