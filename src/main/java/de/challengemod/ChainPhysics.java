package de.challengemod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Kette wie in "Chained Together": Die Spieler hängen (nach UUID sortiert) hintereinander an einer Kette.
 * Ist ein Kettenglied länger als die eingestellte Kettenlänge, zieht die Kette beide mit echter
 * Geschwindigkeit zueinander – auch nach oben, wenn einer abstürzt.
 * Die sichtbare Kette und das Mitziehen durch Portale macht das Datenpaket (chain/draw, chain/dimfollow).
 */
final class ChainPhysics {
    private static final double MAX_SPEED = 1.4;
    private static boolean errorLogged;

    private ChainPhysics() {
    }

    static void tick(MinecraftServer server) {
        try {
            if (!Scores.running(server) || Scores.get(server, "#m_chain", "sb.cfg", 0) != 1) {
                return;
            }
            double length = Math.max(3, Scores.get(server, "#chain_len", "sb.cfg", 6));
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                    players.add(p);
                }
            }
            if (players.size() < 2) {
                return;
            }
            players.sort(Comparator.comparing(ServerPlayer::getStringUUID));
            for (int i = 0; i + 1 < players.size(); i++) {
                link(players.get(i), players.get(i + 1), length);
            }
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                ChallengeMod.LOGGER.error("Ketten-Physik ist ausgefallen (wird nur einmal gemeldet)", t);
            }
        }
    }

    private static void link(ServerPlayer a, ServerPlayer b, double length) {
        if (a.level() != b.level()) {
            return; // verschiedene Dimensionen: das Datenpaket zieht den Zurückgebliebenen nach 3 s hinterher
        }
        Vec3 delta = b.position().subtract(a.position());
        double distance = delta.length();
        if (distance <= length || distance < 1.0E-4) {
            return;
        }
        Vec3 dir = delta.scale(1.0 / distance);
        double excess = distance - length;
        double strength = Math.min(0.05 + excess * 0.08, 0.9);
        pull(a, dir, strength);
        pull(b, dir.scale(-1.0), strength);
    }

    private static void pull(ServerPlayer player, Vec3 dir, double strength) {
        Vec3 velocity = player.getDeltaMovement();
        double along = velocity.dot(dir);
        if (along < 0) {
            velocity = velocity.subtract(dir.scale(along)); // Bewegung weg vom Partner aufheben
        }
        velocity = velocity.add(dir.scale(strength));
        if (velocity.length() > MAX_SPEED) {
            velocity = velocity.normalize().scale(MAX_SPEED);
        }
        player.setDeltaMovement(velocity);
        // schickt die neue Geschwindigkeit direkt an den Client
        player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
        if (dir.y > 0.3) {
            player.resetFallDistance(); // wer hochgezogen wird, sammelt keinen Fallschaden
        }
    }
}
