package de.challengemod;

/** Automatisch erzeugt von build.py – Reihenfolge der Werte für /challenge apply. */
final class PageKeys {
    private PageKeys() {
    }

    static String[] keys(String page) {
        return switch (page) {
            case "rules" -> new String[] {"dist", "diff", "regen", "uhc", "schmerz", "band", "rest"};
            case "chaos" -> new String[] {"clen", "chain", "mut", "perm", "mobrand", "brand", "fx"};
            case "goal" -> new String[] {"goal", "death", "role"};
            case "world" -> new String[] {"cmax", "cshift", "cbiome", "mobdrop", "chest", "craft"};
            default -> null;
        };
    }
}
