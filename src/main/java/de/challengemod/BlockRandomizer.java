package de.challengemod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final Set<ResourceKey<LootTable>> droppedThisBreak = new HashSet<>();
    private static ResourceKey<LootTable> pendingKey;
    private static int pendingTick = -1;
    private static boolean errorLogged;

    private BlockRandomizer() {
    }

    /** Vor jedem Abbau: merken, welche Blöcke bei diesem Abbau schon etwas gedroppt haben. */
    static boolean beforeBreak() {
        droppedThisBreak.clear();
        pendingKey = null;
        return true;
    }

    /**
     * Nach dem Abbau: Hat der Block nichts gedroppt (falsches Werkzeug, bloße Hand, Glas, Laub …),
     * droppt er trotzdem sein Randomizer-Item. So ist jeder Block mit allem abbaubar.
     */
    static void afterBreak(Level level, Player player, BlockPos pos, BlockState state) {
        try {
            MinecraftServer server = level.getServer();
            if (server == null || player.isCreative()) {
                return;
            }
            if (!Scores.running(server) || Scores.get(server, "#m_brand", "sb.cfg", 0) != 1) {
                return;
            }
            Block block = state.getBlock();
            var table = block.getLootTable();
            if (table.isEmpty() || droppedThisBreak.contains(table.get())) {
                return; // Block ohne Beute (z. B. Spawner) bzw. hat schon gedroppt
            }
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (SKIP.contains(id)) {
                return;
            }
            Item target = mapping(server).get(block);
            if (target != null) {
                Block.popResource(level, pos, new ItemStack(target));
                // Minecraft erzeugt den normalen Drop erst NACH diesem Moment -> vormerken, damit er gestrichen wird
                pendingKey = table.get();
                pendingTick = server.getTickCount();
            }
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Block-Randomizer ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }

    static void reset() {
        mapping = null;
        pools = null;
        tableToBlock = null;
        pendingKey = null;
        droppedThisBreak.clear();
        mappingId = Long.MIN_VALUE;
    }

    static void modifyDrops(Holder<LootTable> holder, LootContext context, List<ItemStack> drops) {
        try {
            MinecraftServer server = context.getLevel().getServer();
            if (!Scores.running(server)) {
                return;
            }
            var key = holder.unwrapKey();
            if (key.isEmpty()) {
                return;
            }
            Block block = tables().get(key.get());
            if (block == null) {
                otherLoot(server, key.get().toString(), drops); // Mob, Truhe, Angeln …
                return;
            }
            if (drops.isEmpty() || Scores.get(server, "#m_brand", "sb.cfg", 0) != 1) {
                return;
            }
            if (key.get().equals(pendingKey) && pendingTick == server.getTickCount()) {
                // Dieser Block hat sein Item beim Abbauen schon bekommen -> den normalen Drop streichen
                pendingKey = null;
                drops.clear();
                return;
            }
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (SKIP.contains(id)) {
                return;
            }
            Item target = mapping(server).get(block);
            if (target == null) {
                return;
            }
            // immer genau EIN Item pro Block
            drops.clear();
            drops.add(new ItemStack(target));
            droppedThisBreak.add(key.get());
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Block-Randomizer ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }

    /** Mob-Drop-Randomizer (#m_mobdrop) und Truhen-/Angel-Randomizer (#m_chest). */
    private static void otherLoot(MinecraftServer server, String table, List<ItemStack> drops) {
        if (table.contains("entities/")) {
            if (Scores.get(server, "#m_mobdrop", "sb.cfg", 0) == 1) {
                drops.clear();
                drops.add(new ItemStack(mobItem(server, table)));
            }
        } else if (table.contains("chests/") || table.contains("gameplay/fishing")) {
            if (Scores.get(server, "#m_chest", "sb.cfg", 0) == 1) {
                List<Item> all = pools(server).all;
                Random random = new Random();
                for (int i = 0; i < drops.size(); i++) {
                    Item item = all.get(random.nextInt(all.size()));
                    int count = Math.max(1, Math.min(drops.get(i).getCount(), new ItemStack(item).getMaxStackSize()));
                    drops.set(i, new ItemStack(item, count));
                }
            }
        }
    }

    /** Jede Mob-Art droppt ein festes Item. Lohenruten und Enderperlen bleiben erreichbar (für das Ziel Enderdrache). */
    private static Item mobItem(MinecraftServer server, String table) {
        Pools pools = pools(server);
        Item cached = pools.mobItems.get(table);
        if (cached != null) {
            return cached;
        }
        String[] common = {"entities/zombie]", "entities/skeleton]", "entities/creeper]", "entities/spider]"};
        int rotate = (int) Math.floorMod(pools.seed, 4L);
        Item item;
        if (table.endsWith(common[rotate])) {
            item = Items.BLAZE_ROD;
        } else if (table.endsWith(common[(rotate + 1) % 4])) {
            item = Items.ENDER_PEARL;
        } else {
            item = pools.all.get(new Random(pools.seed * 31L + table.hashCode()).nextInt(pools.all.size()));
        }
        pools.mobItems.put(table, item);
        return item;
    }

    /**
     * Crafting-Randomizer: Jedes gecraftete Item wird zu einem festen anderen Item.
     * Enderaugen und Lohenstaub bleiben normal, damit das Ende erreichbar ist.
     */
    static Item craftItem(MinecraftServer server, Item original) {
        if (original == Items.ENDER_EYE || original == Items.BLAZE_POWDER
                || BuiltInRegistries.ITEM.getKey(original).getNamespace().equals(ChallengeMod.NS)) {
            return original; // auch die Siegel und der Seelenstein bleiben, was sie sind
        }
        Pools pools = pools(server);
        Item cached = pools.craftItems.get(original);
        if (cached == null) {
            String id = BuiltInRegistries.ITEM.getKey(original).toString();
            cached = pools.all.get(new Random(pools.seed * 131L + id.hashCode()).nextInt(pools.all.size()));
            pools.craftItems.put(original, cached);
        }
        return cached;
    }

    /**
     * Cushions sind in 26.3 keine Blöcke, sondern Objekte in der Welt. Bricht eins ab, entsteht direkt ein Item –
     * ohne Beutetabelle. Dieses frisch entstandene Item wird hier getauscht (Fabric-Ereignis „Objekt geladen“).
     */
    static void onEntityLoad(Entity entity, ServerLevel level) {
        try {
            if (!(entity instanceof ItemEntity drop) || drop.getAge() != 0 || drop.getOwner() != null) {
                return; // nur frisch entstandene Items, die niemand geworfen hat
            }
            ItemStack stack = drop.getItem();
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_cushion")) {
                return;
            }
            MinecraftServer server = level.getServer();
            if (server == null || !Scores.running(server) || Scores.get(server, "#m_brand", "sb.cfg", 0) != 1) {
                return;
            }
            Pools pools = pools(server);
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Item target = pools.all.get(new Random(pools.seed * 977L + id.hashCode()).nextInt(pools.all.size()));
            drop.setItem(new ItemStack(target));
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Block-Randomizer ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }

    private static final class Pools {
        long seed;
        List<Item> all = new ArrayList<>();
        List<Item> basic = new ArrayList<>();
        Map<String, Item> mobItems = new HashMap<>();
        Map<Item, Item> craftItems = new HashMap<>();
    }

    private static Pools pools;

    /** Alle erlaubten Items (all) und die ohne starke Items (basic), pro Welt und Variante einmal berechnet. */
    private static Pools pools(MinecraftServer server) {
        int variant = Scores.get(server, "#bvar", "sb", 1);
        long seed = server.overworld().getSeed() * 31L + variant * 0x9E3779B97F4A7C15L;
        if (pools != null && pools.seed == seed) {
            return pools;
        }
        Pools p = new Pools();
        p.seed = seed;
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(ChallengeMod.NS)) {
                continue; // eigene Items (Siegel, Seelenstein) fallen nie zufällig aus Blöcken
            }
            if (item == Items.AIR || BANNED.contains(id) || id.endsWith("_spawn_egg") || id.startsWith("netherite_")
                    || id.contains("infested") || id.endsWith("_smithing_template") || id.startsWith("music_disc")) {
                continue;
            }
            p.all.add(item);
            if (!STRONG.contains(id)) {
                p.basic.add(item);
            }
        }
        Comparator<Item> byId = Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString());
        p.all.sort(byId);
        p.basic.sort(byId);
        pools = p;
        return p;
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
        Pools p = pools(server);
        long seed = p.seed;
        int variant = Scores.get(server, "#bvar", "sb", 1);
        if (mapping != null && mappingId == seed) {
            return mapping;
        }
        List<Item> all = p.all;
        List<Item> basic = p.basic;
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
