package de.challengemod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.ScoreHolder;

/** Liest Werte, die das Challenge-Datenpaket im Scoreboard ablegt (z. B. "#state" in "sb"). */
final class Scores {
    private Scores() {
    }

    static int get(MinecraftServer server, String holder, String objective, int fallback) {
        var scoreboard = server.getScoreboard();
        var obj = scoreboard.getObjective(objective);
        if (obj == null) {
            return fallback;
        }
        var info = scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly(holder), obj);
        return info == null ? fallback : info.value();
    }

    /** true, wenn die Challenge gerade läuft (nicht pausiert, nicht vorbei, nicht aus). */
    static boolean running(MinecraftServer server) {
        return get(server, "#state", "sb", 4) == 1 && get(server, "#paused", "sb", 0) == 0;
    }
}
