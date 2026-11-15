package de.challengemod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Der Bosskampf auf der Insel: „Die Rissblüte“ – eine riesige Knospe (Shulker, vierfach vergrößert) in der Blüte
 * in der Mitte, umkreist von einem Kranz aus schwebenden Kristallen.
 *  - Die Knospe ist abwechselnd GESCHLOSSEN (unverwundbar, sie greift mit der Insel an) und OFFEN (verwundbar).
 *  - Lichtblüte (weiß): nur der Nether-Spieler (Rolle 2) kann sie verletzen.
 *    Schattenblüte (schwarz): nur der Oberwelt-Spieler (Rolle 1). Die Form wechselt bei jedem Schließen.
 *  - Angriffe: Risse brechen aus, Druckwellen (drüberspringen), Splitterregen, Sog mit Entladung, Schwebe-Geschosse.
 *  - Finale: Die letzten 10 % fallen nur, wenn beide sie innerhalb von 5 Sekunden treffen.
 * Fast alles läuft über normale Befehle (summon, attribute, bossbar …), damit es robust bleibt.
 */
final class RiftBoss {
    static final String NAME = "Die Rissblüte";
    private static final float MAX_HP = 480.0F;
    private static final String BOSS = "@e[tag=riss_boss,limit=1]";
    private static final Random RANDOM = new Random();
    private static final String NO_ROTATION = "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]";

    private static long clock;
    private static int form;          // 0 = Licht, 1 = Schatten
    private static boolean open;
    private static int stateTimer;    // Ticks bis zum Wechsel offen/geschlossen (im Finale: bis zum nächsten Angriff)
    private static int attackIndex;
    private static int secondAttack;  // Ticks bis zum zweiten Angriff einer Runde (-1 = keiner)
    private static int grace;         // Ticks, in denen ein fehlender Boss noch nicht neu gerufen wird
    private static int crackGroup = -1;
    private static int crackStage;    // 0 = ruhig, 1 = leuchtet (Warnung), 2 = klingt ab
    private static int crackCountdown;
    private static int shardWave;
    private static int shardId;
    private static int pullTicks;
    private static double haloAngle;
    private static boolean finale;
    private static boolean finaleAnnounced;
    private static boolean finishNow;
    private static boolean killing;
    private static boolean has1;
    private static boolean has2;
    private static float lastHp = MAX_HP;
    private static final long[] lastHit = {-1000, -1000, -1000};
    private static final List<double[]> rings = new ArrayList<>();   // Radius, Wartezeit
    private static final List<Set<String>> ringHit = new ArrayList<>();
    private static final List<double[]> shards = new ArrayList<>();  // x, y, z, Alter, Nummer
    private static final Map<String, Long> hinted = new HashMap<>();
    private static boolean errorLogged;
    private static boolean barShown;

    private RiftBoss() {
    }

    static void reset() {
        clock = 0;
        form = 0;
        open = false;
        stateTimer = 0;
        attackIndex = 0;
        secondAttack = -1;
        grace = 0;
        crackGroup = -1;
        crackStage = 0;
        crackCountdown = 0;
        shardWave = 0;
        pullTicks = 0;
        finale = false;
        finaleAnnounced = false;
        finishNow = false;
        killing = false;
        lastHp = MAX_HP;
        lastHit[1] = -1000;
        lastHit[2] = -1000;
        rings.clear();
        ringHit.clear();
        shards.clear();
        hinted.clear();
    }

    /** Vor dem Betreten der Insel: Reste eines früheren Kampfes wegräumen. */
    static void prepare(MinecraftServer server) {
        reset();
        killing = true;
        Rift.cmd(server, "kill @e[tag=riss_boss]");
        Rift.cmd(server, "kill @e[tag=riss_add]");
        Rift.cmd(server, "kill @e[tag=riss_halo]");
        Rift.cmd(server, "kill @e[tag=riss_shard]");
        if (Rift.findLevel(server, Rift.ARENA) != null) {
            // ältere Versionen haben in dieser Dimension versehentlich einen Enderdrachen erzeugt
            Rift.cmd(server, "execute in " + Rift.ARENA + " run kill @e[type=minecraft:ender_dragon,distance=0..]");
            Rift.cmd(server, "execute in " + Rift.ARENA + " run kill @e[type=minecraft:end_crystal,distance=0..]");
        }
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

    private static void fling(ServerPlayer player, double x, double y, double z) {
        Vec3 velocity = new Vec3(x, y, z);
        player.setDeltaMovement(velocity);
        player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
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
                    if (dx * dx + dz * dz < 16 * 16) {
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
        Rift.cmd(server, "team modify riss_licht color white");
        Rift.cmd(server, "team add riss_schatten");
        Rift.cmd(server, "team modify riss_schatten color dark_purple");
        Rift.cmd(server, "bossbar add challenge:riss \"" + NAME + "\"");
        Rift.cmd(server, "bossbar set challenge:riss max " + (int) MAX_HP);
        Rift.cmd(server, "bossbar set challenge:riss style notched_10");
        Rift.cmd(server, "bossbar set challenge:riss players @a");
        barShown = true;
        spawnBoss(server, cx, top, cz, MAX_HP);
        Rift.set(server, "#rift_phase", 3);
        form = 1; // beginClosed wechselt sofort auf Licht
        beginClosed(server, cx, top, cz, false);
        stateTimer = 150;
        Rift.title(server, "DIE RISSBLÜTE", "light_purple", "Sie ist nur verwundbar, wenn sie sich öffnet");
        Rift.sound(server, "minecraft:entity.wither.spawn", 0.7);
        Rift.broadcast(server, "§d✦ Die Rissblüte erwacht. §7Geschlossen greift sie an, offen ist sie verwundbar. "
                + "§fWeiß§7: nur der Nether-Spieler trifft. §8Schwarz§7: nur der Oberwelt-Spieler trifft.");
    }

    private static void spawnBoss(MinecraftServer server, int cx, int top, int cz, float hp) {
        String in = in(server);
        Rift.cmd(server, in + "summon minecraft:shulker " + (cx + 0.5) + " " + top + " " + (cz + 0.5)
                + " {Tags:[\"riss_boss\"],PersistenceRequired:1b,NoAI:1b,Silent:1b,Color:0b,Peek:0b}");
        Rift.cmd(server, "data merge entity " + BOSS + " {CustomName:\"" + NAME + "\",CustomNameVisible:1b}");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:scale base set 4");
        Rift.cmd(server, "attribute " + BOSS + " minecraft:max_health base set " + (int) MAX_HP);
        Rift.cmd(server, "attribute " + BOSS + " minecraft:knockback_resistance base set 1");
        Rift.cmd(server, "data merge entity " + BOSS + " {Health:" + hp + "f}");
        Rift.cmd(server, "effect give " + BOSS + " minecraft:glowing infinite 0 true");
        // Kristallkranz
        Rift.cmd(server, "kill @e[tag=riss_halo]");
        for (int k = 0; k < 8; k++) {
            Rift.cmd(server, in + "summon minecraft:block_display " + (cx + 0.5) + " " + (top + 6) + " " + (cz + 0.5)
                    + " {Tags:[\"riss_halo\",\"riss_halo_" + k + "\"],block_state:{Name:\"minecraft:pearlescent_froglight\"},"
                    + "transformation:{" + NO_ROTATION + ",translation:[-0.4f,-0.4f,-0.4f],scale:[0.8f,0.8f,0.8f]},"
                    + "teleport_duration:3,brightness:{sky:15,block:15}}");
        }
        grace = 60;
    }

    /** Farbe der Knospe, des Kranzes und der Bossleiste passend zur Form. */
    private static void applyForm(MinecraftServer server) {
        String color = finale ? "10b" : form == 0 ? "0b" : "15b";
        String halo = finale ? "minecraft:amethyst_block" : form == 0 ? "minecraft:pearlescent_froglight" : "minecraft:crying_obsidian";
        Rift.cmd(server, "data merge entity " + BOSS + " {Color:" + color + ",Peek:" + (open ? "100b" : "0b") + "}");
        Rift.cmd(server, "execute as @e[tag=riss_halo] run data merge entity @s {block_state:{Name:\"" + halo + "\"}}");
        if (finale) {
            Rift.cmd(server, "team leave @e[tag=riss_boss]");
            Rift.cmd(server, "bossbar set challenge:riss color pink");
            Rift.cmd(server, "bossbar set challenge:riss name \"" + NAME + " – trefft sie GLEICHZEITIG\"");
            return;
        }
        Rift.cmd(server, "team join " + (form == 0 ? "riss_licht" : "riss_schatten") + " @e[tag=riss_boss]");
        Rift.cmd(server, "bossbar set challenge:riss color " + (form == 0 ? "white" : "purple"));
        Rift.cmd(server, "bossbar set challenge:riss name \"" + NAME + (form == 0 ? " – Lichtblüte" : " – Schattenblüte")
                + (open ? " (offen)" : " (geschlossen)") + "\"");
    }

    private static void beginClosed(MinecraftServer server, int cx, int top, int cz, boolean enraged) {
        open = false;
        form = 1 - form;
        applyForm(server);
        Rift.sound(server, "minecraft:entity.shulker.close", 0.6);
        if (form == 0) {
            Rift.cmd(server, "title @a actionbar {text:\"Lichtblüte: Gleich kann nur der Nether-Spieler sie verletzen\",color:\"white\"}");
        } else {
            Rift.cmd(server, "title @a actionbar {text:\"Schattenblüte: Gleich kann nur der Oberwelt-Spieler sie verletzen\",color:\"dark_purple\"}");
        }
        stateTimer = enraged ? 150 : 175;
        launchAttack(server, cx, top, cz, enraged);
        secondAttack = enraged ? 65 : -1;
        if (enraged) {
            summonBrood(server);
        }
    }

    private static void beginOpen(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz, boolean enraged) {
        open = true;
        applyForm(server);
        stateTimer = enraged ? 115 : 140;
        Rift.sound(server, "minecraft:entity.shulker.open", 0.6);
        if (form == 0) {
            Rift.title(server, "LICHTBLÜTE OFFEN", "white", "Nether-Spieler: schlag zu! Der andere wehrt die Geschosse ab");
        } else {
            Rift.title(server, "SCHATTENBLÜTE OFFEN", "dark_purple", "Oberwelt-Spieler: schlag zu! Der andere wehrt die Geschosse ab");
        }
        shootBullets(server, players, cx, top, cz);
    }

    private static void launchAttack(MinecraftServer server, int cx, int top, int cz, boolean enraged) {
        int attack = attackIndex++ % 4;
        if (attack == 0) {
            startCracks(server);
        } else if (attack == 1) {
            int count = enraged ? 3 : 2;
            for (int i = 0; i < count; i++) {
                rings.add(new double[] {3.0, 20 + i * 35});
                ringHit.add(new HashSet<>());
            }
            Rift.sound(server, "minecraft:block.beacon.power_select", 0.6);
            Rift.cmd(server, "title @a actionbar {text:\"Druckwellen – springt über die Ringe!\",color:\"light_purple\"}");
        } else if (attack == 2) {
            shardWave = enraged ? 90 : 60;
            Rift.sound(server, "minecraft:block.amethyst_block.chime", 0.6);
            Rift.cmd(server, "title @a actionbar {text:\"Splitterregen – bleibt in Bewegung!\",color:\"light_purple\"}");
        } else {
            pullTicks = 70;
            Rift.sound(server, "minecraft:block.portal.trigger", 1.4);
            Rift.cmd(server, "title @a actionbar {text:\"Sog – lauft von der Blüte weg!\",color:\"light_purple\"}");
        }
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
                    // verschwunden: mit dem letzten Stand zurückholen
                    spawnBoss(server, cx, top, cz, Math.max(lastHp, MAX_HP * 0.1F));
                    applyForm(server);
                }
            } else {
                Rift.cmd(server, "execute store result score #boss_hp sb run data get entity " + BOSS + " Health");
                Rift.cmd(server, "execute store result bossbar challenge:riss value run data get entity " + BOSS + " Health");
                lastHp = Math.max(1, Rift.score(server, "#boss_hp"));
            }
            Rift.cmd(server, "bossbar set challenge:riss players @a");
        }
        boolean enraged = lastHp < MAX_HP * 0.5F;

        if (finale && !finaleAnnounced) {
            finaleAnnounced = true;
            open = true;
            stateTimer = 100;
            secondAttack = -1;
            applyForm(server);
            Rift.title(server, "JETZT GEMEINSAM", "light_purple", "Beide müssen sie innerhalb von 5 Sekunden treffen");
            Rift.sound(server, "minecraft:block.bell.resonate", 1.0);
        }

        // Wechsel offen / geschlossen
        if (--stateTimer <= 0) {
            if (finale) {
                stateTimer = 170;
                launchAttack(server, cx, top, cz, true);
            } else if (open) {
                beginClosed(server, cx, top, cz, enraged);
            } else {
                beginOpen(server, players, cx, top, cz, enraged);
            }
        }
        if (secondAttack > 0 && --secondAttack == 0) {
            secondAttack = -1;
            launchAttack(server, cx, top, cz, true);
        }

        halo(server, cx, top, cz);
        cracks(server, players, top);
        rings(server, players, cx, top, cz);
        shards(server, players);
        pull(server, players, cx, top, cz);
    }

    // ------------------------------------------------------------------ Kristallkranz
    private static void halo(MinecraftServer server, int cx, int top, int cz) {
        if (clock % 2 != 0) {
            return;
        }
        haloAngle += open ? 0.17 : 0.06;
        double radius = open ? 6.0 : 3.6;
        double height = top + (open ? 7.4 : 5.3) + Math.sin(clock * 0.05) * 0.3;
        String in = in(server);
        for (int k = 0; k < 8; k++) {
            double a = haloAngle + k * Math.PI / 4;
            Rift.cmd(server, in + "tp @e[tag=riss_halo_" + k + ",limit=1] " + fmt(cx + 0.5 + radius * Math.cos(a)) + " "
                    + fmt(height) + " " + fmt(cz + 0.5 + radius * Math.sin(a)));
        }
    }

    // ------------------------------------------------------------------ Angriff 1: Risse
    private static void startCracks(MinecraftServer server) {
        if (crackStage != 0) {
            return;
        }
        crackGroup = RANDOM.nextInt(4);
        crackStage = 1;
        crackCountdown = 40;
        paintCracks(server, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
        Rift.sound(server, "minecraft:block.beacon.activate", 1.4);
        Rift.cmd(server, "title @a actionbar {text:\"Die Risse leuchten – runter von den hellen Linien!\",color:\"light_purple\"}");
    }

    private static void cracks(MinecraftServer server, List<ServerPlayer> players, int top) {
        if (crackStage == 0 || --crackCountdown > 0) {
            return;
        }
        if (crackStage == 1) {
            // Ausbruch: wer auf oder direkt neben einem leuchtenden Riss steht, bekommt Schaden
            Set<Long> columns = RiftIsland.CRACK_COLUMNS.get(crackGroup);
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
        paintCracks(server, RiftIsland.crackState());
        crackStage = 0;
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
            paintCracks(server, RiftIsland.crackState());
        }
        crackGroup = -1;
        crackStage = 0;
    }

    // ------------------------------------------------------------------ Angriff 2: Druckwellen
    private static void rings(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        String in = in(server);
        for (int i = rings.size() - 1; i >= 0; i--) {
            double[] ring = rings.get(i);
            if (ring[1] > 0) {
                ring[1]--;
                if (ring[1] == 0) {
                    Rift.sound(server, "minecraft:entity.warden.heartbeat", 0.6);
                }
                continue;
            }
            ring[0] += 0.45;
            double radius = ring[0];
            if (radius > 43) {
                rings.remove(i);
                ringHit.remove(i);
                continue;
            }
            if (clock % 2 == 0) {
                int count = Math.max(14, Math.min(48, (int) (radius * 1.5)));
                for (int k = 0; k < count; k++) {
                    double a = 2 * Math.PI * k / count + clock * 0.07;
                    Rift.cmd(server, in + "particle minecraft:witch " + fmt(cx + 0.5 + radius * Math.cos(a)) + " " + (top + 0.4) + " "
                            + fmt(cz + 0.5 + radius * Math.sin(a)) + " 0.3 0.1 0.3 0 3 force");
                }
            }
            Set<String> already = ringHit.get(i);
            for (ServerPlayer player : players) {
                double dx = player.getX() - (cx + 0.5);
                double dz = player.getZ() - (cz + 0.5);
                double distance = Math.sqrt(dx * dx + dz * dz);
                String name = player.getScoreboardName();
                if (Math.abs(distance - radius) < 0.8 && player.onGround() && distance > 0.5 && already.add(name)) {
                    Rift.cmd(server, "damage " + name + " 7 minecraft:magic");
                    fling(player, dx / distance * 0.9, 0.45, dz / distance * 0.9);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Angriff 3: Splitterregen
    private static void shards(MinecraftServer server, List<ServerPlayer> players) {
        String in = in(server);
        if (shardWave > 0) {
            shardWave--;
            if (shardWave % 6 == 0) {
                for (ServerPlayer player : players) {
                    addShard(server, player.getX(), player.getY(), player.getZ());
                    for (int i = 0; i < 2; i++) {
                        addShard(server, player.getX() + RANDOM.nextDouble() * 12 - 6, player.getY(),
                                player.getZ() + RANDOM.nextDouble() * 12 - 6);
                    }
                }
            }
        }
        for (Iterator<double[]> it = shards.iterator(); it.hasNext();) {
            double[] s = it.next();
            String at = fmt(s[0]) + " " + fmt(s[1]) + " " + fmt(s[2]);
            String tag = "riss_s" + (int) s[4];
            s[3]++;
            if (s[3] == 2) {
                Rift.cmd(server, in + "tp @e[tag=" + tag + ",limit=1] " + at); // gleitet in 24 Ticks herunter
            }
            if (s[3] < 27) {
                if (((int) s[3]) % 5 == 1) {
                    Rift.cmd(server, in + "particle minecraft:witch " + fmt(s[0]) + " " + fmt(s[1] + 0.2) + " " + fmt(s[2])
                            + " 0.9 0.05 0.9 0 10 force");
                }
                continue;
            }
            it.remove();
            Rift.cmd(server, in + "particle minecraft:explosion " + at + " 0.5 0.3 0.5 0 3 force");
            Rift.cmd(server, in + "playsound minecraft:block.amethyst_block.break master @a " + at + " 2 0.7");
            Rift.cmd(server, "execute in " + dimension(server) + " positioned " + at
                    + " as @a[distance=..2.4] run damage @s 7 minecraft:magic");
            Rift.cmd(server, "kill @e[tag=" + tag + "]");
        }
    }

    private static void addShard(MinecraftServer server, double x, double y, double z) {
        int id = shardId++ % 10000;
        shards.add(new double[] {x, y, z, 0, id});
        Rift.cmd(server, in(server) + "summon minecraft:block_display " + fmt(x) + " " + fmt(y + 18) + " " + fmt(z)
                + " {Tags:[\"riss_shard\",\"riss_s" + id + "\"],block_state:{Name:\"minecraft:amethyst_cluster\",Properties:{facing:\"down\"}},"
                + "transformation:{" + NO_ROTATION + ",translation:[-1f,0f,-1f],scale:[2f,2f,2f]},"
                + "teleport_duration:24,brightness:{sky:15,block:15}}");
    }

    // ------------------------------------------------------------------ Angriff 4: Sog und Entladung
    private static void pull(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        if (pullTicks <= 0) {
            return;
        }
        pullTicks--;
        String in = in(server);
        if (pullTicks % 4 == 0) {
            Rift.cmd(server, in + "particle minecraft:reverse_portal " + (cx + 0.5) + " " + (top + 2) + " " + (cz + 0.5)
                    + " 7 1.5 7 0.2 60 force");
        }
        for (ServerPlayer player : players) {
            double dx = (cx + 0.5) - player.getX();
            double dz = (cz + 0.5) - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < 0.5) {
                continue;
            }
            if (pullTicks > 0) {
                if (distance > 4.5) {
                    Vec3 velocity = player.getDeltaMovement().add(new Vec3(dx / distance * 0.075, 0, dz / distance * 0.075));
                    player.setDeltaMovement(velocity);
                    player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
                }
            } else if (distance < 11) {
                // Entladung: wer zu nah an der Blüte ist, wird getroffen und weggeschleudert
                Rift.cmd(server, "damage " + player.getScoreboardName() + " 8 minecraft:magic");
                fling(player, -dx / distance * 1.1, 0.55, -dz / distance * 1.1);
            }
        }
        if (pullTicks == 0) {
            Rift.cmd(server, in + "particle minecraft:explosion_emitter " + (cx + 0.5) + " " + (top + 2) + " " + (cz + 0.5) + " 3 1 3 0 6 force");
            Rift.cmd(server, in + "playsound minecraft:entity.generic.explode master @a " + (cx + 0.5) + " " + top + " " + (cz + 0.5) + " 3 0.6");
        }
    }

    // ------------------------------------------------------------------ Geschosse und Rissbrut
    private static void shootBullets(MinecraftServer server, List<ServerPlayer> players, int cx, int top, int cz) {
        String in = in(server);
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            long most = id.getMostSignificantBits();
            long least = id.getLeastSignificantBits();
            Rift.cmd(server, in + "summon minecraft:shulker_bullet " + (cx + 0.5) + " " + (top + 10) + " " + (cz + 0.5)
                    + " {Tags:[\"riss_add\"],Target:[I;" + (int) (most >> 32) + "," + (int) most + "," + (int) (least >> 32) + ","
                    + (int) least + "],Steps:1,TXD:0.0d,TYD:0.2d,TZD:0.0d}");
        }
    }

    private static void summonBrood(MinecraftServer server) {
        Rift.cmd(server, "execute store result score #adds_n sb if entity @e[type=minecraft:endermite,tag=riss_add]");
        List<int[]> blocks = RiftIsland.CRACKS.get(RANDOM.nextInt(4));
        if (Rift.score(server, "#adds_n") >= 4 || blocks.isEmpty()) {
            return;
        }
        String in = in(server);
        for (int i = 0; i < 2; i++) {
            int[] b = blocks.get(RANDOM.nextInt(blocks.size()));
            Rift.cmd(server, in + "summon minecraft:endermite " + (b[0] + 0.5) + " " + (b[1] + 1) + " " + (b[2] + 0.5)
                    + " {Tags:[\"riss_add\"],PersistenceRequired:1b}");
        }
    }

    // ------------------------------------------------------------------ Sieg
    private static void win(MinecraftServer server) {
        finishNow = false;
        for (int g = 0; g < 4; g++) {
            crackGroup = g;
            paintCracks(server, RiftIsland.crackState());
        }
        killing = true;
        Rift.cmd(server, "kill @e[tag=riss_boss]");
        Rift.cmd(server, "kill @e[tag=riss_add]");
        Rift.cmd(server, "kill @e[tag=riss_halo]");
        Rift.cmd(server, "kill @e[tag=riss_shard]");
        killing = false;
        Rift.cmd(server, "bossbar remove challenge:riss");
        barShown = false;
        Rift.set(server, "#rift_phase", 4);
        Rift.broadcast(server, "§d✦ " + NAME + " ist verwelkt. §7In 30 Sekunden bringt euch der Riss zurück in die Oberwelt.");
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
                return false; // nur Spieler können ihr etwas anhaben
            }
            int role = Rift.role(server, player);
            if (!finale) {
                if (!open) {
                    hint(server, player, "Die Blüte ist geschlossen. Weich aus und warte, bis sie sich öffnet.");
                    return false;
                }
                int need = form == 0 ? 2 : 1;
                boolean needPresent = need == 1 ? has1 : has2;
                if (needPresent && role != need) {
                    hint(server, player, form == 0 ? "Lichtblüte: Nur der Nether-Spieler kann sie jetzt verletzen. Wehr die Geschosse ab!"
                            : "Schattenblüte: Nur der Oberwelt-Spieler kann sie jetzt verletzen. Wehr die Geschosse ab!");
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
