package de.challengemod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Block-Randomizer: Jeder Blocktyp droppt ein festes, anderes Item. Die Zuordnung hängt vom Welt-Seed und
 * einer beim Start gewürfelten Variante ab (#bvar). An/aus: Einstellungen Seite 2 (#m_brand).
 * Häufige Blöcke (Stein, Erde, Holz …) bekommen nur einfache Items, damit es nicht zu leicht wird.
 */
final class BlockRandomizer {
    /** Items, die nie als Drop vorkommen (Creative-only, kaputt oder viel zu stark). */
    private static final Set<String> BANNED = Set.of(
            "air", "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
            "structure_block", "structure_void", "jigsaw", "barrier", "light", "debug_stick", "knowledge_book",
            "bedrock", "spawner", "trial_spawner", "vault", "end_portal_frame", "reinforced_deepslate",
            "petrified_oak_slab", "test_block", "test_instance_block", "dragon_egg", "nether_star", "elytra",
            "totem_of_undying", "enchanted_golden_apple", "beacon", "end_crystal", "budding_amethyst", "frogspawn",
            "farmland", "dirt_path", "written_book", "filled_map", "player_head", "dragon_head", "bundle",
            "ominous_trial_key", "heavy_core", "mace", "wind_charge");
    /** Starke Items: nur auf seltenen Blöcken. */
    private static final Set<String> STRONG = Set.of(
            "diamond", "diamond_pickaxe", "diamond_sword", "diamond_axe", "diamond_shovel", "diamond_hoe",
            "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots", "diamond_block",
            "iron_pickaxe", "iron_sword", "iron_axe", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
            "golden_apple", "ender_eye", "ender_pearl", "blaze_rod", "iron_block", "gold_block", "emerald_block",
            "enchanting_table", "experience_bottle", "trident", "heart_of_the_sea", "obsidian", "crying_obsidian",
            "lava_bucket", "water_bucket", "bucket", "anvil", "shield", "crossbow", "bow", "netherite_scrap",
            "ancient_debris", "emerald", "tnt", "respawn_anchor", "conduit", "shulker_shell", "brewing_stand");
    /** Häufige Blöcke: bekommen nur einfache Items. */
    private static final Set<String> COMMON = Set.of(
            "stone", "deepslate", "dirt", "grass_block", "sand", "red_sand", "gravel", "netherrack", "end_stone",
            "cobblestone", "cobbled_deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "sandstone",
            "red_sandstone", "terracotta", "short_grass", "tall_grass", "fern", "large_fern", "snow", "snow_block",
            "ice", "packed_ice", "clay", "mud", "moss_block", "soul_sand", "soul_soil", "basalt", "blackstone",
            "magma_block", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sugar_cane", "cactus", "podzol",
            "coarse_dirt", "mycelium", "dripstone_block", "pointed_dripstone", "sculk", "glow_lichen", "vine",
            "dead_bush", "crimson_nylium", "warped_nylium", "crimson_stem", "warped_stem", "nether_wart_block",
            "warped_wart_block", "rooted_dirt", "smooth_basalt", "dandelion", "poppy", "leaf_litter", "bush",
            "pink_petals", "wildflowers", "short_dry_grass", "tall_dry_grass", "lily_pad", "bamboo", "coal_ore",
            "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore", "copper_ore", "deepslate_copper_ore",
            "nether_quartz_ore", "nether_gold_ore", "moss_carpet", "cave_vines", "cave_vines_plant", "hanging_roots");
    /** Blöcke mit Inhalt bleiben normal (sonst gehen z. B. Shulker-Inhalte verloren). */
    private static final Set<String> SKIP = Set.of("decorated_pot", "beehive", "bee_nest", "chiseled_bookshelf");

    private static Map<ResourceKey<LootTable>, Block> tableToBlock;
    private static Map<Block, Item> mapping;
    private static long mappingId = Long.MIN_VALUE;
    private static boolean errorLogged;

    private BlockRandomizer() {
    }

    static void reset() {
        mapping = null;
        tableToBlock = null;
        mappingId = Long.MIN_VALUE;
    }

    static void modifyDrops(Holder<LootTable> holder, LootContext context, List<ItemStack> drops) {
        try {
            if (drops.isEmpty()) {
                return;
            }
            MinecraftServer server = context.getLevel().getServer();
            if (!Scores.running(server) || Scores.get(server, "#m_brand", "sb.cfg", 0) != 1) {
                return;
            }
            var key = holder.unwrapKey();
            if (key.isEmpty()) {
                return;
            }
            Block block = tables().get(key.get());
            if (block == null) {
                return; // kein Block-Loot (Truhe, Mob, Angeln …)
            }
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (id.endsWith("shulker_box") || SKIP.contains(id)) {
                return;
            }
            Item target = mapping(server).get(block);
            if (target == null) {
                return;
            }
            int max = new ItemStack(target).getMaxStackSize();
            int count = Math.max(1, Math.min(drops.get(0).getCount(), max));
            drops.clear();
            drops.add(new ItemStack(target, count));
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Block-Randomizer ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }

    private static Map<ResourceKey<LootTable>, Block> tables() {
        if (tableToBlock == null) {
            Map<ResourceKey<LootTable>, Block> map = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                block.getLootTable().ifPresent(k -> map.putIfAbsent(k, block));
            }
            tableToBlock = map;
        }
        return tableToBlock;
    }

    private static Map<Block, Item> mapping(MinecraftServer server) {
        int variant = Scores.get(server, "#bvar", "sb", 1);
        long seed = server.overworld().getSeed() * 31L + variant * 0x9E3779B97F4A7C15L;
        if (mapping != null && mappingId == seed) {
            return mapping;
        }
        List<Item> all = new ArrayList<>();
        List<Item> basic = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (item == Items.AIR || BANNED.contains(id) || id.endsWith("_spawn_egg") || id.startsWith("netherite_")
                    || id.contains("infested") || id.endsWith("_smithing_template") || id.startsWith("music_disc")) {
                continue;
            }
            all.add(item);
            if (!STRONG.contains(id)) {
                basic.add(item);
            }
        }
        Comparator<Item> byId = Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString());
        all.sort(byId);
        basic.sort(byId);
        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            blocks.add(block);
        }
        blocks.sort(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString()));

        Random random = new Random(seed);
        Map<Block, Item> result = new HashMap<>();
        for (Block block : blocks) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            boolean common = COMMON.contains(id) || id.endsWith("_log") || id.endsWith("_leaves") || id.endsWith("_terracotta");
            List<Item> pool = common ? basic : all;
            result.put(block, pool.get(random.nextInt(pool.size())));
        }
        mapping = result;
        mappingId = seed;
        ChallengeMod.LOGGER.info("Block-Randomizer: {} Blöcke zugeordnet (Variante {})", result.size(), variant);
        return mapping;
    }
}
