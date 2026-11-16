package de.challengemod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Der Bosskampf auf der Insel: „Der Ronin“ – ein Samurai mit eigener Skin-Textur und zwei eigenen Katanas.
 * Technisch ist er eine „Mannequin“-Figur (Spielermodell ohne eigene KI); Bewegung und Angriffe steuert dieser Code.
 *
 * Zu zweit: Er pariert immer die Schläge EINES Spielers (Oberwelt- oder Nether-Spieler), nur der andere trifft.
 *   Das wechselt alle paar Sekunden. Allein kann man ihn immer treffen.
 * Phase 1 (bis 2/3):  Doppelhiebe im Nahkampf, Sturmschnitt (Lichtlinie, dann rast er hindurch), wehrt Pfeile ab.
 * Phase 2 (bis 1/3):  dazu Klingenwurf (das dunkle Katana fliegt im Bogen und kehrt zurück), Schattenschritt, Wirbel.
 * Phase 3:            brennende Klingen, Trugbilder, Tausend Schnitte.
 * Finale (10 %):      Letzter Schnitt – beide müssen ihn treffen, während er ausholt.
 */
final class RiftBoss {
    static final String NAME = "Der Ronin";
    private static final float MAX_HP = 360.0F;
    private static final String BOSS = "@e[tag=riss_boss,limit=1]";
    private static final String NS = ChallengeMod.NS;
    private static final String PROFILE = "profile:{texture:\"" + NS + ":entity/ronin\",model:\"wide\"},hide_description:1b";
    private static final Random RANDOM = new Random();
    private static final int CHASE = 0, SLASH = 1, RECOVER = 2, AIM = 3, DASH = 4, THROW = 5, WHIRL = 6, CUTS = 7, FINAL = 8;
    private static final String[] TAUNTS = {"Zu langsam.", "Deine Deckung ist offen.", "Ein Schnitt genügt.", "Du zögerst.",
            "Kämpft ihr immer so?", "Steh auf."};

    /** Die Figur selbst (wird beim Laden über ihre Markierung gefunden) – nur für die Schlag-Animation. */
    private static LivingEntity body;
    private static long clock;
    private static int state;
    private static int timer;
    private static int special;       // Ticks bis zum nächsten Spezialangriff
    private static boolean placed;
    private static double bx;
    private static double by;
    private static double bz;
    private static double yaw;
    private static String target = "";
    private static int openRole = 1;  // welche Rolle ihn gerade treffen kann (1 = Oberwelt, 2 = Nether)
    private static int guardTimer;
    private static double lineX;
    private static double lineZ;
    private static double lineLength;
    private static final Set<String> struck = new HashSet<>();
    private static final List<double[]> cuts = new ArrayList<>();    // Punkt x, z und Richtung x, z
    private static final List<double[]> clones = new ArrayList<>();  // Nummer, x, z, Pause
    private static int cloneTimer;
    private static int cloneId;
    private static int announced = 1; // höchste angesagte Phase
    private static boolean finale;
    private static boolean finaleAnnounced;
    private static final long[] lastHit = {-1000, -1000, -1000};
    private static boolean finishNow;
    private static boolean killing;
    private static boolean has1;
    private static boolean has2;
    private static int grace;
    private static float lastHp = MAX_HP;
    private static final Map<String, Long> hinted = new HashMap<>();
    private static boolean errorLogged;
    private static boolean barShown;

    private RiftBoss() {
    }

    static void reset() {
        clock = 0;
        state = CHASE;
        timer = 0;
        special = 160;
        placed = false;
        target = "";
        openRole = 1;
        guardTimer = 0;
        struck.clear();
        cuts.clear();
        clones.clear();
        cloneTimer = 100;
        announced = 1;
        finale = false;
        finaleAnnounced = false;
        lastHit[1] = -1000;
        lastHit[2] = -1000;
        finishNow = false;
        killing = false;
        grace = 0;
        lastHp = MAX_HP;
        hinted.clear();
    }

    /** Vor dem Betreten der Insel und beim Zurücksetzen: Reste eines früheren Kampfes wegräumen. */
    static void prepare(MinecraftServer server) {
        reset();
        killing = true;
        for (String tag : new String[] {"riss_boss", "riss_add", "riss_halo", "riss_shard", "riss_sword", "riss_clone", "riss_fly"}) {
            Rift.cmd(server, "kill @e[tag=" + tag + "]");
        }
        if (Rift.findLevel(server, Rift.ARENA) != null) {
            // ältere Versionen haben in dieser Dimension versehentlich einen Enderdrachen erzeugt
            Rift.cmd(server, "execute in " + Rift.ARENA + " run kill @e[type=minecraft:ender_dragon,distance=0..]");
            Rift.cmd(server, "execute in " + Rift.ARENA + " run kill @e[type=minecraft:end_crystal,distance=0..]");
        }
        killing = false;
        body = null;
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

    static void resetCracks(MinecraftServer server, int cx, int top, int cz) {
        RiftIsland.ensure(cx, top, cz);
    }

    /** Fabric-Ereignis „Objekt geladen“: merkt sich die Boss-Figur. */
    static void onEntityLoad(Entity entity, ServerLevel level) {
        try {
            if (entity instanceof LivingEntity living && entity.entityTags().contains("riss_boss")) {
                body = living;
            }
        } catch (Throwable ignored) {
            // dann eben ohne Schlag-Animation
        }
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

    private static ServerPlayer nearest(List<ServerPlayer> players, double x, double z) {
        ServerPlayer best = players.get(0);
        for (ServerPlayer player : players) {
            if (Math.hypot(player.getX() - x, player.getZ() - z) < Math.hypot(best.getX() - x, best.getZ() - z)) {
                best = player;
            }
        }
        return best;
    }

    private static double distance(ServerPlayer player) {
        return Math.hypot(player.getX() - bx, player.getZ() - bz);
    }

    private static void say(MinecraftServer server, String text) {
        Rift.broadcast(server, "§8<§c" + NAME + "§8> §f" + text);
    }

    private static void swing(boolean mainHand) {
        try {
            if (body != null && body.isAlive()) {
                body.swing(mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, SwingAnimation.DEFAULT, true);
            }
        } catch (Throwable ignored) {
            // nur Optik
        }
    }

    private static void pose(MinecraftServer server, boolean crouch) {
        Rift.cmd(server, "data merge entity " + BOSS + " {pose:\"" + (crouch ? "crouching" : "standing") + "\"}");
    }

    private static void face(double x, double z) {
        if (Math.abs(x - bx) + Math.abs(z - bz) > 0.01) {
            yaw = -Math.toDegrees(Math.atan2(x - bx, z - bz));
        }
    }

    /** Schritt in eine Richtung; er bleibt auf der Insel und folgt dem Boden. */
    private static void move(ServerLevel level, int cx, int top, int cz, double dx, double dz) {
        bx += dx;
        bz += dz;
        double fromCenter = Math.hypot(bx - (cx + 0.5), bz - (cz + 0.5));
        if (fromCenter > 28) {
            bx = cx + 0.5 + (bx - (cx + 0.5)) / fromCenter * 28;
            bz = cz + 0.5 + (bz - (cz + 0.5)) / fromCenter * 28;
        }
        by = ground(level, top, bx, bz);
    }

    private static double ground(ServerLevel level, int top, double x, double z) {
        if (level == null) {
            return top;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        return y < top - 4 || y > top + 6 ? top : y;
    }

    /** Treffer auf einen Spieler. Blockbare Schläge hält ein erhobener Schild auf. */
    private static void hurt(MinecraftServer server, ServerPlayer player, int amount, boolean blockable) {
        if (blockable && player.isBlocking()) {
            Rift.cmd(server, "execute at " + player.getScoreboardName() + " run playsound minecraft:item.shield.block master @a ~ ~ ~ 1 1");
            return;
        }
        Rift.cmd(server, "damage " + player.getScoreboardName() + " " + amount + " minecraft:mob_attack");
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
                    one |= Rift.role(server, player) == 1;
                    two |= Rift.role(server, player) == 2;
                }
            }
            has1 = one;
            has2 = two;
            if (phase == 2) {
                for (ServerPlayer player : players) {
                    if (Math.hypot(player.getX() - cx, player.getZ() - cz) < 16) {
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
        bx = cx + 0.5;
        bz = cz + 0.5;
        by = top;
        placed = true;
        Rift.cmd(server, "team add riss_a");
        Rift.cmd(server, "team modify riss_a color aqua");
        Rift.cmd(server, "team add riss_b");
        Rift.cmd(server, "team modify riss_b color red");
        Rift.cmd(server, "bossbar add challenge:riss \"" + NAME + "\"");
        Rift.cmd(server, "bossbar set challenge:riss max " + (int) MAX_HP);
        Rift.cmd(server, "bossbar set challenge:riss style notched_6");
        Rift.cmd(server, "bossbar set challenge:riss color red");
        Rift.cmd(server, "bossbar set challenge:riss players @a");
        barShown = true;
        spawnBoss(server, MAX_HP);
        Rift.set(server, "#rift_phase", 3);
        Rift.title(server, "DER RONIN", "red", "Zwei Klingen. Ein Meister ohne Herr.");
        Rift.sound(server, "minecraft:entity.wither.spawn", 1.2);
        Rift.broadcast(server, "§c✦ Der Ronin §7pariert immer die Schläge von einem von euch – nur der andere trifft. "
                + "§bLeuchtet er blau§7, trifft der Oberwelt-Spieler. §cLeuchtet er rot§7, trifft der Nether-Spieler. "
                + "Ein Schild hält seine Hiebe auf. Pfeile wehrt er ab.");
        guard(server, RANDOM.nextBoolean() ? 1 : 2);
    }

    private static void spawnBoss(MinecraftServer server, float hp) {
        Rift.cmd(server, in(server) + "summon minecraft:mannequin " + fmt(bx) + " " + fmt(by) + " " + fmt(bz)
                + " {Tags:[\"riss_boss\"]," + PROFILE + ",CustomName:\"" + NAME + "\",CustomNameVisible:1b}");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:scale base set 1.5");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:max_health base set " + (int) MAX_HP);
        Rift.cmd(server, "attribute " + BOSS + " minecraft:knockback_resistance base set 1");
        Rift.cmd(server, "data merge entity " + BOSS + " {Health:" + hp + "f}");
        Rift.cmd(server, "effect give " + BOSS + " minecraft:glowing infinite 0 true");
        equip(server, true);
        Rift.cmd(server, "team join " + (openRole == 1 ? "riss_a" : "riss_b") + " @e[tag=riss_boss]");
        grace = 60;
    }

    /** Die beiden Katanas; ab Phase 3 brennen sie. */
    private static void equip(MinecraftServer server, boolean both) {
        boolean burning = lastHp < MAX_HP * 0.33F;
        Rift.cmd(server, "item replace entity " + BOSS + " weapon.mainhand with " + NS + (burning ? ":katana_flame" : ":katana_light"));
        Rift.cmd(server, "item replace entity " + BOSS + " weapon.offhand with " + (both ? NS + (burning ? ":katana_soul" : ":katana_dark") : "minecraft:air"));
    }

    /** Wer ihn treffen kann, wechselt: blau = Oberwelt-Spieler, rot = Nether-Spieler. */
    private static void guard(MinecraftServer server, int role) {
        openRole = role;
        guardTimer = 150 + RANDOM.nextInt(60);
        Rift.cmd(server, "team join " + (role == 1 ? "riss_a" : "riss_b") + " @e[tag=riss_boss]");
        Rift.cmd(server, "bossbar set challenge:riss color " + (role == 1 ? "blue" : "red"));
        Rift.cmd(server, "bossbar set challenge:riss name \"" + NAME + (role == 1 ? " – offen für den Oberwelt-Spieler" : " – offen für den Nether-Spieler") + "\"");
        Rift.sound(server, "minecraft:item.armor.equip_netherite", 1.4);
    }

    private static boolean canHit(int role) {
        return state == THROW || !(has1 && has2) || role == openRole;
    }

    private static void fight(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        ServerLevel level = Rift.findLevel(server, dimension(server));
        if (!placed) {
            bx = cx + 0.5; // nach einem Server-Neustart mitten im Kampf
            bz = cz + 0.5;
            by = top;
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
                    spawnBoss(server, Math.max(lastHp, MAX_HP * 0.1F)); // verschwunden: mit dem letzten Stand zurückholen
                }
            } else {
                Rift.cmd(server, "execute store result score #boss_hp sb run data get entity " + BOSS + " Health");
                Rift.cmd(server, "execute store result bossbar challenge:riss value run data get entity " + BOSS + " Health");
                lastHp = Math.max(1, Rift.score(server, "#boss_hp"));
            }
            Rift.cmd(server, "bossbar set challenge:riss players @a");
        }
        if (players.isEmpty()) {
            return;
        }
        int phase = lastHp < MAX_HP * 0.33F ? 3 : lastHp < MAX_HP * 0.66F ? 2 : 1;
        if (phase > announced) {
            announced = phase;
            if (phase == 2) {
                Rift.title(server, "DER STURM", "light_purple", "Klingenwurf, Schattenschritt und Wirbel");
                say(server, "Ihr seid besser, als ihr ausseht.");
            } else {
                equip(server, true);
                Rift.title(server, "DER DÄMON", "gold", "Brennende Klingen, Trugbilder, Tausend Schnitte");
                say(server, "Dann zeige ich euch, warum ich keinen Herrn mehr habe.");
            }
            Rift.sound(server, "minecraft:entity.ender_dragon.growl", 1.3);
        }
        if (finale) {
            finaleTick(server, players);
        } else {
            if (--guardTimer <= 0 && has1 && has2) {
                guard(server, 3 - openRole);
            }
            if (clock % 20 == 0 && has1 && has2 && state != THROW) {
                for (ServerPlayer player : players) {
                    boolean open = Rift.role(server, player) == openRole;
                    Rift.cmd(server, "title " + player.getScoreboardName() + " actionbar {text:\""
                            + (open ? "Du kannst ihn treffen!" : "Er pariert deine Schläge – lenk ihn ab") + "\",color:\"" + (open ? "green" : "red") + "\"}");
                }
            }
            act(server, level, players, cx, top, cz, phase);
            if (phase == 3) {
                clones(server, level, players, cx, top, cz);
                if (clock % 4 == 0) {
                    Rift.cmd(server, in(server) + "particle minecraft:flame " + fmt(bx) + " " + fmt(by + 1.6) + " " + fmt(bz) + " 0.5 0.6 0.5 0.01 3 force");
                    Rift.cmd(server, in(server) + "particle minecraft:soul_fire_flame " + fmt(bx) + " " + fmt(by + 1.6) + " " + fmt(bz) + " 0.5 0.6 0.5 0.01 3 force");
                }
            }
        }
        Rift.cmd(server, in(server) + "tp " + BOSS + " " + fmt(bx) + " " + fmt(by) + " " + fmt(bz) + " " + fmt(yaw) + " 0");
    }

    // ------------------------------------------------------------------ Angriffe
    private static void act(MinecraftServer server, ServerLevel level, List<ServerPlayer> players, int cx, int top, int cz, int phase) {
        ServerPlayer t = find(players, target);
        if (t == null || (state == CHASE && clock % 100 == 0)) {
            t = nearest(players, bx, bz);
            target = t.getScoreboardName();
        }
        double dx = t.getX() - bx;
        double dz = t.getZ() - bz;
        double dist = Math.max(0.01, Math.hypot(dx, dz));
        String in = in(server);
        if (state == CHASE) {
            face(t.getX(), t.getZ());
            if (dist > 2.6) {
                double speed = phase == 1 ? 0.2 : 0.23;
                move(level, cx, top, cz, dx / dist * speed, dz / dist * speed);
            }
            if (--special <= 0 && dist > 3.5) {
                special = phase == 1 ? 170 : phase == 2 ? 130 : 105;
                startSpecial(server, level, players, t, cx, top, cz, phase);
            } else if (dist <= 2.8) {
                state = SLASH;
                timer = 18;
            }
        } else if (state == SLASH) {
            face(t.getX(), t.getZ());
            if (timer == 14 || timer == 6) {
                swing(timer == 14);
                Rift.cmd(server, in + "playsound minecraft:entity.player.attack.sweep master @a " + fmt(bx) + " " + fmt(by) + " " + fmt(bz) + " 1 " + (timer == 14 ? "0.9" : "1.2"));
                Rift.cmd(server, in + "particle minecraft:sweep_attack " + fmt(bx + dx / dist * 1.6) + " " + fmt(by + 1.6) + " " + fmt(bz + dz / dist * 1.6) + " 0.3 0.2 0.3 0 2 force");
                for (ServerPlayer player : players) {
                    if (distance(player) < 3.4) {
                        hurt(server, player, phase == 3 ? 7 : 6, true);
                    }
                }
            }
            if (--timer <= 0) {
                state = RECOVER;
                timer = phase == 1 ? 30 : 22;
            }
        } else if (state == RECOVER) {
            if (--timer <= 0) {
                state = CHASE;
            }
        } else if (state == AIM) {
            if (clock % 2 == 0) {
                for (int k = 1; k <= 14; k++) {
                    double f = lineLength * k / 14;
                    Rift.cmd(server, in + "particle minecraft:end_rod " + fmt(bx + lineX * f) + " " + fmt(by + 1.0) + " " + fmt(bz + lineZ * f) + " 0 0 0 0 1 force");
                }
            }
            if (--timer <= 0) {
                state = DASH;
                timer = 6;
                struck.clear();
                pose(server, false);
                swing(true);
                Rift.sound(server, "minecraft:item.trident.riptide_1", 1.2);
            }
        } else if (state == DASH) {
            move(level, cx, top, cz, lineX * lineLength / 6, lineZ * lineLength / 6);
            Rift.cmd(server, in + "particle minecraft:sweep_attack " + fmt(bx) + " " + fmt(by + 1.4) + " " + fmt(bz) + " 0.6 0.3 0.6 0 4 force");
            for (ServerPlayer player : players) {
                if (distance(player) < 2.2 && struck.add(player.getScoreboardName())) {
                    hurt(server, player, 9, true);
                }
            }
            if (--timer <= 0) {
                swing(false);
                state = RECOVER;
                timer = 30;
            }
        } else if (state == THROW) {
            // das dunkle Katana fliegt im Bogen hinaus und kehrt zurück
            double p = 1 - timer / 50.0;
            double out = 12 * Math.sin(Math.PI * p);
            double side = 3.5 * Math.sin(2 * Math.PI * p);
            double fx = bx + lineX * out - lineZ * side;
            double fz = bz + lineZ * out + lineX * side;
            Rift.cmd(server, in + "tp @e[tag=riss_fly,limit=1] " + fmt(fx) + " " + fmt(by + 1.3) + " " + fmt(fz) + " " + fmt((clock * 50) % 360) + " 0");
            for (ServerPlayer player : players) {
                if (Math.hypot(player.getX() - fx, player.getZ() - fz) < 1.5 && struck.add(player.getScoreboardName())) {
                    hurt(server, player, 7, true);
                }
            }
            if (timer == 25) {
                struck.clear(); // auf dem Rückweg kann es noch einmal treffen
            }
            if (--timer <= 0) {
                Rift.cmd(server, "kill @e[tag=riss_fly]");
                equip(server, true);
                state = RECOVER;
                timer = 15;
            }
        } else if (state == WHIRL) {
            yaw = (yaw + 42) % 360;
            move(level, cx, top, cz, dx / dist * 0.27, dz / dist * 0.27);
            if (clock % 2 == 0) {
                double a = clock * 0.9;
                Rift.cmd(server, in + "particle minecraft:sweep_attack " + fmt(bx + 2 * Math.cos(a)) + " " + fmt(by + 1.4) + " " + fmt(bz + 2 * Math.sin(a)) + " 0 0 0 0 1 force");
            }
            if (timer % 8 == 0) {
                swing(timer % 16 == 0);
                Rift.cmd(server, in + "playsound minecraft:entity.player.attack.sweep master @a " + fmt(bx) + " " + fmt(by) + " " + fmt(bz) + " 1 1.4");
                for (ServerPlayer player : players) {
                    double d = distance(player);
                    if (d < 3.0 && d > 0.05) {
                        hurt(server, player, 5, true);
                        fling(player, (player.getX() - bx) / d * 0.8, 0.35, (player.getZ() - bz) / d * 0.8);
                    }
                }
            }
            if (--timer <= 0) {
                state = RECOVER;
                timer = 40; // ihm ist schwindlig – gute Gelegenheit
            }
        } else if (state == CUTS) {
            if (clock % 3 == 0) {
                for (double[] c : cuts) {
                    for (int k = -6; k <= 6; k++) {
                        Rift.cmd(server, in + "particle minecraft:end_rod " + fmt(c[0] + c[2] * k * 2.6) + " " + fmt(by + 0.6) + " " + fmt(c[1] + c[3] * k * 2.6) + " 0 0 0 0 1 force");
                    }
                }
            }
            if (--timer <= 0) {
                swing(true);
                swing(false);
                pose(server, false);
                Rift.sound(server, "minecraft:entity.player.attack.crit", 0.6);
                for (ServerPlayer player : players) {
                    for (double[] c : cuts) {
                        double px = player.getX() - c[0];
                        double pz = player.getZ() - c[1];
                        if (Math.abs(px * c[3] - pz * c[2]) < 0.9 && Math.abs(px * c[2] + pz * c[3]) < 17) {
                            hurt(server, player, 8, false);
                            break;
                        }
                    }
                }
                cuts.clear();
                state = RECOVER;
                timer = 35;
            }
        }
    }

    private static void startSpecial(MinecraftServer server, ServerLevel level, List<ServerPlayer> players, ServerPlayer t,
                                     int cx, int top, int cz, int phase) {
        double dx = t.getX() - bx;
        double dz = t.getZ() - bz;
        double dist = Math.max(0.01, Math.hypot(dx, dz));
        lineX = dx / dist;
        lineZ = dz / dist;
        face(t.getX(), t.getZ());
        int pick = phase == 1 ? 0 : RANDOM.nextInt(phase == 2 ? 4 : 5);
        String in = in(server);
        if (pick == 0) {                 // Sturmschnitt
            state = AIM;
            timer = phase == 1 ? 24 : 18;
            lineLength = Math.min(16, dist + 5);
            pose(server, true);
            Rift.sound(server, "minecraft:item.trident.throw", 0.6);
        } else if (pick == 1) {          // Klingenwurf
            state = THROW;
            timer = 50;
            struck.clear();
            swing(false);
            equip(server, false);
            Rift.cmd(server, "kill @e[tag=riss_fly]");
            Rift.cmd(server, in + "summon minecraft:item_display " + fmt(bx) + " " + fmt(by + 1.3) + " " + fmt(bz)
                    + " {Tags:[\"riss_fly\"],teleport_duration:1,item:{id:\"" + NS + (phase == 3 ? ":katana_soul" : ":katana_dark") + "\",count:1},"
                    + "transformation:{left_rotation:[0.7f,0f,0f,0.7f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[2f,2f,2f]},"
                    + "brightness:{sky:15,block:15}}");
            Rift.sound(server, "minecraft:item.trident.throw", 1.0);
            Rift.cmd(server, "title @a actionbar {text:\"Klingenwurf – jetzt hat er nur eine Klinge: beide können ihn treffen!\",color:\"yellow\"}");
        } else if (pick == 2) {          // Schattenschritt: hinter das Ziel
            Rift.cmd(server, in + "particle minecraft:large_smoke " + fmt(bx) + " " + fmt(by + 1) + " " + fmt(bz) + " 0.4 0.8 0.4 0.02 30 force");
            double look = Math.toRadians(t.getYRot());
            bx = t.getX() + Math.sin(look) * 2.2;
            bz = t.getZ() - Math.cos(look) * 2.2;
            move(level, cx, top, cz, 0, 0);
            face(t.getX(), t.getZ());
            Rift.cmd(server, in + "particle minecraft:large_smoke " + fmt(bx) + " " + fmt(by + 1) + " " + fmt(bz) + " 0.4 0.8 0.4 0.02 30 force");
            Rift.cmd(server, in + "playsound minecraft:entity.enderman.teleport master @a " + fmt(bx) + " " + fmt(by) + " " + fmt(bz) + " 1 0.7");
            state = SLASH;
            timer = 26;
        } else if (pick == 3) {          // Wirbel
            state = WHIRL;
            timer = 64;
            Rift.sound(server, "minecraft:entity.breeze.wind_burst", 0.8);
        } else {                         // Tausend Schnitte
            state = CUTS;
            timer = 34;
            cuts.clear();
            for (int i = 0; i < 6; i++) {
                ServerPlayer around = players.get(RANDOM.nextInt(players.size()));
                double a = RANDOM.nextDouble() * Math.PI;
                cuts.add(new double[] {around.getX() + RANDOM.nextDouble() * 10 - 5, around.getZ() + RANDOM.nextDouble() * 10 - 5, Math.cos(a), Math.sin(a)});
            }
            pose(server, true);
            Rift.sound(server, "minecraft:block.bell.resonate", 1.6);
            Rift.cmd(server, "title @a actionbar {text:\"Tausend Schnitte – weg von den Lichtlinien!\",color:\"gold\"}");
        }
    }

    /** Phase 3: Trugbilder – Kopien, die mitkämpfen. Ein Treffer lässt sie zerplatzen. */
    private static void clones(MinecraftServer server, ServerLevel level, List<ServerPlayer> players, int cx, int top, int cz) {
        String in = in(server);
        if (--cloneTimer <= 0) {
            cloneTimer = 320;
            while (clones.size() < 2) {
                int id = cloneId++ % 1000;
                double a = RANDOM.nextDouble() * Math.PI * 2;
                double x = bx + 4 * Math.cos(a);
                double z = bz + 4 * Math.sin(a);
                clones.add(new double[] {id, x, z, 30});
                Rift.cmd(server, in + "summon minecraft:mannequin " + fmt(x) + " " + fmt(by) + " " + fmt(z)
                        + " {Tags:[\"riss_clone\",\"riss_add\",\"riss_c" + id + "\"]," + PROFILE + ",CustomName:\"" + NAME + "\",CustomNameVisible:1b}");
                String clone = "@e[tag=riss_c" + id + ",limit=1]";
                Rift.cmd(server, "attribute " + clone + " minecraft:scale base set 1.5");
                Rift.cmd(server, "attribute " + clone + " minecraft:max_health base set 1");
                Rift.cmd(server, "item replace entity " + clone + " weapon.mainhand with " + NS + ":katana_flame");
                Rift.cmd(server, "item replace entity " + clone + " weapon.offhand with " + NS + ":katana_soul");
                Rift.cmd(server, in + "particle minecraft:large_smoke " + fmt(x) + " " + fmt(by + 1) + " " + fmt(z) + " 0.4 0.8 0.4 0.02 20 force");
            }
            Rift.sound(server, "minecraft:entity.illusioner.mirror_move", 1.0);
        }
        for (Iterator<double[]> it = clones.iterator(); it.hasNext();) {
            double[] c = it.next();
            String clone = "@e[tag=riss_c" + (int) c[0] + ",limit=1]";
            if (clock % 10 == 0) {
                Rift.cmd(server, "execute store result score #clone_n sb if entity " + clone);
                if (Rift.score(server, "#clone_n") == 0) {
                    Rift.cmd(server, in + "particle minecraft:poof " + fmt(c[1]) + " " + fmt(by + 1) + " " + fmt(c[2]) + " 0.3 0.6 0.3 0.05 20 force");
                    it.remove();
                    continue;
                }
            }
            ServerPlayer t = nearest(players, c[1], c[2]);
            double dx = t.getX() - c[1];
            double dz = t.getZ() - c[2];
            double dist = Math.max(0.01, Math.hypot(dx, dz));
            if (dist > 2.2) {
                c[1] += dx / dist * 0.19;
                c[2] += dz / dist * 0.19;
            } else if (c[3] <= 0) {
                c[3] = 30;
                hurt(server, t, 3, true);
            }
            c[3]--;
            Rift.cmd(server, in + "tp " + clone + " " + fmt(c[1]) + " " + fmt(ground(level, top, c[1], c[2])) + " " + fmt(c[2])
                    + " " + fmt(-Math.toDegrees(Math.atan2(dx, dz))) + " 0");
        }
    }

    // ------------------------------------------------------------------ Finale: Letzter Schnitt
    private static void finaleTick(MinecraftServer server, List<ServerPlayer> players) {
        String in = in(server);
        if (!finaleAnnounced) {
            finaleAnnounced = true;
            state = FINAL;
            timer = 110;
            lastHit[1] = -1000;
            lastHit[2] = -1000;
            Rift.cmd(server, "kill @e[tag=riss_clone]");
            Rift.cmd(server, "kill @e[tag=riss_fly]");
            clones.clear();
            equip(server, true);
            pose(server, true);
            Rift.cmd(server, "team leave @e[tag=riss_boss]");
            Rift.cmd(server, "bossbar set challenge:riss color yellow");
            Rift.cmd(server, "bossbar set challenge:riss name \"" + NAME + " – trefft ihn BEIDE, bevor er zuschlägt\"");
            Rift.title(server, "LETZTER SCHNITT", "gold", "Trefft ihn beide, solange er ausholt");
            say(server, "Ein letzter Schnitt. Für uns alle.");
            Rift.sound(server, "minecraft:block.bell.resonate", 0.8);
        }
        if (timer > 0) {
            // er holt aus
            if (clock % 2 == 0) {
                Rift.cmd(server, in + "particle minecraft:flame " + fmt(bx) + " " + fmt(by + 2.2) + " " + fmt(bz) + " 0.3 1 0.3 0.05 6 force");
            }
            if (--timer == 0) {
                swing(true);
                swing(false);
                pose(server, false);
                Rift.cmd(server, in + "particle minecraft:explosion_emitter " + fmt(bx) + " " + fmt(by + 1) + " " + fmt(bz) + " 2 0.5 2 0 4 force");
                Rift.sound(server, "minecraft:entity.generic.explode", 0.7);
                for (ServerPlayer player : players) {
                    if (distance(player) < 30) {
                        hurt(server, player, 12, false);
                    }
                }
                say(server, TAUNTS[RANDOM.nextInt(TAUNTS.length)]);
                timer = -70; // kurze Pause, dann holt er neu aus
            }
        } else if (++timer == 0) {
            timer = 110;
            lastHit[1] = -1000;
            lastHit[2] = -1000;
            pose(server, true);
            Rift.title(server, "LETZTER SCHNITT", "gold", "Trefft ihn beide, solange er ausholt");
        }
    }

    // ------------------------------------------------------------------ Sieg
    private static void win(MinecraftServer server) {
        finishNow = false;
        killing = true;
        for (String tag : new String[] {"riss_boss", "riss_add", "riss_clone", "riss_fly"}) {
            Rift.cmd(server, "kill @e[tag=" + tag + "]");
        }
        killing = false;
        body = null;
        Rift.cmd(server, "bossbar remove challenge:riss");
        barShown = false;
        Rift.set(server, "#rift_phase", 4);
        say(server, "Endlich … würdige Gegner. Nehmt meine Klingen.");
        Rift.cmd(server, "give @a[scores={sb.role=1}] " + NS + ":katana_light");
        Rift.cmd(server, "give @a[scores={sb.role=2}] " + NS + ":katana_dark");
        Rift.broadcast(server, "§d✦ " + NAME + " ist besiegt. §7In 30 Sekunden bringt euch der Riss zurück in die Oberwelt.");
        Rift.scheduleReturn(600);
        Rift.cmd(server, "function " + NS + ":win/win");
        reset();
    }

    // ------------------------------------------------------------------ Schaden am Boss
    /** Fabric-Ereignis: false = der Treffer zählt nicht. */
    static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        try {
            if (killing || !entity.entityTags().contains("riss_boss")) {
                return true;
            }
            MinecraftServer server = entity.level().getServer();
            if (server == null || Rift.score(server, "#rift_phase") != 3) {
                return true;
            }
            if (!(source.getEntity() instanceof ServerPlayer player)) {
                return false; // nur Spieler können ihm etwas anhaben
            }
            if (source.getDirectEntity() != player) {
                Rift.cmd(server, "execute at " + BOSS + " run playsound minecraft:item.shield.block master @a ~ ~ ~ 1 1.4");
                hint(server, player, "Er wehrt Pfeile und Geschosse mit den Klingen ab.");
                return false;
            }
            int role = Rift.role(server, player);
            if (finale) {
                if (timer > 0) {
                    lastHit[role == 2 ? 2 : 1] = clock;
                    boolean ok1 = !has1 || clock - lastHit[1] <= 110;
                    boolean ok2 = !has2 || clock - lastHit[2] <= 110;
                    if (ok1 && ok2) {
                        finishNow = true;
                    } else {
                        hint(server, player, "Getroffen! Jetzt muss dein Partner auch treffen, bevor er zuschlägt.");
                    }
                }
                return false;
            }
            if (!canHit(role)) {
                Rift.cmd(server, "execute at " + BOSS + " run playsound minecraft:block.anvil.place master @a ~ ~ ~ 0.5 1.8");
                hint(server, player, "Er pariert deine Schläge. Gerade kann nur dein Partner ihn treffen.");
                return false;
            }
            float limit = entity.getMaxHealth() * 0.1F;
            if (entity.getHealth() - amount <= limit) {
                entity.setHealth(limit);
                finale = true;
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
