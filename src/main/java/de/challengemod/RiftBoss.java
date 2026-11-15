package de.challengemod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Der Bosskampf auf der Insel: „Der Zerrissene“.
 *  - Lichtform: nur der Nether-Spieler (Rolle 2) kann ihn verletzen. Blitze schlagen bei den Spielern ein.
 *  - Ascheform: nur der Oberwelt-Spieler (Rolle 1) kann ihn verletzen. Ascheschläge explodieren bei den Spielern.
 *  - Alle paar Sekunden leuchtet eine Gruppe von Rissen auf und bricht kurz danach aus.
 *  - Finale: Die letzten 10 % fallen nur, wenn beide ihn innerhalb von 5 Sekunden treffen.
 * Fast alles läuft über normale Befehle (summon, attribute, bossbar …), damit es robust bleibt.
 */
final class RiftBoss {
    static final String NAME = "Der Zerrissene";
    private static final float MAX_HP = 480.0F;
    private static final String BOSS = "@e[tag=riss_boss,limit=1]";
    private static final Random RANDOM = new Random();

    private static long clock;
    private static int form;          // 0 = Licht, 1 = Asche
    private static int formTimer;
    private static int strikeTimer;
    private static int crackTimer;
    private static int addTimer;
    private static int grace;         // Ticks, in denen ein fehlender Boss noch nicht neu gerufen wird
    private static int crackGroup = -1;
    private static int crackStage;    // 0 = ruhig, 1 = leuchtet (Warnung), 2 = klingt ab
    private static int crackCountdown;
    private static boolean finale;
    private static boolean finaleAnnounced;
    private static boolean finishNow;
    private static boolean killing;
    private static boolean has1;
    private static boolean has2;
    private static float lastHp = MAX_HP;
    private static final long[] lastHit = {-1000, -1000, -1000};
    private static final List<double[]> strikes = new ArrayList<>(); // x, y, z, Ticks bis zum Einschlag, Form
    private static final Map<String, Long> hinted = new HashMap<>();
    private static boolean errorLogged;
    private static boolean barShown;

    private RiftBoss() {
    }

    static void reset() {
        clock = 0;
        form = 0;
        formTimer = 0;
        strikeTimer = 0;
        crackTimer = 0;
        addTimer = 0;
        grace = 0;
        crackGroup = -1;
        crackStage = 0;
        crackCountdown = 0;
        finale = false;
        finaleAnnounced = false;
        finishNow = false;
        killing = false;
        lastHp = MAX_HP;
        lastHit[1] = -1000;
        lastHit[2] = -1000;
        strikes.clear();
        hinted.clear();
    }

    /** Vor dem Betreten der Insel: Reste eines früheren Kampfes wegräumen. */
    static void prepare(MinecraftServer server) {
        reset();
        killing = true;
        Rift.cmd(server, "kill @e[tag=riss_boss]");
        Rift.cmd(server, "kill @e[tag=riss_add]");
        killing = false;
        Rift.cmd(server, "bossbar remove challenge:riss");
        barShown = false;
    }

    /** Ist die Challenge vorbei (verloren, abgebrochen), verschwindet die Bossleiste wieder. */
    static void hideBarIfShown(MinecraftServer server) {
        if (barShown) {
            barShown = false;
            Rift.cmd(server, "bossbar remove challenge:riss");
        }
    }

    private static String dimension(MinecraftServer server) {
        return Rift.score(server, "#arena_dim") == 1 ? Rift.OVERWORLD : Rift.ARENA;
    }

    private static String in(MinecraftServer server) {
        return "execute in " + dimension(server) + " run ";
    }

    private static boolean inArena(MinecraftServer server, ServerPlayer player, int cx, int cz) {
        return player.level().dimension().toString().contains(dimension(server))
                && Math.abs(player.getX() - cx) < 90 && Math.abs(player.getZ() - cz) < 90;
    }

    /** Zahl für Befehle: zwei Nachkommastellen, nie in der Schreibweise 1.0E-4. */
    private static String fmt(double value) {
        return Double.toString(Math.round(value * 100.0) / 100.0);
    }

    // ------------------------------------------------------------------ Tick
    static void tick(MinecraftServer server, int phase) {
        try {
            clock++;
            int cx = Rift.score(server, "#arena_x");
            int top = Rift.score(server, "#arena_y");
            int cz = Rift.score(server, "#arena_z");
            List<ServerPlayer> players = new ArrayList<>();
            boolean one = false;
            boolean two = false;
            for (ServerPlayer player : Rift.rolePlayers(server)) {
                if (inArena(server, player, cx, cz)) {
                    players.add(player);
                    int role = Rift.role(server, player);
                    one |= role == 1;
                    two |= role == 2;
                }
            }
            has1 = one;
            has2 = two;
            if (phase == 2) {
                for (ServerPlayer player : players) {
                    double dx = player.getX() - cx;
                    double dz = player.getZ() - cz;
                    if (dx * dx + dz * dz < 13 * 13) {
                        start(server, cx, top, cz);
                        return;
                    }
                }
                return;
            }
            fight(server, players, cx, top, cz);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Der Bosskampf ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }

    private static void start(MinecraftServer server, int cx, int top, int cz) {
        reset();
        RiftIsland.ensure(cx, top, cz);
        Rift.cmd(server, "team add riss_licht");
        Rift.cmd(server, "team modify riss_licht color yellow");
        Rift.cmd(server, "team add riss_asche");
        Rift.cmd(server, "team modify riss_asche color dark_red");
        Rift.cmd(server, "bossbar add challenge:riss \"" + NAME + "\"");
        Rift.cmd(server, "bossbar set challenge:riss max " + (int) MAX_HP);
        Rift.cmd(server, "bossbar set challenge:riss style notched_10");
        Rift.cmd(server, "bossbar set challenge:riss players @a");
        barShown = true;
        Rift.cmd(server, in(server) + "summon minecraft:lightning_bolt " + cx + " " + top + " " + cz);
        spawnBoss(server, cx, top, cz, MAX_HP);
        Rift.set(server, "#rift_phase", 3);
        formTimer = 500;
        strikeTimer = 100;
        crackTimer = 200;
        addTimer = 600;
        applyForm(server);
        Rift.title(server, "DER ZERRISSENE", "dark_purple", "Lichtform: nur der Nether-Spieler kann ihn verletzen");
        Rift.sound(server, "minecraft:entity.wither.spawn", 0.7);
    }

    private static void spawnBoss(MinecraftServer server, int cx, int top, int cz, float hp) {
        Rift.cmd(server, in(server) + "summon minecraft:wither_skeleton " + (cx + 0.5) + " " + (top + 1) + " " + (cz + 0.5)
                + " {Tags:[\"riss_boss\"],PersistenceRequired:1b}");
        Rift.cmd(server, "data merge entity " + BOSS + " {CustomName:\"" + NAME + "\",CustomNameVisible:1b}");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:scale base set 3.5");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:max_health base set " + (int) MAX_HP);
        Rift.cmd(server, "attribute " + BOSS + " minecraft:attack_damage base set 6");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:knockback_resistance base set 1");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:follow_range base set 64");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:step_height base set 2.5");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:movement_speed base set 0.27");
        Rift.cmd(server, "data merge entity " + BOSS + " {Health:" + hp + "f}");
        Rift.cmd(server, "effect give " + BOSS + " minecraft:glowing infinite 0 true");
        grace = 60;
    }

    private static void applyForm(MinecraftServer server) {
        boolean light = form == 0;
        String[] armor = light
                ? new String[] {"golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots", "golden_sword"}
                : new String[] {"netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots", "netherite_sword"};
        Rift.cmd(server, "item replace entity " + BOSS + " armor.head with minecraft:" + armor[0]);
        Rift.cmd(server, "item replace entity " + BOSS + " armor.chest with minecraft:" + armor[1]);
        Rift.cmd(server, "item replace entity " + BOSS + " armor.legs with minecraft:" + armor[2]);
        Rift.cmd(server, "item replace entity " + BOSS + " armor.feet with minecraft:" + armor[3]);
        Rift.cmd(server, "item replace entity " + BOSS + " weapon.mainhand with minecraft:" + armor[4]);
        Rift.cmd(server, "team join " + (light ? "riss_licht" : "riss_asche") + " @e[tag=riss_boss]");
        Rift.cmd(server, "bossbar set challenge:riss color " + (light ? "yellow" : "red"));
        Rift.cmd(server, "bossbar set challenge:riss name \"" + NAME + (light ? " – Lichtform" : " – Ascheform") + "\"");
    }

    private static void fight(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        RiftIsland.ensure(cx, top, cz);
        if (finishNow) {
            win(server);
            return;
        }
        if (grace > 0) {
            grace--;
        }
        if (clock % 5 == 0) {
            Rift.cmd(server, "execute store result score #boss_n sb if entity @e[tag=riss_boss]");
            if (Rift.score(server, "#boss_n") == 0) {
                if (grace == 0) {
                    // in die Leere gefallen oder verschwunden: mit dem letzten Stand zurückholen
                    spawnBoss(server, cx, top, cz, Math.max(lastHp, MAX_HP * 0.1F));
                    applyForm(server);
                }
            } else {
                Rift.cmd(server, "execute store result score #boss_hp sb run data get entity " + BOSS + " Health");
                Rift.cmd(server, "execute store result bossbar challenge:riss value run data get entity " + BOSS + " Health");
                lastHp = Math.max(1, Rift.score(server, "#boss_hp"));
                Rift.cmd(server, "execute as @e[tag=riss_boss] at @s unless entity @s[x=" + (cx - 46) + ",y=" + (top - 6) + ",z="
                        + (cz - 46) + ",dx=92,dy=70,dz=92] run tp @s " + (cx + 0.5) + " " + (top + 1) + " " + (cz + 0.5));
            }
            Rift.cmd(server, "bossbar set challenge:riss players @a");
        }
        boolean enraged = lastHp < MAX_HP * 0.5F;

        if (finale && !finaleAnnounced) {
            finaleAnnounced = true;
            Rift.cmd(server, "team leave @e[tag=riss_boss]");
            Rift.cmd(server, "bossbar set challenge:riss color white");
            Rift.cmd(server, "bossbar set challenge:riss name \"" + NAME + " – trefft ihn GLEICHZEITIG\"");
            Rift.title(server, "JETZT GEMEINSAM", "white", "Beide müssen ihn innerhalb von 5 Sekunden treffen");
            Rift.sound(server, "minecraft:block.bell.resonate", 1.0);
        }

        // Formwechsel
        if (!finale && --formTimer <= 0) {
            formTimer = enraged ? 360 : 500;
            form = 1 - form;
            applyForm(server);
            if (form == 0) {
                Rift.title(server, "LICHTFORM", "yellow", "Nur der Nether-Spieler kann ihn verletzen");
            } else {
                Rift.title(server, "ASCHEFORM", "dark_red", "Nur der Oberwelt-Spieler kann ihn verletzen");
            }
            Rift.sound(server, "minecraft:entity.illusioner.mirror_move", 0.7);
        }

        // Blitze (Licht) bzw. Ascheschläge (Asche) auf die Plätze, an denen die Spieler gerade stehen
        if (--strikeTimer <= 0) {
            strikeTimer = enraged ? 55 : 80;
            for (ServerPlayer player : players) {
                strikes.add(new double[] {player.getX(), player.getY(), player.getZ(), 30, form});
            }
        }
        String in = in(server);
        for (Iterator<double[]> it = strikes.iterator(); it.hasNext();) {
            double[] s = it.next();
            String at = fmt(s[0]) + " " + fmt(s[1]) + " " + fmt(s[2]);
            s[3]--;
            if (s[3] > 0) {
                if (((int) s[3]) % 4 == 0) {
                    Rift.cmd(server, in + "particle " + (s[4] == 0 ? "minecraft:end_rod" : "minecraft:flame") + " " + fmt(s[0]) + " "
                            + fmt(s[1] + 0.2) + " " + fmt(s[2]) + " 1.3 0.1 1.3 0 14 force");
                }
                continue;
            }
            it.remove();
            if (s[4] == 0) {
                Rift.cmd(server, in + "summon minecraft:lightning_bolt " + at);
            } else {
                Rift.cmd(server, in + "particle minecraft:explosion_emitter " + at + " 0 0 0 0 1 force");
                Rift.cmd(server, in + "playsound minecraft:entity.generic.explode master @a " + at + " 2 0.8");
                Rift.cmd(server, "execute in " + dimension(server) + " positioned " + at
                        + " as @a[distance=..3.2] run damage @s 9 minecraft:explosion");
            }
        }

        // Risse
        cracks(server, players, top, enraged);

        // Verstärkung
        if (--addTimer <= 0) {
            addTimer = enraged ? 500 : 700;
            Rift.cmd(server, "execute store result score #adds_n sb if entity @e[tag=riss_add]");
            if (Rift.score(server, "#adds_n") < 4) {
                String mob = form == 0
                        ? "minecraft:stray ~ ~ ~ {Tags:[\"riss_add\"],PersistenceRequired:1b,equipment:{mainhand:{id:\"minecraft:bow\",count:1}}}"
                        : "minecraft:wither_skeleton ~ ~ ~ {Tags:[\"riss_add\"],PersistenceRequired:1b,equipment:{mainhand:{id:\"minecraft:stone_sword\",count:1}}}";
                Rift.cmd(server, "execute at " + BOSS + " positioned ~3 ~ ~3 run summon " + mob);
                Rift.cmd(server, "execute at " + BOSS + " positioned ~-3 ~ ~-3 run summon " + mob);
                Rift.sound(server, "minecraft:entity.evoker.prepare_summon", 0.9);
            }
        }
    }

    private static void cracks(MinecraftServer server, List<ServerPlayer> players, int top, boolean enraged) {
        if (crackStage == 0) {
            if (--crackTimer > 0) {
                return;
            }
            crackGroup = RANDOM.nextInt(4);
            crackStage = 1;
            crackCountdown = 40;
            paintCracks(server, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
            Rift.sound(server, "minecraft:block.beacon.activate", 1.4);
            Rift.cmd(server, "title @a actionbar {text:\"Die Risse leuchten – runter von den hellen Linien!\",color:\"light_purple\"}");
            return;
        }
        if (--crackCountdown > 0) {
            return;
        }
        if (crackStage == 1) {
            // Ausbruch: wer auf oder direkt neben einem leuchtenden Riss steht, bekommt Schaden
            var columns = RiftIsland.CRACK_COLUMNS.get(crackGroup);
            for (ServerPlayer player : players) {
                int bx = (int) Math.floor(player.getX());
                int bz = (int) Math.floor(player.getZ());
                boolean hit = false;
                for (int ox = -1; ox <= 1 && !hit; ox++) {
                    for (int oz = -1; oz <= 1 && !hit; oz++) {
                        hit = columns.contains(RiftIsland.column(bx + ox, bz + oz));
                    }
                }
                if (hit && player.getY() > top - 3 && player.getY() < top + 5) {
                    Rift.cmd(server, "damage " + player.getScoreboardName() + " 8 minecraft:magic");
                }
            }
            List<int[]> blocks = RiftIsland.CRACKS.get(crackGroup);
            String in = in(server);
            for (int i = 0; i < 70 && !blocks.isEmpty(); i++) {
                int[] b = blocks.get(RANDOM.nextInt(blocks.size()));
                Rift.cmd(server, in + "particle minecraft:reverse_portal " + (b[0] + 0.5) + " " + (b[1] + 1.6) + " " + (b[2] + 0.5)
                        + " 0.2 1.0 0.2 0.05 14 force");
            }
            Rift.sound(server, "minecraft:entity.warden.sonic_boom", 1.3);
            crackStage = 2;
            crackCountdown = 12;
            return;
        }
        paintCracks(server, Blocks.PURPLE_CONCRETE.defaultBlockState());
        crackStage = 0;
        crackTimer = enraged ? 150 : 230;
    }

    private static void paintCracks(MinecraftServer server, BlockState state) {
        ServerLevel level = Rift.findLevel(server, dimension(server));
        if (level == null || crackGroup < 0) {
            return;
        }
        for (int[] b : RiftIsland.CRACKS.get(crackGroup)) {
            level.setBlock(new BlockPos(b[0], b[1], b[2]), state, 2 | 16);
        }
    }

    /** Setzt alle Risse der Insel auf ihren normalen Block zurück. */
    static void resetCracks(MinecraftServer server, int cx, int top, int cz) {
        RiftIsland.ensure(cx, top, cz);
        for (int g = 0; g < 4; g++) {
            crackGroup = g;
            paintCracks(server, Blocks.PURPLE_CONCRETE.defaultBlockState());
        }
        crackGroup = -1;
        crackStage = 0;
    }

    private static void win(MinecraftServer server) {
        finishNow = false;
        if (crackStage != 0) {
            paintCracks(server, Blocks.PURPLE_CONCRETE.defaultBlockState());
        }
        killing = true;
        Rift.cmd(server, "kill @e[tag=riss_boss]");
        Rift.cmd(server, "kill @e[tag=riss_add]");
        killing = false;
        Rift.cmd(server, "bossbar remove challenge:riss");
        barShown = false;
        Rift.set(server, "#rift_phase", 4);
        Rift.broadcast(server, "§d✦ " + NAME + " ist gefallen. §7In 30 Sekunden bringt euch der Riss zurück in die Oberwelt.");
        Rift.scheduleReturn(600);
        Rift.cmd(server, "function " + ChallengeMod.NS + ":win/win");
        reset();
    }

    // ------------------------------------------------------------------ Schaden am Boss
    private static boolean isBoss(LivingEntity entity) {
        return Math.abs(entity.getMaxHealth() - MAX_HP) < 0.5F || NAME.equals(entity.getName().getString());
    }

    /** Fabric-Ereignis: false = der Treffer zählt nicht. */
    static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        try {
            if (killing || !isBoss(entity)) {
                return true;
            }
            MinecraftServer server = entity.level().getServer();
            if (server == null || Rift.score(server, "#rift_phase") != 3) {
                return true;
            }
            if (!(source.getEntity() instanceof ServerPlayer player)) {
                return false; // Blitze, Feuer, Sturz und Leere tun ihm nichts
            }
            int role = Rift.role(server, player);
            if (!finale) {
                int need = form == 0 ? 2 : 1;
                boolean needPresent = need == 1 ? has1 : has2;
                if (needPresent && role != need) {
                    hint(server, player, form == 0 ? "Lichtform: Nur der Nether-Spieler kann ihn jetzt verletzen. Lenk ihn ab!"
                            : "Ascheform: Nur der Oberwelt-Spieler kann ihn jetzt verletzen. Lenk ihn ab!");
                    return false;
                }
                float limit = entity.getMaxHealth() * 0.1F;
                if (entity.getHealth() - amount <= limit) {
                    entity.setHealth(limit);
                    finale = true;
                    return false;
                }
                return true;
            }
            lastHit[role == 2 ? 2 : 1] = clock;
            boolean ok1 = !has1 || clock - lastHit[1] <= 100;
            boolean ok2 = !has2 || clock - lastHit[2] <= 100;
            if (ok1 && ok2) {
                finishNow = true;
            } else {
                hint(server, player, "Getroffen! Jetzt muss dein Partner innerhalb von 5 Sekunden auch treffen.");
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void hint(MinecraftServer server, ServerPlayer player, String text) {
        String name = player.getScoreboardName();
        Long last = hinted.get(name);
        if (last != null && clock - last < 30) {
            return;
        }
        hinted.put(name, clock);
        Rift.cmd(server, "title " + name + " actionbar {text:\"" + text + "\",color:\"red\"}");
    }
}
