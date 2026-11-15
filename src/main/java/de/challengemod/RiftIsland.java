package de.challengemod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Die Boss-Insel „Der Riss“: eine runde, schwebende Insel aus hellem Stein mit lila Rissen,
 * einer großen Blüte in der Mitte, acht kleinen Blüten, dunklen Bäumen und Moos-Inselchen drumherum.
 * Die Insel wird immer gleich berechnet. So kennt der Bosskampf die Risse auch nach einem Neustart,
 * ohne die Insel noch einmal zu bauen (generate mit level == null).
 */
final class RiftIsland {
    private static final int R = 40;
    private static final int HALF = 70;
    private static final int SAND = 1, SAND_L = 2, PLAZA = 3, CRACK = 4, PET_D = 5, PET_M = 6, PET_L = 7, WALL = 8;

    /** Risse in vier Gruppen: 0 = Hauptstrahlen, 1 = Ring, 2 = äußere Strahlen, 3 = Kreise um die kleinen Blüten. */
    static final List<List<int[]>> CRACKS = new ArrayList<>();
    static final List<Set<Long>> CRACK_COLUMNS = new ArrayList<>();
    private static long computedFor = Long.MIN_VALUE;

    private RiftIsland() {
    }

    /** Der Block, aus dem die Risse im Ruhezustand bestehen: dunkel mit lila Adern, leuchtet leicht. */
    static BlockState crackState() {
        return Blocks.CRYING_OBSIDIAN.defaultBlockState();
    }

    static double hash(int a, int b, int c) {
        int n = a * 374761393 + b * 668265263 + c * 1274126177;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return (n & 0x7fffffff) / 2147483648.0;
    }

    private static double noise(int x, int z, double scale, int seed) {
        double fx = x / scale;
        double fz = z / scale;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double tz = fz - z0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = hash(x0, z0, seed);
        double b = hash(x0 + 1, z0, seed);
        double c = hash(x0, z0 + 1, seed);
        double d = hash(x0 + 1, z0 + 1, seed);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    private static double angleDiff(double a, double b) {
        double d = (a - b + Math.PI) % (2 * Math.PI);
        if (d < 0) {
            d += 2 * Math.PI;
        }
        return Math.abs(d - Math.PI);
    }

    static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Stellt sicher, dass die Riss-Listen zur Insel bei (cx, top, cz) passen. */
    static void ensure(int cx, int top, int cz) {
        long key = ((long) cx * 31 + top) * 31 + cz;
        if (computedFor != key) {
            generate(null, cx, top, cz);
        }
    }

    /** Baut die Insel (level != null) und merkt sich die Risse. top = Höhe, auf der man in der Mitte steht. */
    static void generate(ServerLevel level, int cx, int top, int cz) {
        CRACKS.clear();
        CRACK_COLUMNS.clear();
        for (int i = 0; i < 4; i++) {
            CRACKS.add(new ArrayList<>());
            CRACK_COLUMNS.add(new HashSet<>());
        }
        computedFor = ((long) cx * 31 + top) * 31 + cz;

        BlockState[] palette = new BlockState[9];
        palette[SAND] = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        palette[SAND_L] = Blocks.CUT_SANDSTONE.defaultBlockState();
        palette[PLAZA] = Blocks.END_STONE_BRICKS.defaultBlockState();
        palette[CRACK] = crackState();
        palette[PET_D] = Blocks.AMETHYST_BLOCK.defaultBlockState();
        palette[PET_M] = Blocks.PURPUR_BLOCK.defaultBlockState();
        palette[PET_L] = Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState();
        palette[WALL] = Blocks.CHISELED_SANDSTONE.defaultBlockState();
        BlockState rockTop = Blocks.SANDSTONE.defaultBlockState();
        BlockState rockMid = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        BlockState rockLow = Blocks.TERRACOTTA.defaultBlockState();

        Random rnd = new Random(11);
        double[] phase = {rnd.nextDouble() * 6.28, rnd.nextDouble() * 6.28, rnd.nextDouble() * 6.28};
        double[][] med = new double[8][2];
        for (int k = 0; k < 8; k++) {
            med[k][0] = 21 * Math.cos(Math.toRadians(22.5 + 45 * k));
            med[k][1] = 21 * Math.sin(Math.toRadians(22.5 + 45 * k));
        }
        List<double[]> mounds = new ArrayList<>();
        for (int tries = 0; tries < 4000 && mounds.size() < 30; tries++) {
            double a = rnd.nextDouble() * 6.28;
            double r = 14 + rnd.nextDouble() * 23;
            double mx = r * Math.cos(a);
            double mz = r * Math.sin(a);
            double mr = 2.6 + rnd.nextDouble() * 2.6;
            int mh = 1 + rnd.nextInt(3);
            boolean ok = true;
            for (double[] m : med) {
                ok &= Math.hypot(mx - m[0], mz - m[1]) >= 5.5 + mr;
            }
            for (double[] m : mounds) {
                ok &= Math.hypot(mx - m[0], mz - m[1]) >= m[2] + mr + 1.5;
            }
            for (int k = 0; k < 8; k++) {
                ok &= angleDiff(a, Math.toRadians(45 * k)) * r >= mr + 1.2;
            }
            if (ok) {
                mounds.add(new double[] {mx, mz, mr, mh});
            }
        }

        int size = HALF * 2 + 1;
        int[][] heights = new int[size][size]; // Höhe über "top", auf der man steht; MIN_VALUE = keine Insel
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                heights[dx + HALF][dz + HALF] = Integer.MIN_VALUE;
                double r = Math.hypot(dx, dz);
                double a = Math.atan2(dz, dx);
                double re = R + 2.2 * Math.sin(3 * a + phase[0]) + 1.5 * Math.sin(7 * a + phase[1]) + 0.9 * Math.sin(13 * a + phase[2]);
                if (r > re) {
                    continue;
                }
                int up = 0;
                int type = SAND;
                int group = -1;
                if (r > 12.6) {
                    for (int k = 0; k < 8; k++) {
                        if (angleDiff(a, Math.toRadians(45 * k) + 0.05 * Math.sin(r * 0.45 + k * 1.7)) * r < 0.62) {
                            group = 0;
                        }
                    }
                    if (group < 0 && Math.abs(r - (29 + 1.6 * Math.sin(4 * a + 1.3) + 0.8 * Math.sin(11 * a))) < 0.62) {
                        group = 1;
                    }
                    if (group < 0 && r > 29) {
                        for (int k = 0; k < 8; k++) {
                            if (angleDiff(a, Math.toRadians(22.5 + 45 * k) + 0.07 * Math.sin(r * 0.6 + k)) * r < 0.6) {
                                group = 2;
                            }
                        }
                    }
                    if (group < 0 && r > 13 && r < 23) {
                        for (double[] m : med) {
                            if (Math.abs(Math.hypot(dx - m[0], dz - m[1]) - 6.2) < 0.55) {
                                group = 3;
                            }
                        }
                    }
                }
                if (group >= 0) {
                    type = CRACK;
                }
                for (double[] m : mounds) {
                    double dm = Math.hypot(dx - m[0], dz - m[1]);
                    if (dm < m[2]) {
                        up = Math.min((int) m[3], (int) (m[2] - dm) + 1);
                        type = SAND_L;
                    }
                }
                for (double[] m : med) {
                    double dm = Math.hypot(dx - m[0], dz - m[1]);
                    if (dm < 4.6) {
                        up = 1;
                        type = PLAZA;
                        double am = Math.atan2(dz - m[1], dx - m[0]);
                        if (dm < 1.2) {
                            type = PET_L;
                        } else if (dm < 1.0 + 2.9 * Math.pow(Math.abs(Math.cos(3 * am)), 0.8)) {
                            type = dm < 2.4 ? PET_M : PET_D;
                        }
                    }
                }
                if (r >= 10.4 && r <= 12.4) {
                    double nearest = Double.MAX_VALUE;
                    for (int k = 0; k < 8; k++) {
                        nearest = Math.min(nearest, angleDiff(a, Math.toRadians(45 * k)) * r);
                    }
                    if (nearest < 1.6) {
                        up = 0;
                        type = nearest < 0.62 ? CRACK : PLAZA;
                        group = 0;
                    } else {
                        up = 2;
                        type = WALL;
                    }
                }
                if (r < 10.4) {
                    up = 0;
                    type = PLAZA;
                    if (r < 2.3) {
                        type = PET_L;
                    } else if (r < 2.6 + 6.6 * Math.pow(Math.abs(Math.cos(4 * a)), 0.75)) {
                        type = Math.abs(r - 5.0) < 0.5 ? PET_L : r < 5 ? PET_M : PET_D;
                    } else if (Math.abs(r - 9.6) < 0.6) {
                        type = PET_D;
                    }
                }
                if (r > re - 1.6 && up == 0 && hash(dx, dz, 5) < 0.6) {
                    up = -1;
                }
                heights[dx + HALF][dz + HALF] = up;
                int x = cx + dx;
                int z = cz + dz;
                if (type == CRACK) {
                    int g = Math.max(group, 0);
                    CRACKS.get(g).add(new int[] {x, top + up - 1, z});
                    CRACK_COLUMNS.get(g).add(column(x, z));
                }
                if (level == null) {
                    continue;
                }
                double thick = 4 + 26 * Math.pow(Math.max(0.0, 1 - (r / re) * (r / re)), 0.8) + 5 * noise(dx, dz, 7, 9);
                if (hash(Math.floorDiv(dx, 2), Math.floorDiv(dz, 2), 31) < 0.02) {
                    thick += 3 + hash(Math.floorDiv(dx, 2), Math.floorDiv(dz, 2), 32) * 5;
                }
                int depth = (int) thick;
                for (int d = 1; d <= depth; d++) {
                    level.setBlock(new BlockPos(x, top - 1 - d, z), d < 9 ? rockTop : d < 18 ? rockMid : rockLow, Rift.FLAGS);
                }
                for (int y = -1; y < up - 1; y++) {
                    level.setBlock(new BlockPos(x, top + y, z), type == WALL ? palette[WALL] : palette[SAND], Rift.FLAGS);
                }
                level.setBlock(new BlockPos(x, top + up - 1, z), palette[type], Rift.FLAGS);
            }
        }
        if (level == null) {
            return;
        }

        // Bäume: dunkler Stamm, flache dunkle Krone mit lila Flecken
        BlockState trunk = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState leafDark = Blocks.OBSIDIAN.defaultBlockState();
        BlockState leafPurple = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        for (int k = 0; k < 10; k++) {
            double a = Math.toRadians(36 * k + 9);
            double rr = 33.5 + (k % 3) * 0.8;
            int tx = (int) Math.round(rr * Math.cos(a));
            int tz = (int) Math.round(rr * Math.sin(a));
            int base = heights[tx + HALF][tz + HALF];
            if (base == Integer.MIN_VALUE) {
                continue;
            }
            int trunkHeight = 4 + k % 2;
            for (int y = 0; y < trunkHeight; y++) {
                level.setBlock(new BlockPos(cx + tx, top + base + y, cz + tz), trunk, Rift.FLAGS);
            }
            int crown = top + base + trunkHeight;
            for (int a2 = -4; a2 <= 4; a2++) {
                for (int b = -1; b <= 2; b++) {
                    for (int c = -4; c <= 4; c++) {
                        boolean in = (a2 * a2 + c * c) / 11.0 + (b * b) / 2.6 <= 1 + hash(tx + a2, b, tz + c) * 0.25;
                        if (in && !(b == -1 && a2 * a2 + c * c < 3)) {
                            level.setBlock(new BlockPos(cx + tx + a2, crown + b, cz + tz + c),
                                    hash(tx + a2, crown + b, tz + c) < 0.16 ? leafPurple : leafDark, Rift.FLAGS);
                        }
                    }
                }
            }
        }

        // Lichter: kleiner Pfosten mit Endstab
        List<int[]> lights = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            for (double d = -0.2; d <= 0.21; d += 0.4) {
                double a = Math.toRadians(45 * k) + d;
                lights.add(new int[] {(int) Math.round(11.4 * Math.cos(a)), (int) Math.round(11.4 * Math.sin(a))});
            }
            double a = Math.atan2(med[k][1], med[k][0]);
            lights.add(new int[] {(int) Math.round(26.6 * Math.cos(a)), (int) Math.round(26.6 * Math.sin(a))});
        }
        for (int k = 0; k < 16; k++) {
            double a = Math.toRadians(22.5 * k + 11);
            lights.add(new int[] {(int) Math.round(36.5 * Math.cos(a)), (int) Math.round(36.5 * Math.sin(a))});
        }
        BlockState post = Blocks.SANDSTONE_WALL.defaultBlockState();
        BlockState rod = Blocks.END_ROD.defaultBlockState();
        for (int[] l : lights) {
            int base = heights[l[0] + HALF][l[1] + HALF];
            if (base != Integer.MIN_VALUE) {
                level.setBlock(new BlockPos(cx + l[0], top + base, cz + l[1]), post, Rift.FLAGS);
                level.setBlock(new BlockPos(cx + l[0], top + base + 1, cz + l[1]), rod, Rift.FLAGS);
            }
        }

        // schwebende Moos-Inselchen rund um die Insel
        BlockState islet = Blocks.SANDSTONE.defaultBlockState();
        BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();
        for (int k = 0; k < 11; k++) {
            double a = Math.toRadians(33 * k + 12 + (rnd.nextDouble() * 16 - 8));
            double rr = 52 + rnd.nextDouble() * 9;
            double ix = rr * Math.cos(a);
            double iz = rr * Math.sin(a);
            double iy = rnd.nextDouble() * 22 - 14;
            double rx = 3.2 + rnd.nextDouble() * 3.8;
            double ry = 3.5 + rnd.nextDouble() * 3.0;
            for (int x = (int) (ix - rx - 1); x <= (int) (ix + rx + 1); x++) {
                for (int z = (int) (iz - rx - 1); z <= (int) (iz + rx + 1); z++) {
                    int highest = Integer.MIN_VALUE;
                    for (int y = (int) (iy - ry - 1); y <= (int) (iy + 1); y++) {
                        double q = ((x - ix) * (x - ix) + (z - iz) * (z - iz)) / (rx * rx)
                                + ((y - iy) * (y - iy)) / (y < iy ? ry * ry : 1.2);
                        if (q <= 1 + hash(x, y, z) * 0.3) {
                            level.setBlock(new BlockPos(cx + x, top + y, cz + z), islet, Rift.FLAGS);
                            highest = y;
                        }
                    }
                    if (highest != Integer.MIN_VALUE) {
                        level.setBlock(new BlockPos(cx + x, top + highest, cz + z), moss, Rift.FLAGS);
                    }
                }
            }
        }
    }
}
