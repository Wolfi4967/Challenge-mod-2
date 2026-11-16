package de.challengemod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Der Bosskampf auf der Insel: „Der Klingenmeister“ – ein gepanzerter Schwertkämpfer, doppelt so groß wie die Spieler.
 *
 * Phase 1 und 2 – das Duell: Er fordert immer EINEN Spieler heraus (Kreis um ihn). Nur der Duellant kann ihn verletzen,
 *   und nur, nachdem er richtig auf einen Schlag geantwortet hat:
 *     HIEB = mit dem Schild blocken, FEGER = springen, STICH = zur Seite gehen, WIRBEL = Abstand nehmen.
 *   Der andere Spieler hält die Schattenklingen fern, die auf den Duellanten zufliegen. Nach drei Schlagwechseln
 *   wechselt der Gegner. Ab Phase 2 (zwei Schwerter) kommen die Schläge schneller und als Zweierfolge.
 * Phase 3 – entwaffnet: Sein Schwert steckt in der Insel. Nur wer es in der Hand hält, kann ihn verletzen. Er jagt den
 *   Träger und holt es sich zurück, wenn er ihn erwischt. Man kann es sich zuwerfen.
 * Finale (letzte 10 %): Einer blockt seinen Hieb mit dem Schild, dann hat der andere 5 Sekunden für den Schlag mit dem Schwert.
 *
 * Der Boss selbst hat keine eigene KI: Bewegung, Schläge und Treffer steuert dieser Code über normale Befehle.
 */
final class RiftBoss {
    static final String NAME = "Der Klingenmeister";
    private static final float MAX_HP = 320.0F;
    private static final String BOSS = "@e[tag=riss_boss,limit=1]";
    private static final String BLADE = ChallengeMod.NS + ":master_blade";
    private static final Random RANDOM = new Random();
    private static final int BOW = 0, IDLE = 1, TELEGRAPH = 2, STAGGER = 3;
    private static final int HIEB = 0, FEGER = 1, STICH = 2, WIRBEL = 3;
    private static final String[] ATTACK_NAME = {"HIEB", "FEGER", "STICH", "WIRBEL"};
    private static final String[] ATTACK_COLOR = {"red", "yellow", "aqua", "light_purple"};
    private static final String[] ATTACK_HINT = {"Schild hoch!", "Springen!", "Zur Seite!", "Abstand nehmen!"};
    private static final String[] ATTACK_SOUND = {"minecraft:block.anvil.place", "minecraft:entity.player.attack.sweep",
            "minecraft:item.trident.throw", "minecraft:entity.breeze.wind_burst"};
    private static final int[] ATTACK_DAMAGE = {8, 6, 8, 7};
    private static final String[] TAUNTS = {"Zu langsam.", "Das nennst du Verteidigung?", "Mein Lehrling war mit sechs Jahren schneller.",
            "Noch einmal. Diesmal mit Würde.", "Du kämpfst wie ein Schaf.", "Ich habe Zeit. Du auch?"};

    private static long clock;
    private static int stage;
    private static int timer;
    private static int attack = -1;
    private static int comboLeft;
    private static boolean comboOk;
    private static int exchanges;
    private static String duelist = "";
    private static boolean bowed;
    private static boolean angry;
    private static boolean placed;
    private static double bx;
    private static double bz;
    private static double lineX;
    private static double lineZ;
    private static int bladeTimer;
    private static boolean disarmed;
    private static boolean disarmAnnounced;
    private static double[] swordSpot;
    private static int swordLost;
    private static int outside;
    private static int catchCooldown;
    private static boolean finale;
    private static boolean finaleAnnounced;
    private static int openWindow;
    private static boolean finishNow;
    private static boolean killing;
    private static int grace;
    private static float lastHp = MAX_HP;
    private static final Map<String, Long> hinted = new HashMap<>();
    private static final Map<String, Long> bladeHit = new HashMap<>();
    private static boolean errorLogged;
    private static boolean barShown;

    private RiftBoss() {
    }

    static void reset() {
        clock = 0;
        stage = BOW;
        timer = 0;
        attack = -1;
        comboLeft = 1;
        comboOk = true;
        exchanges = 0;
        duelist = "";
        bowed = false;
        angry = false;
        placed = false;
        bladeTimer = 60;
        disarmed = false;
        disarmAnnounced = false;
        swordSpot = null;
        swordLost = 0;
        outside = 0;
        catchCooldown = 0;
        finale = false;
        finaleAnnounced = false;
        openWindow = 0;
        finishNow = false;
        killing = false;
        grace = 0;
        lastHp = MAX_HP;
        hinted.clear();
        bladeHit.clear();
    }

    /** Vor dem Betreten der Insel und beim Zurücksetzen: Reste eines früheren Kampfes wegräumen. */
    static void prepare(MinecraftServer server) {
        reset();
        killing = true;
        Rift.cmd(server, "kill @e[tag=riss_boss]");
        Rift.cmd(server, "kill @e[tag=riss_add]");
        Rift.cmd(server, "kill @e[tag=riss_halo]");
        Rift.cmd(server, "kill @e[tag=riss_shard]");
        Rift.cmd(server, "kill @e[tag=riss_sword]");
        removeBlades(server);
        if (Rift.findLevel(server, Rift.ARENA) != null) {
            // ältere Versionen haben in dieser Dimension versehentlich einen Enderdrachen erzeugt
            Rift.cmd(server, "execute in " + Rift.ARENA + " run kill @e[type=minecraft:ender_dragon,distance=0..]");
            Rift.cmd(server, "execute in " + Rift.ARENA + " run kill @e[type=minecraft:end_crystal,distance=0..]");
        }
        killing = false;
        Rift.cmd(server, "bossbar remove challenge:riss");
        barShown = false;
    }

    private static void removeBlades(MinecraftServer server) {
        Rift.cmd(server, "clear @a " + BLADE);
        Rift.cmd(server, "kill @e[type=minecraft:item,nbt={Item:{id:\"" + BLADE + "\"}}]");
    }

    /** Ist die Challenge vorbei (verloren, abgebrochen), verschwindet die Bossleiste wieder. */
    static void hideBarIfShown(MinecraftServer server) {
        if (barShown) {
            barShown = false;
            Rift.cmd(server, "bossbar remove challenge:riss");
        }
    }

    /** Wird nicht mehr gebraucht (der Klingenmeister benutzt die Risse nicht), bleibt aber für Rift.enter erhalten. */
    static void resetCracks(MinecraftServer server, int cx, int top, int cz) {
        RiftIsland.ensure(cx, top, cz);
    }

    // ------------------------------------------------------------------ Helfer
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

    private static void fling(ServerPlayer player, double x, double y, double z) {
        Vec3 velocity = new Vec3(x, y, z);
        player.setDeltaMovement(velocity);
        player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
    }

    private static ServerPlayer find(List<ServerPlayer> players, String name) {
        for (ServerPlayer player : players) {
            if (player.getScoreboardName().equals(name)) {
                return player;
            }
        }
        return null;
    }

    private static double distance(ServerPlayer player) {
        return Math.hypot(player.getX() - bx, player.getZ() - bz);
    }

    private static boolean holdsBlade(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.MASTER_BLADE);
    }

    private static void titleTo(MinecraftServer server, String name, String title, String color, String subtitle) {
        Rift.cmd(server, "title " + name + " times 0 25 6");
        Rift.cmd(server, "title " + name + " subtitle {text:\"" + subtitle + "\",color:\"gray\"}");
        Rift.cmd(server, "title " + name + " title {text:\"" + title + "\",color:\"" + color + "\",bold:true}");
    }

    private static void say(MinecraftServer server, String text) {
        Rift.broadcast(server, "§8<§c" + NAME + "§8> §f" + text);
    }

    private static void glow(MinecraftServer server, boolean on) {
        Rift.cmd(server, on ? "effect give " + BOSS + " minecraft:glowing infinite 0 true" : "effect clear " + BOSS + " minecraft:glowing");
    }

    /** Stellt den Boss an seinen Platz und lässt ihn einen Spieler ansehen (oder sich drehen). */
    private static void pose(MinecraftServer server, double y, String lookAt, double spinYaw) {
        String at = fmt(bx) + " " + fmt(y) + " " + fmt(bz);
        if (lookAt != null) {
            Rift.cmd(server, in(server) + "tp " + BOSS + " " + at + " facing entity " + lookAt);
        } else {
            Rift.cmd(server, in(server) + "tp " + BOSS + " " + at + " " + fmt(spinYaw) + " 0");
        }
    }

    // ------------------------------------------------------------------ Tick
    static void tick(MinecraftServer server, int phase) {
        try {
            clock++;
            int cx = Rift.score(server, "#arena_x");
            int top = Rift.score(server, "#arena_y");
            int cz = Rift.score(server, "#arena_z");
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer player : Rift.rolePlayers(server)) {
                if (inArena(server, player, cx, cz)) {
                    players.add(player);
                }
            }
            if (phase == 2) {
                for (ServerPlayer player : players) {
                    double dx = player.getX() - cx;
                    double dz = player.getZ() - cz;
                    if (dx * dx + dz * dz < 16 * 16) {
                        start(server, players, cx, top, cz);
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

    private static void start(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        reset();
        bx = cx + 0.5;
        bz = cz + 0.5;
        placed = true;
        Rift.cmd(server, "bossbar add challenge:riss \"" + NAME + "\"");
        Rift.cmd(server, "bossbar set challenge:riss max " + (int) MAX_HP);
        Rift.cmd(server, "bossbar set challenge:riss style notched_10");
        Rift.cmd(server, "bossbar set challenge:riss color red");
        Rift.cmd(server, "bossbar set challenge:riss players @a");
        barShown = true;
        spawnBoss(server, top, MAX_HP);
        Rift.set(server, "#rift_phase", 3);
        Rift.title(server, "DER KLINGENMEISTER", "red", "Er fordert euch zum Duell");
        Rift.sound(server, "minecraft:entity.wither.spawn", 0.9);
        Rift.broadcast(server, "§c✦ Der Klingenmeister §7duelliert immer einen von euch. Nur der Duellant kann ihn treffen – und nur, "
                + "wenn er richtig antwortet: §cHIEB§7 = Schild hoch, §eFEGER§7 = springen, §bSTICH§7 = zur Seite, §dWIRBEL§7 = Abstand. "
                + "Der andere schlägt die Schattenklingen weg. §fIhr braucht beide einen Schild.");
        startDuel(server, players, players.isEmpty() ? "" : players.get(0).getScoreboardName());
    }

    private static void spawnBoss(MinecraftServer server, int top, float hp) {
        Rift.cmd(server, in(server) + "summon minecraft:wither_skeleton " + fmt(bx) + " " + top + " " + fmt(bz)
                + " {Tags:[\"riss_boss\"],PersistenceRequired:1b,NoAI:1b,Silent:1b}");
        Rift.cmd(server, "data merge entity " + BOSS + " {CustomName:\"" + NAME + "\",CustomNameVisible:1b}");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:scale base set 2");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:max_health base set " + (int) MAX_HP);
        Rift.cmd(server, "attribute " + BOSS + " minecraft:knockback_resistance base set 1");
        Rift.cmd(server, "data merge entity " + BOSS + " {Health:" + hp + "f}");
        Rift.cmd(server, "item replace entity " + BOSS + " armor.head with minecraft:netherite_helmet");
        Rift.cmd(server, "item replace entity " + BOSS + " armor.chest with minecraft:netherite_chestplate");
        Rift.cmd(server, "item replace entity " + BOSS + " armor.legs with minecraft:netherite_leggings");
        Rift.cmd(server, "item replace entity " + BOSS + " armor.feet with minecraft:netherite_boots");
        equip(server);
        grace = 60;
    }

    /** Ein Schwert (Phase 1), zwei Schwerter (Phase 2) oder leere Hände (entwaffnet). */
    private static void equip(MinecraftServer server) {
        boolean two = lastHp < MAX_HP * 0.66F;
        Rift.cmd(server, "item replace entity " + BOSS + " weapon.mainhand with " + (disarmed ? "minecraft:air" : "minecraft:netherite_sword"));
        Rift.cmd(server, "item replace entity " + BOSS + " weapon.offhand with " + (!disarmed && two ? "minecraft:netherite_sword" : "minecraft:air"));
    }

    private static void fight(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        if (!placed) {
            // nach einem Server-Neustart mitten im Kampf
            bx = cx + 0.5;
            bz = cz + 0.5;
            placed = true;
        }
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
                    spawnBoss(server, top, Math.max(lastHp, MAX_HP * 0.1F)); // verschwunden: mit dem letzten Stand zurückholen
                }
            } else {
                Rift.cmd(server, "execute store result score #boss_hp sb run data get entity " + BOSS + " Health");
                Rift.cmd(server, "execute store result bossbar challenge:riss value run data get entity " + BOSS + " Health");
                float before = lastHp;
                lastHp = Math.max(1, Rift.score(server, "#boss_hp"));
                if (before >= MAX_HP * 0.66F && lastHp < MAX_HP * 0.66F && !disarmed) {
                    equip(server);
                    Rift.title(server, "ZWEI KLINGEN", "red", "Jetzt kommen die Schläge schneller und zu zweit");
                    Rift.sound(server, "minecraft:item.armor.equip_netherite", 0.6);
                    say(server, "Genug gespielt.");
                }
            }
            Rift.cmd(server, "bossbar set challenge:riss players @a");
        }
        if (players.isEmpty()) {
            return;
        }
        if (finale) {
            finaleTick(server, players, cx, top, cz);
        } else if (disarmed) {
            disarmTick(server, players, cx, top, cz);
        } else {
            duelTick(server, players, top);
        }
    }

    // ------------------------------------------------------------------ Phase 1 und 2: das Duell
    private static void startDuel(MinecraftServer server, List<ServerPlayer> players, String next) {
        duelist = next;
        exchanges = 0;
        stage = BOW;
        timer = 60;
        bowed = false;
        angry = false;
        comboOk = true;
        glow(server, false);
        if (next.isEmpty()) {
            return;
        }
        titleTo(server, next, "DUELL", "red", "Schleichen = verbeugen. Nur du kannst ihn treffen");
        for (ServerPlayer other : players) {
            if (!other.getScoreboardName().equals(next)) {
                titleTo(server, other.getScoreboardName(), "WACHE", "light_purple", "Schlag die Schattenklingen weg, die auf " + next + " zufliegen");
            }
        }
        Rift.sound(server, "minecraft:block.bell.use", 0.7);
    }

    private static String otherThan(List<ServerPlayer> players, String name) {
        for (ServerPlayer player : players) {
            if (!player.getScoreboardName().equals(name)) {
                return player.getScoreboardName();
            }
        }
        return players.get(0).getScoreboardName();
    }

    private static void duelTick(MinecraftServer server, List<ServerPlayer> players, int top) {
        ServerPlayer d = find(players, duelist);
        if (d == null) {
            startDuel(server, players, players.get(0).getScoreboardName());
            return;
        }
        boolean two = lastHp < MAX_HP * 0.66F;
        String in = in(server);
        // der Duellkreis, und wer nicht dran ist, bleibt draußen
        if (clock % 4 == 0) {
            for (int k = 0; k < 20; k++) {
                double a = 2 * Math.PI * k / 20 + clock * 0.02;
                Rift.cmd(server, in + "particle minecraft:end_rod " + fmt(bx + 7 * Math.cos(a)) + " " + (top + 0.3) + " "
                        + fmt(bz + 7 * Math.sin(a)) + " 0 0 0 0 1 force");
            }
        }
        for (ServerPlayer other : players) {
            if (other != d && distance(other) < 6.3 && distance(other) > 0.3) {
                double dist = distance(other);
                fling(other, (other.getX() - bx) / dist * 0.7, 0.3, (other.getZ() - bz) / dist * 0.7);
                hint(server, other, "Das ist nicht dein Duell. Halte die Schattenklingen fern!");
            }
        }
        blades(server, d, top, two);

        if (stage == BOW) {
            pose(server, top, duelist, 0);
            if (!bowed && d.isShiftKeyDown()) {
                bowed = true;
                Rift.cmd(server, "effect give " + duelist + " minecraft:resistance 30 0 true");
                say(server, "Manieren. Selten geworden.");
                d.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7Du hast dich verbeugt: 30 Sekunden Schutz."));
            }
            if (--timer <= 0) {
                stage = IDLE;
                timer = 30;
                comboLeft = two ? 2 : 1;
                comboOk = true;
            }
        } else if (stage == IDLE) {
            pose(server, top, duelist, 0);
            if (--timer <= 0) {
                beginTelegraph(server, d, two);
            }
        } else if (stage == TELEGRAPH) {
            telegraph(server, d, top);
            if (--timer <= 0) {
                resolve(server, players, d, top, two);
            }
        } else {
            pose(server, top, duelist, 0);
            if (clock % 5 == 0) {
                Rift.cmd(server, in + "particle minecraft:crit " + fmt(bx) + " " + (top + 2.5) + " " + fmt(bz) + " 0.6 1 0.6 0.2 12 force");
            }
            if (--timer <= 0) {
                glow(server, false);
                endExchange(server, players, two);
            }
        }
    }

    private static void beginTelegraph(MinecraftServer server, ServerPlayer d, boolean two) {
        int next = RANDOM.nextInt(4);
        if (next == attack) {
            next = (next + 1 + RANDOM.nextInt(3)) % 4;
        }
        attack = next;
        stage = TELEGRAPH;
        timer = (int) ((two ? 20 : 28) * (angry ? 0.75 : 1.0));
        double dist = Math.max(0.01, distance(d));
        lineX = (d.getX() - bx) / dist;
        lineZ = (d.getZ() - bz) / dist;
        titleTo(server, duelist, ATTACK_NAME[attack], ATTACK_COLOR[attack], two ? "" : ATTACK_HINT[attack]);
        Rift.sound(server, ATTACK_SOUND[attack], attack == HIEB ? 1.6 : 0.8);
    }

    /** Die sichtbare Ankündigung des Schlags. */
    private static void telegraph(MinecraftServer server, ServerPlayer d, int top) {
        String in = in(server);
        if (attack == HIEB) {
            pose(server, top + 1.5, duelist, 0); // er springt hoch und holt aus
            if (clock % 2 == 0) {
                Rift.cmd(server, in + "particle minecraft:crit " + fmt(bx) + " " + (top + 6) + " " + fmt(bz) + " 0.4 0.4 0.4 0.1 8 force");
            }
        } else if (attack == FEGER) {
            pose(server, top, duelist, 0);
            if (clock % 2 == 0) {
                for (int k = 0; k < 10; k++) {
                    double a = 2 * Math.PI * k / 10 + clock * 0.3;
                    Rift.cmd(server, in + "particle minecraft:sweep_attack " + fmt(bx + 3.5 * Math.cos(a)) + " " + (top + 0.3) + " "
                            + fmt(bz + 3.5 * Math.sin(a)) + " 0 0 0 0 1 force");
                }
            }
        } else if (attack == STICH) {
            pose(server, top, duelist, 0);
            if (clock % 2 == 0) {
                for (int k = 1; k <= 10; k++) {
                    Rift.cmd(server, in + "particle minecraft:end_rod " + fmt(bx + lineX * k) + " " + (top + 1.3) + " "
                            + fmt(bz + lineZ * k) + " 0 0 0 0 1 force");
                }
            }
        } else {
            pose(server, top, null, (clock * 45.0) % 360.0); // er dreht sich
            if (clock % 2 == 0) {
                for (int k = 0; k < 12; k++) {
                    double a = 2 * Math.PI * k / 12 - clock * 0.4;
                    Rift.cmd(server, in + "particle minecraft:cloud " + fmt(bx + 4.4 * Math.cos(a)) + " " + (top + 1.0) + " "
                            + fmt(bz + 4.4 * Math.sin(a)) + " 0 0 0 0 1 force");
                }
            }
        }
    }

    /** Der Schlag trifft: Hat der Duellant richtig geantwortet? */
    private static void resolve(MinecraftServer server, List<ServerPlayer> players, ServerPlayer d, int top, boolean two) {
        double dx = d.getX() - bx;
        double dz = d.getZ() - bz;
        double dist = Math.hypot(dx, dz);
        boolean fled = dist > 8.5; // außerhalb des Kreises zählt nicht als Antwort
        boolean hit;
        if (attack == HIEB) {
            hit = !d.isBlocking();
        } else if (attack == FEGER) {
            hit = d.onGround();
        } else if (attack == STICH) {
            double along = dx * lineX + dz * lineZ;
            hit = along > 0 && along < 10.5 && Math.abs(dx * lineZ - dz * lineX) < 1.1;
        } else {
            hit = dist < 4.6;
        }
        String in = in(server);
        Rift.cmd(server, in + "particle minecraft:sweep_attack " + fmt(bx + lineX * 2) + " " + (top + 1.5) + " " + fmt(bz + lineZ * 2)
                + " 1 0.5 1 0 6 force");
        if (!hit && !fled) {
            Rift.sound(server, attack == HIEB ? "minecraft:item.shield.block" : "minecraft:entity.experience_orb.pickup", 1.0);
            comboLeft--;
        } else {
            comboOk = false;
            comboLeft = 0;
            if (hit && !fled) {
                Rift.cmd(server, "damage " + duelist + " " + ATTACK_DAMAGE[attack] + " minecraft:mob_attack");
                Rift.sound(server, "minecraft:entity.player.attack.crit", 0.7);
                say(server, TAUNTS[RANDOM.nextInt(TAUNTS.length)]);
            } else {
                say(server, "Lauf nur. Ein Duell gewinnt man nicht von da hinten.");
            }
        }
        if (comboLeft > 0) {
            stage = IDLE;
            timer = 8;
        } else if (comboOk) {
            stage = STAGGER;
            timer = two ? 50 : 60;
            glow(server, true);
            titleTo(server, duelist, "JETZT!", "green", "Er ist aus dem Gleichgewicht – schlag zu");
            Rift.sound(server, "minecraft:entity.iron_golem.damage", 0.7);
        } else {
            endExchange(server, players, two);
        }
    }

    private static void endExchange(MinecraftServer server, List<ServerPlayer> players, boolean two) {
        exchanges++;
        if (exchanges >= 3) {
            startDuel(server, players, otherThan(players, duelist));
            return;
        }
        stage = IDLE;
        timer = 35;
        comboLeft = two ? 2 : 1;
        comboOk = true;
    }

    /** Schattenklingen: schwebende Schwerter, die auf den Duellanten zufliegen. Ein Treffer zerstört sie. */
    private static void blades(MinecraftServer server, ServerPlayer d, int top, boolean two) {
        String in = in(server);
        if (--bladeTimer <= 0) {
            bladeTimer = two ? 40 : 60;
            Rift.cmd(server, "execute store result score #adds_n sb if entity @e[tag=riss_blade]");
            if (Rift.score(server, "#adds_n") < (two ? 5 : 3)) {
                for (int i = 0; i < (two ? 2 : 1); i++) {
                    double a = RANDOM.nextDouble() * Math.PI * 2;
                    Rift.cmd(server, in + "summon minecraft:vex " + fmt(bx + 14 * Math.cos(a)) + " " + (top + 2.5) + " " + fmt(bz + 14 * Math.sin(a))
                            + " {Tags:[\"riss_blade\",\"riss_add\"],NoAI:1b,Silent:1b,PersistenceRequired:1b,"
                            + "equipment:{mainhand:{id:\"minecraft:netherite_sword\",count:1}}}");
                }
                Rift.cmd(server, "execute as @e[tag=riss_blade] run attribute @s minecraft:max_health base set 1");
                Rift.cmd(server, "execute as @e[tag=riss_blade] run attribute @s minecraft:scale base set 1.8");
                Rift.cmd(server, "execute as @e[tag=riss_blade] run data merge entity @s {Health:1f}");
                Rift.cmd(server, "effect give @e[tag=riss_blade] minecraft:invisibility infinite 0 true");
                Rift.cmd(server, "effect give @e[tag=riss_blade] minecraft:glowing infinite 0 true");
            }
        }
        Rift.cmd(server, "execute as @e[tag=riss_blade] at @s facing entity " + duelist + " eyes run tp @s ^ ^ ^0.19");
        if (clock % 2 == 0) {
            String near = "execute at " + duelist + " positioned ~ ~1.2 ~ ";
            Rift.cmd(server, near + "if entity @e[tag=riss_blade,distance=..1.3] run damage " + duelist + " 5 minecraft:mob_attack");
            Rift.cmd(server, near + "run kill @e[tag=riss_blade,distance=..1.3]");
        }
    }

    // ------------------------------------------------------------------ Phase 3: entwaffnet
    private static void spawnSword(MinecraftServer server, int cx, int top, int cz) {
        double a = RANDOM.nextDouble() * Math.PI * 2;
        double r = 5 + RANDOM.nextDouble() * 3;
        swordSpot = new double[] {cx + 0.5 + r * Math.cos(a), top, cz + 0.5 + r * Math.sin(a)};
        Rift.cmd(server, "kill @e[tag=riss_sword]");
        Rift.cmd(server, in(server) + "summon minecraft:item_display " + fmt(swordSpot[0]) + " " + (top + 1.4) + " " + fmt(swordSpot[2])
                + " {Tags:[\"riss_sword\"],Glowing:1b,item:{id:\"" + BLADE + "\",count:1},"
                + "transformation:{left_rotation:[0f,0f,-0.92f,0.38f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[2.5f,2.5f,2.5f]},"
                + "brightness:{sky:15,block:15}}");
        Rift.sound(server, "minecraft:block.anvil.land", 1.4);
    }

    /** Liegt das Schwert in der Insel, kann es jeder aufheben. Geht es verloren, taucht es wieder auf. */
    private static void swordUpkeep(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        if (swordSpot != null) {
            if (clock % 5 == 0) {
                Rift.cmd(server, in(server) + "particle minecraft:enchanted_hit " + fmt(swordSpot[0]) + " " + (top + 1.2) + " "
                        + fmt(swordSpot[2]) + " 0.3 0.6 0.3 0 6 force");
            }
            for (ServerPlayer player : players) {
                if (Math.hypot(player.getX() - swordSpot[0], player.getZ() - swordSpot[2]) < 1.7) {
                    Rift.cmd(server, "kill @e[tag=riss_sword]");
                    Rift.cmd(server, "give " + player.getScoreboardName() + " " + BLADE);
                    swordSpot = null;
                    swordLost = 0;
                    Rift.broadcast(server, "§b✦ " + player.getScoreboardName() + " hält die Klinge des Meisters. §7Nur damit kann man ihn jetzt verletzen. "
                            + "Mit Q könnt ihr sie euch zuwerfen.");
                    Rift.sound(server, "minecraft:item.armor.equip_netherite", 1.2);
                    return;
                }
            }
            return;
        }
        boolean held = false;
        for (ServerPlayer player : players) {
            held |= holdsBlade(player) || player.getOffhandItem().is(ModItems.MASTER_BLADE);
        }
        if (held) {
            swordLost = 0;
        } else if (++swordLost > 400) {
            // 20 Sekunden lang hält sie niemand in der Hand: Sie steckt wieder in der Insel
            removeBlades(server);
            spawnSword(server, cx, top, cz);
            swordLost = 0;
        }
    }

    private static void takeBladeBack(MinecraftServer server, int cx, int top, int cz, String text) {
        removeBlades(server);
        spawnSword(server, cx, top, cz);
        say(server, text);
    }

    private static void disarmTick(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        if (!disarmAnnounced) {
            disarmAnnounced = true;
            stage = IDLE;
            Rift.cmd(server, "kill @e[tag=riss_blade]");
            glow(server, false);
            equip(server);
            spawnSword(server, cx, top, cz);
            Rift.title(server, "ENTWAFFNET", "aqua", "Holt euch sein Schwert – nur damit ist er verwundbar");
            say(server, "Mein Schwert! Fasst es nicht an!");
            Rift.cmd(server, "bossbar set challenge:riss color blue");
        }
        swordUpkeep(server, players, cx, top, cz);
        // er jagt den Träger des Schwerts, sonst den Nächsten
        ServerPlayer target = null;
        boolean carrier = false;
        for (ServerPlayer player : players) {
            boolean has = holdsBlade(player) || player.getOffhandItem().is(ModItems.MASTER_BLADE);
            if (target == null || (has && !carrier) || (has == carrier && distance(player) < distance(target))) {
                target = player;
                carrier = has;
            }
        }
        String name = target.getScoreboardName();
        double tx = target.getX() - bx;
        double tz = target.getZ() - bz;
        double dist = Math.hypot(tx, tz);
        if (catchCooldown > 0) {
            catchCooldown--;
        } else if (dist > 1.9) {
            bx += tx / dist * 0.25;
            bz += tz / dist * 0.25;
            // er bleibt auf dem Platz in der Mitte
            double fromCenter = Math.hypot(bx - (cx + 0.5), bz - (cz + 0.5));
            if (fromCenter > 9.3) {
                bx = cx + 0.5 + (bx - (cx + 0.5)) / fromCenter * 9.3;
                bz = cz + 0.5 + (bz - (cz + 0.5)) / fromCenter * 9.3;
            }
        } else {
            Rift.cmd(server, "damage " + name + " 8 minecraft:mob_attack");
            fling(target, tx / Math.max(dist, 0.1) * 0.9, 0.4, tz / Math.max(dist, 0.1) * 0.9);
            catchCooldown = 50;
            if (carrier) {
                takeBladeBack(server, cx, top, cz, "Das gehört mir.");
            }
        }
        // wer mit dem Schwert vom Platz flieht, verliert es
        if (carrier && Math.hypot(target.getX() - (cx + 0.5), target.getZ() - (cz + 0.5)) > 12.5) {
            if (++outside > 100) {
                outside = 0;
                takeBladeBack(server, cx, top, cz, "Weglaufen? Mit MEINEM Schwert?");
            } else if (outside % 20 == 1) {
                hint(server, target, "Bleib auf dem Platz in der Mitte, sonst kehrt das Schwert zu ihm zurück!");
            }
        } else {
            outside = 0;
        }
        pose(server, top, name, 0);
    }

    // ------------------------------------------------------------------ Finale
    private static void finaleTick(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        if (!finaleAnnounced) {
            finaleAnnounced = true;
            stage = IDLE;
            timer = 60;
            openWindow = 0;
            bx = cx + 0.5;
            bz = cz + 0.5;
            Rift.title(server, "LETZTES GEFECHT", "gold", "Einer blockt seinen Hieb, der andere schlägt mit dem Schwert zu");
            say(server, "Ich brauche kein Schwert für euch.");
            Rift.cmd(server, "bossbar set challenge:riss color yellow");
            Rift.sound(server, "minecraft:block.bell.resonate", 1.0);
        }
        swordUpkeep(server, players, cx, top, cz);
        // der Hieb gilt dem, der das Schwert NICHT hält
        ServerPlayer blocker = players.get(0);
        for (ServerPlayer player : players) {
            if (!holdsBlade(player)) {
                blocker = player;
                break;
            }
        }
        String name = blocker.getScoreboardName();
        if (openWindow > 0) {
            if (--openWindow == 0) {
                glow(server, false);
            } else if (clock % 5 == 0) {
                Rift.cmd(server, in(server) + "particle minecraft:crit " + fmt(bx) + " " + (top + 2.5) + " " + fmt(bz) + " 0.6 1 0.6 0.2 12 force");
            }
        }
        if (stage == IDLE) {
            pose(server, top, name, 0);
            if (openWindow == 0 && --timer <= 0) {
                stage = TELEGRAPH;
                timer = 30;
                attack = HIEB;
                titleTo(server, name, "HIEB", "red", "Schild hoch!");
                Rift.sound(server, ATTACK_SOUND[HIEB], 1.6);
            }
        } else {
            pose(server, top + 1.5, name, 0);
            if (clock % 2 == 0) {
                Rift.cmd(server, in(server) + "particle minecraft:crit " + fmt(bx) + " " + (top + 6) + " " + fmt(bz) + " 0.4 0.4 0.4 0.1 8 force");
            }
            if (--timer <= 0) {
                stage = IDLE;
                timer = 70;
                if (blocker.isBlocking() && distance(blocker) < 12) {
                    openWindow = 100;
                    glow(server, true);
                    Rift.sound(server, "minecraft:item.shield.block", 1.0);
                    Rift.title(server, "JETZT!", "green", "5 Sekunden: Schlag mit seinem Schwert zu");
                } else {
                    Rift.cmd(server, "damage " + name + " 7 minecraft:mob_attack");
                    say(server, TAUNTS[RANDOM.nextInt(TAUNTS.length)]);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Sieg
    private static void win(MinecraftServer server) {
        finishNow = false;
        killing = true;
        Rift.cmd(server, "kill @e[tag=riss_boss]");
        Rift.cmd(server, "kill @e[tag=riss_add]");
        Rift.cmd(server, "kill @e[tag=riss_sword]");
        killing = false;
        Rift.cmd(server, "bossbar remove challenge:riss");
        barShown = false;
        Rift.set(server, "#rift_phase", 4);
        say(server, "Gut gekämpft. Behaltet die Klinge.");
        Rift.broadcast(server, "§d✦ " + NAME + " ist besiegt. §7In 30 Sekunden bringt euch der Riss zurück in die Oberwelt.");
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
                return false; // nur Spieler können ihm etwas anhaben
            }
            String name = player.getScoreboardName();
            if (finale) {
                if (openWindow > 0 && holdsBlade(player)) {
                    finishNow = true;
                } else {
                    hint(server, player, openWindow > 0 ? "Nur seine eigene Klinge kann ihn besiegen."
                            : "Erst muss einer seinen Hieb mit dem Schild blocken.");
                }
                return false;
            }
            if (disarmed) {
                if (!holdsBlade(player)) {
                    hint(server, player, "Nur sein eigenes Schwert kann ihn jetzt verletzen.");
                    return false;
                }
                Long last = bladeHit.get(name);
                if (last != null && clock - last < 10) {
                    return false;
                }
                bladeHit.put(name, clock);
                float limit = entity.getMaxHealth() * 0.1F;
                float health = entity.getHealth() - 12.0F;
                if (health <= limit) {
                    health = limit;
                    finale = true;
                }
                entity.setHealth(health);
                Rift.sound(server, "minecraft:entity.player.attack.crit", 1.0);
                return false;
            }
            if (stage == BOW) {
                if (!angry) {
                    angry = true;
                    say(server, "Vor der Verbeugung? Ehrlos. Dann eben ohne Gnade.");
                }
                return false;
            }
            if (!name.equals(duelist)) {
                hint(server, player, "Das ist nicht dein Duell. Halte die Schattenklingen fern!");
                return false;
            }
            if (stage != STAGGER) {
                hint(server, player, "Er pariert. Antworte richtig auf seinen Schlag, dann ist er offen.");
                return false;
            }
            float limit = entity.getMaxHealth() * 0.33F;
            if (entity.getHealth() - amount <= limit) {
                entity.setHealth(limit);
                disarmed = true;
                return false;
            }
            return true;
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
