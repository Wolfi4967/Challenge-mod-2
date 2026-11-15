package de.challengemod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Challenge-Mod: Die eigentliche Spiellogik (Regeln, Menüs, Mutationen, HUD …) steckt als Datenpaket
 * in den Mod-Ressourcen (data/challenge/...). Der Java-Teil ergänzt, was ein Datenpaket nicht kann:
 * echte Befehle, echte Ketten-Physik und einen Block-Randomizer, der sich im Spiel an- und ausschalten lässt.
 */
public class ChallengeMod implements ModInitializer {
    public static final String MOD_ID = "challenge-mod";
    public static final String NS = "challenge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModItems.initialize();
        // Seelenwelten: Start in den neuen Dimensionen, gemeinsame Seelentruhe, Seelenstein
        ServerTickEvents.END_SERVER_TICK.register(SoulWorlds::tick);
        UseItemCallback.EVENT.register(SoulWorlds::useItem);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(ChainPhysics::tick);
        LootTableEvents.MODIFY_DROPS.register(BlockRandomizer::modifyDrops);
        // Block-Randomizer: Blöcke droppen auch ohne das passende Werkzeug
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> BlockRandomizer.beforeBreak());
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> BlockRandomizer.afterBreak(level, player, pos, state));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BlockRandomizer.reset();
            WorldRandomizer.reset();
            CraftRandomizer.reset();
            SoulWorlds.reset();
        });
        // Welt-Randomizer: neu erzeugte Chunks versetzen bzw. mit Zufalls-Biom versehen
        ServerChunkEvents.CHUNK_GENERATE.register(WorldRandomizer::onChunkGenerated);
        ServerTickEvents.END_SERVER_TICK.register(WorldRandomizer::tick);
        // Crafting-Randomizer: Ergebnisfeld der Werkbank tauschen
        ServerTickEvents.END_SERVER_TICK.register(CraftRandomizer::tick);
        LOGGER.info("Challenge-Mod geladen – im Spiel: /challenge oder Taste G");
    }

    // ------------------------------------------------------------------ Befehle
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("challenge")
                .executes(ctx -> fn(ctx, "ui/open_main"))
                .then(Commands.literal("menu").executes(ctx -> fn(ctx, "ui/open_main")))
                .then(Commands.literal("start").executes(ctx -> fn(ctx, "cmd/start_logic")))
                .then(Commands.literal("pause").executes(ctx -> fn(ctx, "menu/pause")))
                .then(Commands.literal("disable")
                        .executes(ctx -> fn(ctx, "cmd/disable_ask"))
                        .then(Commands.literal("now").executes(ctx -> fn(ctx, "game/disable"))))
                .then(Commands.literal("setup").executes(ctx -> fn(ctx, "game/to_lobby_menu")))
                .then(Commands.literal("settings").executes(ctx -> fn(ctx, "ui/open_settings")))
                .then(Commands.literal("chaos").executes(ctx -> fn(ctx, "ui/open_chaos")))
                .then(Commands.literal("goal").executes(ctx -> fn(ctx, "ui/open_goal")))
                .then(Commands.literal("world").executes(ctx -> fn(ctx, "ui/open_world")))
                .then(Commands.literal("rules").executes(ctx -> fn(ctx, "ui/open_rules")))
                .then(Commands.literal("stats").executes(ctx -> fn(ctx, "stats/print")))
                .then(Commands.literal("chat").executes(ctx -> fn(ctx, "menu/show")))
                .then(Commands.literal("close").executes(ChallengeMod::closeDialog))
                // wird von den Einstellungs-Fenstern benutzt: /challenge apply <seite> <werte …>
                .then(Commands.literal("apply")
                        .then(Commands.argument("page", StringArgumentType.word())
                                .then(Commands.argument("values", StringArgumentType.greedyString())
                                        .executes(ChallengeMod::apply)))));

        // Kurzformen, wie gewünscht: /start challenge, /pause challenge, /disable challenge
        dispatcher.register(Commands.literal("start")
                .then(Commands.literal("challenge").executes(ctx -> fn(ctx, "cmd/start_logic"))));
        dispatcher.register(Commands.literal("pause")
                .then(Commands.literal("challenge").executes(ctx -> fn(ctx, "menu/pause"))));
        dispatcher.register(Commands.literal("disable")
                .then(Commands.literal("challenge").executes(ctx -> fn(ctx, "cmd/disable_ask"))));
    }

    /** Führt eine Funktion des Challenge-Datenpakets aus – als der Spieler, der den Befehl benutzt hat. */
    private static int fn(CommandContext<CommandSourceStack> ctx, String function) {
        return runFunction(ctx.getSource(), function);
    }

    static int runFunction(CommandSourceStack source, String functionWithArgs) {
        String command = "function " + NS + ":" + functionWithArgs;
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            command = "execute as " + player.getStringUUID() + " at @s run " + command;
        }
        return runAsServer(source.getServer(), command);
    }

    static int runAsServer(MinecraftServer server, String command) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
            return 1;
        } catch (Exception e) {
            LOGGER.error("Befehl fehlgeschlagen: {}", command, e);
            return 0;
        }
    }

    private static int closeDialog(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        return runAsServer(ctx.getSource().getServer(), "dialog clear " + player.getStringUUID());
    }

    /** /challenge apply rules|chaos|goal <zahlen …> – Werte kommen aus den Einstellungs-Fenstern. */
    private static int apply(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String page = StringArgumentType.getString(ctx, "page");
        String[] keys = PageKeys.keys(page);
        if (keys == null) {
            source.sendFailure(Component.literal("Unbekannte Einstellungsseite: " + page));
            return 0;
        }
        String[] values = StringArgumentType.getString(ctx, "values").trim().split("\\s+");
        if (values.length != keys.length) {
            source.sendFailure(Component.literal("Erwartet " + keys.length + " Werte, bekommen " + values.length));
            return 0;
        }
        StringBuilder macroArgs = new StringBuilder("{");
        for (int i = 0; i < keys.length; i++) {
            int value;
            try {
                value = (int) Math.round(Double.parseDouble(values[i]));
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Ungültiger Wert: " + values[i]));
                return 0;
            }
            if (i > 0) {
                macroArgs.append(',');
            }
            macroArgs.append(keys[i]).append(':').append(value);
        }
        macroArgs.append('}');
        return runFunction(source, "settings/set_" + page + " " + macroArgs);
    }
}
